package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.ProcurementOperationLogApplicationService;
import com.scione.scm.bill.application.dto.ProcurementBusinessTypeResponse;
import com.scione.scm.bill.application.dto.ProcurementOperationLogListItemResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/procurement-operation-logs")
@RequiredArgsConstructor
@Tag(name = "供应链操作日志", description = "供应链合同、公司信息及合同模板操作日志查询")
public class ProcurementOperationLogController {

    private final ProcurementOperationLogApplicationService service;

    @GetMapping
    public ApiResponse<PageResult<ProcurementOperationLogListItemResponse>> page(
            @RequestParam(required = false) String dataName,
            @RequestParam(required = false) Integer businessType,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize) {
        return ApiResponse.success(service.findPage(
                dataName,
                businessType,
                operationType,
                operator,
                startDate,
                endDate,
                pageNum,
                pageSize));
    }

    @GetMapping("/business-types")
    public ApiResponse<List<ProcurementBusinessTypeResponse>> businessTypes() {
        return ApiResponse.success(service.businessTypes());
    }
}
