package com.scione.scm.bill.application;

import com.scione.scm.bill.application.port.DeliveryFollowUpWorkbook;
import com.scione.scm.bill.application.port.DeliveryFollowUpWorkbook.InputRow;
import com.scione.scm.bill.application.port.DeliveryFollowUpWorkbook.RowError;
import com.scione.scm.bill.application.vo.DeliveryFollowUpImportVO;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.config.DeliveryAlertProperties;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertRepository;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import com.scione.scm.bill.domain.procurementlog.enums.ProcurementBusinessType;
import com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** 导入编排：解析校验在事务外；有效行与审计同事务；失败审计在回滚之后单独保存。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeliveryFollowUpImportService {
    private static final int KEY_BATCH_SIZE = 500;//查库核对"采购单+SKU"时每批500条
    private static final String DOWNLOAD_PREFIX = "/api/v1/delivery-alerts/imports/";//错误文件下载URL前缀
    private static final String ERROR_FOLDER = "delivery-alerts/import-errors";//错误文件存储目录
    private final DeliveryAlertRepository repository;//读写跟单导入数据（reminder_excel_import）
    private final DeliveryAlertProperties properties;//跟单看板配置（起算日期、导入模板objectKey）
    private final DeliveryFollowUpWorkbook workbook;//Excel解析/错误文件生成
    private final ProcurementOperationLogRecorder logRecorder;//导入审计日志
    private final TransactionTemplate transactionTemplate;//事务控制：有效行+审计同事务
    private final FileApplicationService fileService;//错误文件上传下载

    public byte[] template() {
        String key = properties.getImportTemplateObjectKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "导入模板尚未配置，请先上传模板并配置objectKey");
        }
        return fileService.download(key);
    }

    /** 把错误行生成一份错误Excel上传到文件服务，返回它的objectKey（用于下载）。 */
    private String saveErrors(List<RowError> errors) {
        return fileService.uploadBytes("跟单导入错误-" + UUID.randomUUID() + ".xlsx",//随机文件名，避免覆盖
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook.errorFile(errors),
                ERROR_FOLDER).objectKey();//上传到错误文件目录，返回objectKey
    }

    /** 文件尚未成功读取时，也留下失败审计及可下载的文件级错误说明。 */
    public DeliveryFollowUpImportVO rejectFile(String fileName, String message, String operator) {
        validateOperator(operator);//校验操作人邮箱
        String reference;//错误文件objectKey
        try {
            reference = saveErrors(List.of(new RowError(0, List.of(), message)));//把文件级错误生成错误文件
        } catch (RuntimeException exception) {
            logRecorder.recordRequired(ProcurementBusinessType.DELIVERY_FOLLOW_UP, 0L, safeFileName(fileName),
                    ProcurementOperationType.IMPORT_FAILED, operator,
                    Map.of("message", message + "；错误文件未能保存"));//错误文件都保存失败，也要留审计
            throw exception;
        }
        Long id = logRecorder.recordRequired(ProcurementBusinessType.DELIVERY_FOLLOW_UP, 0L, safeFileName(fileName),
                ProcurementOperationType.IMPORT_FAILED, operator, details(0, 0, 0, 1, reference, message));//记失败审计
        return result(1, 0, 0, 0, 1, id, reference, message);//返回失败结果
    }

    /** 校验操作人邮箱：非空、去空白后有内容、长度不超过64。 */
    private static void validateOperator(String operator) {
        if (operator == null || operator.isBlank() || operator.length() > 64) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "缺少有效的当前登录用户邮箱");
        }
    }

    /** 导入主流程：解析→行校验→核对看板有效键→事务内发布；任一步失败都回滚业务数据并生成错误文件。 */
    public DeliveryFollowUpImportVO importFile(String fileName, byte[] content, String operator) {
        validateOperator(operator);//先校验操作人邮箱是否有效
        String name = safeFileName(fileName);//清洗文件名（防路径穿越、超长）
        // 第1步：解析文件 + 行校验（都在事务外，坏行记进 errors 不影响其他有效行）。
        DeliveryFollowUpWorkbook.Parsed parsed = workbook.parse(name, content);//解析Excel，得到有效行和错误行
        List<RowError> errors = new ArrayList<>(parsed.errors());//错误行列表（先复制解析阶段的错误）
        List<Candidate> candidates = validate(parsed.rows(), errors);//对有效行做行级校验（必填/长度/日期/重复），产出候选
        try {
            // 第2步：分批查库核对「采购单+SKU」是否属于当前看板有效数据，不在范围的记错误行。
            Set<DeliveryFollowUp.Key> existingKeys = new HashSet<>();//看板上已存在的"采购单+SKU"键集合
            List<DeliveryFollowUp.Key> keys = candidates.stream().map(c -> c.value().key()).toList();//所有候选的定位键
            for (int offset = 0; offset < keys.size(); offset += KEY_BATCH_SIZE) {//每500个键一批去查库
                existingKeys.addAll(repository.findFollowUpKeys(
                        keys.subList(offset, Math.min(offset + KEY_BATCH_SIZE, keys.size())), properties.getStartDate()));//查这批键在看板上是否存在
            }
            List<Candidate> valid = new ArrayList<>();//通过"看板有效性"核对的有效候选
            for (Candidate candidate : candidates) {
                if (existingKeys.contains(candidate.value().key())) {
                    valid.add(candidate);//键在看板上存在，纳入valid
                } else {
                    errors.add(new RowError(candidate.input().rowNumber(), candidate.input().values(),
                            "采购单与SKU的组合不属于当前看板有效数据"));//不在看板，记错误行
                }
            }
            String errorFile = errors.isEmpty() ? null : saveErrors(errors);//有错误就生成错误文件，否则为null
            // 统一加锁顺序，减少两个导入文件同时包含相同业务键时的死锁风险。
            valid.sort(Comparator.comparing((Candidate c) -> c.value().orderSn())
                    .thenComparing(c -> c.value().sku()));//按"采购单号+SKU"排序，保证加锁顺序一致
            int total = parsed.rows().size() + parsed.errors().size();//本次总行数（有效+错误）
            // 第3步：有效行 + 审计日志在同一个事务里发布，任一失败整体回滚。
            return transactionTemplate.execute(status -> publish(name, operator.trim(), valid, errors.size(),
                    total, errorFile));//在事务里执行publish，operator去掉首尾空格
        } catch (RuntimeException exception) {
            log.error("跟单情况导入未提交：file={}", name, exception);
            // 不泄露SQL、连接信息等底层异常；将所有原行放入错误文件，明确本批没有提交。
            List<RowError> failed = new ArrayList<>(parsed.errors());//失败行：先放解析错误
            parsed.rows().forEach(row -> failed.add(new RowError(row.rowNumber(), row.values(),
                    "本次导入未提交，请稍后重试；如持续失败请联系管理员")));//再把所有原行都标记为"未提交"
            if (failed.isEmpty()) {
                failed.add(new RowError(0, List.of(), "本次导入未提交，请联系管理员"));//一行都没有就放个占位错误
            }
            String errorFile;//错误文件地址
            try {
                errorFile = saveErrors(failed);//生成包含所有行的错误文件
            } catch (RuntimeException storageException) {
                log.error("跟单错误文件保存失败", storageException);
                // 文件存储故障也必须尝试保存失败审计，不返回虚假的下载地址。
                logRecorder.recordRequired(ProcurementBusinessType.DELIVERY_FOLLOW_UP, 0L, name,
                        ProcurementOperationType.IMPORT_FAILED, operator,
                        Map.of("message", "导入失败且错误文件保存失败，请联系管理员"));
                throw new BusinessException(ResultCode.SYSTEM_ERROR, "导入失败，错误文件未能保存，请联系管理员");
            }
            Long logId = logRecorder.recordRequired(ProcurementBusinessType.DELIVERY_FOLLOW_UP, 0L, name,
                    ProcurementOperationType.IMPORT_FAILED, operator,
                    details(0, 0, 0, failed.size(), errorFile, "导入未提交，业务数据已回滚"));//记失败审计
            return result(failed.size(), 0, 0, 0, failed.size(), logId, errorFile, "导入失败，未提交数据");
        }
    }

    /** 事务内发布：逐条加锁比对，信息不变跳过、有变则写入，最后记录审计日志。 */
    private DeliveryFollowUpImportVO publish(String fileName, String operator, List<Candidate> valid,
                                             int failed, int total, String errorFile) {
        int inserted = 0;//新增条数
        int updated = 0;//更新条数
        int unchanged = 0;//无变化条数
        for (Candidate candidate : valid) {
            DeliveryFollowUp value = candidate.value();//要写入的跟单对象
            DeliveryFollowUp previous = repository.lockFollowUp(value.key());//加锁并读库里已有的同键记录
            // 信息与库里完全一致则跳过，不产生无意义的写操作。
            if (value.sameInformation(previous)) {
                unchanged++;
                continue;
            }
            // 不合并非空字段：新文件中的空白明确清除旧值。
            repository.saveFollowUp(value, operator);//写入（新增或覆盖）
            if (previous == null) {
                inserted++;//库里没有，算新增
            } else {
                updated++;//库里有，算更新
            }
        }
        Long logId = logRecorder.recordRequired(ProcurementBusinessType.DELIVERY_FOLLOW_UP, 0L, fileName,
                failed == 0 ? ProcurementOperationType.IMPORT : ProcurementOperationType.IMPORT_FAILED,
                operator, details(inserted, updated, unchanged, failed, errorFile,
                        failed == 0 ? "导入完成" : "合法行已提交，错误行未写入"));
        return result(total, inserted, updated, unchanged, failed, logId, errorFile,
                failed == 0 ? "导入完成" : "存在失败行，请下载错误文件查看");
    }

    /** 行级校验：必填、长度、日期格式、同文件内「采购单+SKU」重复；通过的行转成 Candidate。 */
    /** 行级校验：必填、长度、日期格式、同文件内「采购单+SKU」重复；通过的行转成 Candidate。 */
    private static List<Candidate> validate(List<InputRow> rows, List<RowError> errors) {
        Map<DeliveryFollowUp.Key, Integer> counts = new HashMap<>();//统计每个"采购单+SKU"键出现的次数，用于查重
        // 解析阶段已判错的行也参与重复检测，不能让同键的另一行悄悄覆盖。
        for (RowError error : errors) {
            if (error.values().size() == 6) {//错误行得有完整6列才能取到采购单和SKU
                List<String> values = error.values();
                counts.merge(new DeliveryFollowUp.Key(values.get(0), values.get(1)), 1, Integer::sum);//把错误行的键也计入次数
            }
        }
        for (InputRow row : rows) {
            List<String> values = row.values();
            counts.merge(new DeliveryFollowUp.Key(values.get(0), values.get(1)), 1, Integer::sum);//有效行的键计入次数
        }
        List<Candidate> result = new ArrayList<>();//通过校验的候选
        for (InputRow row : rows) {
            List<String> v = row.values();//当前行的六列值
            DeliveryFollowUp.Key key = new DeliveryFollowUp.Key(v.get(0), v.get(1));//该行的定位键（采购单+SKU）
            List<String> messages = new ArrayList<>();//该行的校验错误信息
            // 逐列校验：采购单/SKU/跟单员必填，原因/备注可空但限长。
            checkLength(v.get(0), "采购单", 32, true, messages);
            checkLength(v.get(1), "SKU", 64, true, messages);
            checkLength(v.get(2), "跟单员", 64, true, messages);
            checkLength(v.get(4), "原因", 512, false, messages);
            checkLength(v.get(5), "备注", 512, false, messages);
            // 预计交期可空，但填写时必须严格匹配 yyyy-MM-dd 且年份合法。
            LocalDate deliveryDate = null;//解析出的预计交期，空则保持null
            if (!v.get(3).isBlank()) {//第4列(预计交期)非空才校验
                try {
                    if (!v.get(3).matches("\\d{4}-\\d{2}-\\d{2}")) {
                        throw new DateTimeParseException("invalid format", v.get(3), 0);//格式不是yyyy-MM-dd，抛异常
                    }
                    deliveryDate = LocalDate.parse(v.get(3));//解析成日期
                    if (deliveryDate.getYear() < 1000) {
                        throw new DateTimeParseException("invalid MySQL date", v.get(3), 0);//年份太小，MySQL DATE类型不支持，抛异常
                    }
                } catch (DateTimeParseException exception) {
                    messages.add("预计交期必须为有效的yyyy-MM-dd日期或Excel日期单元格");//日期非法，记错误
                }
            }
            // 同文件内同一采购单+SKU 出现多次时，这些行全部判错，不做「取其一」的静默覆盖。
            if (counts.get(key) > 1) {
                messages.add("同一文件内采购单＋SKU重复，重复行全部不导入");
            }
            if (!messages.isEmpty()) {
                errors.add(new RowError(row.rowNumber(), v, String.join("；", messages)));//有错误就记进错误行
            } else {
                result.add(new Candidate(row, new DeliveryFollowUp(v.get(0), v.get(1), v.get(2),
                        deliveryDate, nullable(v.get(4)), nullable(v.get(5)), null)));//通过校验，构造成候选（采购单/SKU/跟单员/交期/原因/备注）
            }
        }
        return result;
    }

    /** 校验单个字段：必填为空报「必填」，超长报「不能超过N字符」。 */
    private static void checkLength(String value, String name, int maximum, boolean required, List<String> errors) {
        if (required && value.isBlank()) {
            errors.add(name + "必填");//必填字段为空
        } else if (value.codePointCount(0, value.length()) > maximum) {
            errors.add(name + "不能超过" + maximum + "个字符");//超出最大长度
        }
    }

    /** 空白转null：可选字段填写空白时存null，而不是存空串。 */
    private static String nullable(String value) {
        return value.isBlank() ? null : value;
    }

    /** 清洗文件名：只取路径最后一段，去掉换行/制表符，超200字符截尾，防止路径穿越和超长。 */
    private static String safeFileName(String fileName) {
        String name = fileName == null ? "未命名文件" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", "_");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    /** 组装审计日志的详情map：导入/更新的各计数 + 结果信息 + 错误文件地址（可选）。 */
    private static Map<String, Object> details(int inserted, int updated, int unchanged, int failed,
                                               String errorFile, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inserted", inserted);
        details.put("updated", updated);
        details.put("unchanged", unchanged);
        details.put("failed", failed);
        details.put("message", message);
        if (errorFile != null) {
            details.put("errorFile", errorFile);
        }
        return details;
    }

    /** 组装返回给前端的导入结果VO；有错误文件时拼出可下载的错误文件URL。 */
    private static DeliveryFollowUpImportVO result(int total, int inserted, int updated, int unchanged,
                                                   int failed, Long logId, String errorFile, String message) {
        return new DeliveryFollowUpImportVO(total, inserted, updated, unchanged, failed, logId,
                errorFile == null ? null : DOWNLOAD_PREFIX + logId + "/errors", message);
    }

    public byte[] downloadErrors(Long logId) {
        return fileService.download(logRecorder.findImportErrorFileKey(logId));
    }

    /** 一条通过行级校验的候选：input原始行（含行号）、value解析出的跟单对象。 */
    private record Candidate(InputRow input, DeliveryFollowUp value) {
    }
}
