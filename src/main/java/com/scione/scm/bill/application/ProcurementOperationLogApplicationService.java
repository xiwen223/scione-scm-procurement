package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.vo.ProcurementBusinessTypeVO;
import com.scione.scm.bill.application.vo.ProcurementOperationLogListItemVO;
import com.scione.scm.bill.application.vo.ProcurementOperationTypeVO;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.procurementlog.enums.ProcurementBusinessType;
import com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ProcurementOperationLogMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ProcurementOperationLogPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProcurementOperationLogApplicationService {

    private final ProcurementOperationLogMapper mapper;

    public List<ProcurementBusinessTypeVO> businessTypes() {
        return Arrays.stream(ProcurementBusinessType.values())
                .map(type -> new ProcurementBusinessTypeVO(type.getCode(), type.getName()))
                .toList();
    }

    /** 操作类型下拉选项：前端筛选下拉与列表「操作类型」列的展示共用这一份数据。 */
    public List<ProcurementOperationTypeVO> operationTypes() {
        return Arrays.stream(ProcurementOperationType.values())
                .map(type -> new ProcurementOperationTypeVO(type.getCode(), type.getLabel()))
                .toList();
    }

    public PageResult<ProcurementOperationLogListItemVO> findPage(
            String dataName,
            Integer businessType,
            String operationType,
            String operator,
            LocalDate startDate,
            LocalDate endDate,
            int pageNum,
            int pageSize) {
        validateBusinessType(businessType);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "操作开始日期不能晚于结束日期");
        }

        String normalizedDataName = blankToNull(dataName);
        String normalizedOperationType = blankToNull(operationType);
        // 兼容旧页面传入的合同修改类型；新页面统一选择“业务类型=合同、操作类型=修改”。
        if ("MODIFY".equals(normalizedOperationType)) normalizedOperationType = "UPDATE";
        String normalizedOperator = blankToNull(operator);
        LocalDateTime startTime = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endTime = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        long total = mapper.count(
                normalizedDataName,
                businessType,
                normalizedOperationType,
                normalizedOperator,
                startTime,
                endTime);
        int offset = (pageNum - 1) * pageSize;

        return PageResult.of(
                pageNum,
                pageSize,
                total,
                mapper.findPage(
                                normalizedDataName,
                                businessType,
                                normalizedOperationType,
                                normalizedOperator,
                                startTime,
                                endTime,
                                offset,
                                pageSize)
                        .stream()
                        .map(this::toListItem)
                        .toList());
    }

    private ProcurementOperationLogListItemVO toListItem(ProcurementOperationLogPO log) {
        String businessTypeText = ProcurementBusinessType.findByCode(log.getBusinessType())
                .map(ProcurementBusinessType::getName)
                .orElse("未知业务");
        return new ProcurementOperationLogListItemVO(
                log.getId(),
                log.getBusinessType(),
                businessTypeText,
                log.getDataName(),
                log.getOperatorId(),
                log.getOperatorName(),
                log.getOperationType(),
                log.getOperationDesc(),
                log.getOperationDetails(),
                log.getIpAddress(),
                log.getCreateTime());
    }

    private static void validateBusinessType(Integer businessType) {
        if (businessType != null && ProcurementBusinessType.findByCode(businessType).isEmpty()) {
            throw new BusinessException(ResultCode.PROCUREMENT_OPERATION_LOG_BUSINESS_TYPE_INVALID,
                    "不支持的业务类型：" + businessType);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
