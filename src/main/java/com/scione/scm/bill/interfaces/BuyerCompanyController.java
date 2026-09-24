package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.BuyerCompanyApplicationService;
import com.scione.scm.bill.application.ProcurementOperationLogRecorder;
import com.scione.scm.bill.application.dto.BuyerCompanyDetailResponse;
import com.scione.scm.bill.application.dto.BuyerCompanyListItemResponse;
import com.scione.scm.bill.application.dto.BuyerCompanySealRequest;
import com.scione.scm.bill.application.dto.BuyerCompanyUpsertRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.scione.scm.bill.application.ProcurementOperationLogRecorder.OPERATOR_HEADER;
import static com.scione.scm.bill.application.ProcurementOperationLogRecorder.details;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementBusinessType.BUYER_COMPANY;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType.CREATE;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType.DELETE;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType.UPDATE;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType.UPDATE_SEAL;

@RestController
@Validated
@RequestMapping("/api/v1/buyer-companies")
@RequiredArgsConstructor
@Tag(name = "需方公司", description = "合同需方公司基础信息管理")
public class BuyerCompanyController {

    private final BuyerCompanyApplicationService service;
    private final ProcurementOperationLogRecorder operationLog;

    @GetMapping
    public ApiResponse<PageResult<BuyerCompanyListItemResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "false") boolean defaultOnly,
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize) {
        PageResult<BuyerCompanyListItemResponse> result = service.findPage(
                keyword,
                isActive,
                defaultOnly,
                pageNum,
                pageSize);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<BuyerCompanyDetailResponse> detail(@PathVariable @Min(1) Long id) {
        BuyerCompanyDetailResponse result = service.getById(id);
        return ApiResponse.success(result);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BuyerCompanyDetailResponse>> create(
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorEmail,
            @Valid @RequestBody BuyerCompanyUpsertRequest request) {
        BuyerCompanyDetailResponse result = service.create(request);
        operationLog.record(BUYER_COMPANY, Long.valueOf(result.id()), result.companyName(), CREATE, operatorEmail,
                companyDetails(result));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }

    @PutMapping("/{id}")
    public ApiResponse<BuyerCompanyDetailResponse> update(
            @PathVariable @Min(1) Long id,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorEmail,
            @Valid @RequestBody BuyerCompanyUpsertRequest request) {
        BuyerCompanyDetailResponse result = service.update(id, request);
        operationLog.record(BUYER_COMPANY, id, result.companyName(), UPDATE, operatorEmail, companyDetails(result));
        return ApiResponse.success(result);
    }

    @PutMapping("/{id}/seal")
    public ApiResponse<BuyerCompanyDetailResponse> updateSeal(
            @PathVariable @Min(1) Long id,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorEmail,
            @Valid @RequestBody BuyerCompanySealRequest request) {
        BuyerCompanyDetailResponse result = service.updateSeal(id, request);
        operationLog.record(BUYER_COMPANY, id, result.companyName(), UPDATE_SEAL, operatorEmail,
                details("sealName", result.sealName()));
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable @Min(1) Long id,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorEmail) {
        // 逻辑删除不会清掉名称，但日志要留名称快照，所以在删除前先读取一次
        String companyName = service.getById(id).companyName();
        service.delete(id);
        operationLog.record(BUYER_COMPANY, id, companyName, DELETE, operatorEmail);
        return ApiResponse.success(null);
    }

    /** 公司主档变更（新增 / 编辑）记录的业务关键字段。 */
    private static Map<String, Object> companyDetails(BuyerCompanyDetailResponse company) {
        return details(
                "companyName", company.companyName(),
                "creditCode", company.creditCode(),
                "openCorpId", company.openCorpId(),
                "identStatus", company.identStatus());
    }
}
