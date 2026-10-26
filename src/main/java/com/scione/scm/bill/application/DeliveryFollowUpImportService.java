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
    private static final int KEY_BATCH_SIZE = 500;
    private static final String DOWNLOAD_PREFIX = "/api/v1/delivery-alerts/imports/";
    private static final String ERROR_FOLDER = "delivery-alerts/import-errors";
    private final DeliveryAlertRepository repository;
    private final DeliveryAlertProperties properties;
    private final DeliveryFollowUpWorkbook workbook;
    private final ProcurementOperationLogRecorder logRecorder;
    private final TransactionTemplate transactionTemplate;
    private final FileApplicationService fileService;

    public byte[] template() {
        String key = properties.getImportTemplateObjectKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "导入模板尚未配置，请先上传模板并配置objectKey");
        }
        return fileService.download(key);
    }

    private String saveErrors(List<RowError> errors) {
        return fileService.uploadBytes("跟单导入错误-" + UUID.randomUUID() + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook.errorFile(errors),
                ERROR_FOLDER).objectKey();
    }

    /** 文件尚未成功读取时，也留下失败审计及可下载的文件级错误说明。 */
    public DeliveryFollowUpImportVO rejectFile(String fileName, String message, String operator) {
        validateOperator(operator);
        String reference;
        try {
            reference = saveErrors(List.of(new RowError(0, List.of(), message)));
        } catch (RuntimeException exception) {
            logRecorder.recordRequired(ProcurementBusinessType.DELIVERY_FOLLOW_UP, 0L, safeFileName(fileName),
                    ProcurementOperationType.IMPORT_FAILED, operator,
                    Map.of("message", message + "；错误文件未能保存"));
            throw exception;
        }
        Long id = logRecorder.recordRequired(ProcurementBusinessType.DELIVERY_FOLLOW_UP, 0L, safeFileName(fileName),
                ProcurementOperationType.IMPORT_FAILED, operator, details(0, 0, 0, 1, reference, message));
        return result(1, 0, 0, 0, 1, id, reference, message);
    }

    private static void validateOperator(String operator) {
        if (operator == null || operator.isBlank() || operator.length() > 64) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "缺少有效的当前登录用户邮箱");
        }
    }

    public DeliveryFollowUpImportVO importFile(String fileName, byte[] content, String operator) {
        validateOperator(operator);
        String name = safeFileName(fileName);
        DeliveryFollowUpWorkbook.Parsed parsed = workbook.parse(name, content);
        List<RowError> errors = new ArrayList<>(parsed.errors());
        List<Candidate> candidates = validate(parsed.rows(), errors);
        try {
            Set<DeliveryFollowUp.Key> existingKeys = new HashSet<>();
            List<DeliveryFollowUp.Key> keys = candidates.stream().map(c -> c.value().key()).toList();
            for (int offset = 0; offset < keys.size(); offset += KEY_BATCH_SIZE) {
                existingKeys.addAll(repository.findFollowUpKeys(
                        keys.subList(offset, Math.min(offset + KEY_BATCH_SIZE, keys.size())), properties.getStartDate()));
            }
            List<Candidate> valid = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (existingKeys.contains(candidate.value().key())) {
                    valid.add(candidate);
                } else {
                    errors.add(new RowError(candidate.input().rowNumber(), candidate.input().values(),
                            "采购单与SKU的组合不属于当前看板有效数据"));
                }
            }
            String errorFile = errors.isEmpty() ? null : saveErrors(errors);
            // 统一加锁顺序，减少两个导入文件同时包含相同业务键时的死锁风险。
            valid.sort(Comparator.comparing((Candidate c) -> c.value().orderSn())
                    .thenComparing(c -> c.value().sku()));
            int total = parsed.rows().size() + parsed.errors().size();
            return transactionTemplate.execute(status -> publish(name, operator.trim(), valid, errors.size(),
                    total, errorFile));
        } catch (RuntimeException exception) {
            log.error("跟单情况导入未提交：file={}", name, exception);
            // 不泄露SQL、连接信息等底层异常；将所有原行放入错误文件，明确本批没有提交。
            List<RowError> failed = new ArrayList<>(parsed.errors());
            parsed.rows().forEach(row -> failed.add(new RowError(row.rowNumber(), row.values(),
                    "本次导入未提交，请稍后重试；如持续失败请联系管理员")));
            if (failed.isEmpty()) {
                failed.add(new RowError(0, List.of(), "本次导入未提交，请联系管理员"));
            }
            String errorFile;
            try {
                errorFile = saveErrors(failed);
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
                    details(0, 0, 0, failed.size(), errorFile, "导入未提交，业务数据已回滚"));
            return result(failed.size(), 0, 0, 0, failed.size(), logId, errorFile, "导入失败，未提交数据");
        }
    }

    private DeliveryFollowUpImportVO publish(String fileName, String operator, List<Candidate> valid,
                                             int failed, int total, String errorFile) {
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (Candidate candidate : valid) {
            DeliveryFollowUp value = candidate.value();
            DeliveryFollowUp previous = repository.lockFollowUp(value.key());
            if (value.sameInformation(previous)) {
                unchanged++;
                continue;
            }
            // 不合并非空字段：新文件中的空白明确清除旧值。
            repository.saveFollowUp(value, operator);
            if (previous == null) {
                inserted++;
            } else {
                updated++;
            }
        }
        Long logId = logRecorder.recordRequired(ProcurementBusinessType.DELIVERY_FOLLOW_UP, 0L, fileName,
                failed == 0 ? ProcurementOperationType.IMPORT : ProcurementOperationType.IMPORT_FAILED,
                operator, details(inserted, updated, unchanged, failed, errorFile,
                        failed == 0 ? "导入完成" : "合法行已提交，错误行未写入"));
        return result(total, inserted, updated, unchanged, failed, logId, errorFile,
                failed == 0 ? "导入完成" : "存在失败行，请下载错误文件查看");
    }

    private static List<Candidate> validate(List<InputRow> rows, List<RowError> errors) {
        Map<DeliveryFollowUp.Key, Integer> counts = new HashMap<>();
        // 解析阶段已判错的行也参与重复检测，不能让同键的另一行悄悄覆盖。
        for (RowError error : errors) {
            if (error.values().size() == 6) {
                List<String> values = error.values();
                counts.merge(new DeliveryFollowUp.Key(values.get(0), values.get(1)), 1, Integer::sum);
            }
        }
        for (InputRow row : rows) {
            List<String> values = row.values();
            counts.merge(new DeliveryFollowUp.Key(values.get(0), values.get(1)), 1, Integer::sum);
        }
        List<Candidate> result = new ArrayList<>();
        for (InputRow row : rows) {
            List<String> v = row.values();
            DeliveryFollowUp.Key key = new DeliveryFollowUp.Key(v.get(0), v.get(1));
            List<String> messages = new ArrayList<>();
            checkLength(v.get(0), "采购单", 32, true, messages);
            checkLength(v.get(1), "SKU", 64, true, messages);
            checkLength(v.get(2), "跟单员", 64, true, messages);
            checkLength(v.get(4), "原因", 512, false, messages);
            checkLength(v.get(5), "备注", 512, false, messages);
            LocalDate deliveryDate = null;
            if (!v.get(3).isBlank()) {
                try {
                    if (!v.get(3).matches("\\d{4}-\\d{2}-\\d{2}")) {
                        throw new DateTimeParseException("invalid format", v.get(3), 0);
                    }
                    deliveryDate = LocalDate.parse(v.get(3));
                    if (deliveryDate.getYear() < 1000) {
                        throw new DateTimeParseException("invalid MySQL date", v.get(3), 0);
                    }
                } catch (DateTimeParseException exception) {
                    messages.add("预计交期必须为有效的yyyy-MM-dd日期或Excel日期单元格");
                }
            }
            if (counts.get(key) > 1) {
                messages.add("同一文件内采购单＋SKU重复，重复行全部不导入");
            }
            if (!messages.isEmpty()) {
                errors.add(new RowError(row.rowNumber(), v, String.join("；", messages)));
            } else {
                result.add(new Candidate(row, new DeliveryFollowUp(v.get(0), v.get(1), v.get(2),
                        deliveryDate, nullable(v.get(4)), nullable(v.get(5)), null)));
            }
        }
        return result;
    }

    private static void checkLength(String value, String name, int maximum, boolean required, List<String> errors) {
        if (required && value.isBlank()) {
            errors.add(name + "必填");
        } else if (value.codePointCount(0, value.length()) > maximum) {
            errors.add(name + "不能超过" + maximum + "个字符");
        }
    }

    private static String nullable(String value) {
        return value.isBlank() ? null : value;
    }

    private static String safeFileName(String fileName) {
        String name = fileName == null ? "未命名文件" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", "_");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

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

    private static DeliveryFollowUpImportVO result(int total, int inserted, int updated, int unchanged,
                                                   int failed, Long logId, String errorFile, String message) {
        return new DeliveryFollowUpImportVO(total, inserted, updated, unchanged, failed, logId,
                errorFile == null ? null : DOWNLOAD_PREFIX + logId + "/errors", message);
    }

    public byte[] downloadErrors(Long logId) {
        return fileService.download(logRecorder.findImportErrorFileKey(logId));
    }

    private record Candidate(InputRow input, DeliveryFollowUp value) {
    }
}
