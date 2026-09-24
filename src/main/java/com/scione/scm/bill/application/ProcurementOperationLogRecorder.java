package com.scione.scm.bill.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.domain.procurementlog.enums.ProcurementBusinessType;
import com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ProcurementOperationLogMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ProcurementOperationLogPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供应链操作日志（procurement_operation_log）的统一写入入口。
 *
 * <p>各业务写操作在接口层调用本类，把「谁、对哪条业务数据、做了什么」落到操作日志表，
 * 由本类集中处理列宽截断、详情 JSON 序列化与写入容错，调用方只需提供业务信息。</p>
 *
 * <p><b>写入是尽力而为的：</b>调用发生在业务事务提交之后，日志写入失败只记 ERROR，
 * 不会把已经成功的业务操作变成失败（否则调用方会误判并重试，产生重复数据）。</p>
 *
 * <p>按当前约定，{@code operator_id} 与 {@code ip_address} 不采集，落库为 NULL；
 * 操作人只记 {@code operator_name}（取自 {@value #OPERATOR_HEADER} 请求头）。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProcurementOperationLogRecorder {

    /**
     * 操作人邮箱请求头。前端 {@code lib/scm-axios.ts} 统一透传当前登录用户邮箱，
     * 后端在 Controller 上用 {@code @RequestHeader} 取值后写入 operator_name。
     */
    public static final String OPERATOR_HEADER = "X-User-Email";

    /** data_name 为 NOT NULL，名称缺失时的兜底值。 */
    private static final String UNKNOWN_DATA_NAME = "-";

    private static final int MAX_DATA_NAME_LENGTH = 255;
    private static final int MAX_OPERATOR_NAME_LENGTH = 50;
    private static final int MAX_OPERATION_DESC_LENGTH = 200;
    /** operation_details 为 text，留出余量避免超长被严格模式拒绝。 */
    private static final int MAX_OPERATION_DETAILS_LENGTH = 60000;

    private final ProcurementOperationLogMapper mapper;
    private final ObjectMapper objectMapper;

    /** 不记录操作详情。 */
    public void record(
            ProcurementBusinessType businessType,
            Long dataId,
            String dataName,
            ProcurementOperationType operationType,
            String operatorName) {
        record(businessType, dataId, dataName, operationType, operatorName, null);
    }

    /**
     * 记录一条操作日志。
     *
     * @param businessType 业务类型（合同 / 公司信息 / 合同模板）
     * @param dataId       业务数据主键
     * @param dataName     业务数据名称快照，写入 data_name（超长自动截断）
     * @param operationType 操作类型
     * @param operatorName 操作人（邮箱），写入 operator_name；为空则记 NULL
     * @param details      操作详情，序列化为 JSON 写入 operation_details，可为 null
     */
    public void record(
            ProcurementBusinessType businessType,
            Long dataId,
            String dataName,
            ProcurementOperationType operationType,
            String operatorName,
            Map<String, Object> details) {
        if (businessType == null || dataId == null || operationType == null) {
            log.warn("操作日志缺少必要字段，已跳过：businessType={}, dataId={}, operationType={}",
                    businessType, dataId, operationType);
            return;
        }

        ProcurementOperationLogPO logPO = new ProcurementOperationLogPO();
        logPO.setBusinessType(businessType.getCode());
        logPO.setDataId(dataId);
        logPO.setDataName(withPlaceholder(dataName));
        logPO.setOperatorName(truncate(operatorName, MAX_OPERATOR_NAME_LENGTH));
        logPO.setOperationType(operationType.getCode());
        logPO.setOperationDesc(truncate(operationType.getLabel() + "：" + logPO.getDataName(),
                MAX_OPERATION_DESC_LENGTH));
        logPO.setOperationDetails(toJson(details));

        try {
            mapper.insert(logPO);
        } catch (RuntimeException exception) {
            log.error("写入供应链操作日志失败（业务已提交，不影响本次操作）：businessType={}, dataId={}, operationType={}",
                    businessType.getCode(), dataId, operationType.getCode(), exception);
        }
    }

    /**
     * 便捷构造操作详情：按 {@code key, value, key, value...} 成对传入，
     * 值为 null 的条目自动跳过（{@link Map#of} 不接受 null，业务字段又常为空）。
     *
     * @return 详情 Map；无任何有效条目时返回 null（不写 operation_details）
     */
    public static Map<String, Object> details(Object... keysAndValues) {
        if (keysAndValues == null || keysAndValues.length == 0) {
            return null;
        }
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("操作详情必须按 key/value 成对传入");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value != null) {
                details.put(String.valueOf(keysAndValues[i]), value);
            }
        }
        return details.isEmpty() ? null : details;
    }

    private String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(details), MAX_OPERATION_DETAILS_LENGTH);
        } catch (JsonProcessingException exception) {
            log.warn("操作详情序列化失败，本条日志不记录详情：{}", details, exception);
            return null;
        }
    }

    /** data_name 为 NOT NULL，名称缺失时用占位符兜底。 */
    private static String withPlaceholder(String value) {
        String name = truncate(value, MAX_DATA_NAME_LENGTH);
        return name == null ? UNKNOWN_DATA_NAME : name;
    }

    private static String truncate(String value, int maxLength) {
        String text = blankToNull(value);
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
