package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.ProcurementBusinessTypeResponse;
import com.scione.scm.bill.application.dto.ProcurementOperationLogListItemResponse;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.procurementlog.enums.ProcurementBusinessType;
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

    public List<ProcurementBusinessTypeResponse> businessTypes() {
        return Arrays.stream(ProcurementBusinessType.values())
                .map(type -> new ProcurementBusinessTypeResponse(type.getCode(), type.getName()))
                .toList();
    }

    public PageResult<ProcurementOperationLogListItemResponse> findPage(
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

    private ProcurementOperationLogListItemResponse toListItem(ProcurementOperationLogPO log) {
        String businessTypeText = ProcurementBusinessType.findByCode(log.getBusinessType())
                .map(ProcurementBusinessType::getName)
                .orElse("未知业务");
        return new ProcurementOperationLogListItemResponse(
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
