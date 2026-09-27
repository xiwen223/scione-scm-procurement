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

import static com.scione.scm.bill.application.ProcurementOperationLogRecorder.FieldChange.of;
import static com.scione.scm.bill.application.ProcurementOperationLogRecorder.OPERATOR_HEADER;
import static com.scione.scm.bill.application.ProcurementOperationLogRecorder.changes;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementBusinessType.BUYER_COMPANY;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType.CREATE;
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
        operationLog.record(BUYER_COMPANY, Long.valueOf(result.id()), result.companyName(), CREATE, operatorEmail);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }

    @PutMapping("/{id}")
    public ApiResponse<BuyerCompanyDetailResponse> update(
            @PathVariable @Min(1) Long id,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorEmail,
            @Valid @RequestBody BuyerCompanyUpsertRequest request) {
        // 变更前快照：日志只记录本次真正变化的字段，需要拿到修改前的值（与模板删除的取快照方式一致）
        BuyerCompanyDetailResponse before = service.getById(id);
        BuyerCompanyDetailResponse result = service.update(id, request);
        operationLog.record(BUYER_COMPANY, id, result.companyName(), UPDATE, operatorEmail,
                companyChanges(before, result));
        return ApiResponse.success(result);
    }

    @PutMapping("/{id}/seal")
    public ApiResponse<BuyerCompanyDetailResponse> updateSeal(
            @PathVariable @Min(1) Long id,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorEmail,
            @Valid @RequestBody BuyerCompanySealRequest request) {
        BuyerCompanyDetailResponse result = service.updateSeal(id, request);
        operationLog.record(BUYER_COMPANY, id, result.companyName(), UPDATE_SEAL, operatorEmail);
        return ApiResponse.success(result);
    }

    /**
     * 编辑公司的变更详情：只记录本次真正变化的字段及其修改前后值。
     *
     * <p>签章相关字段（sealName / sealUrl / sealBase64 / fadadaSealId / sealFlowStatus）由签章维护接口负责，
     * 不在这里记录 —— 其中 sealBase64 还可能很长，落进日志没有检索价值。</p>
     */
    private static Map<String, Object> companyChanges(BuyerCompanyDetailResponse before, BuyerCompanyDetailResponse after) {
        return changes(
                of("companyName", before.companyName(), after.companyName()),
                of("companyShortName", before.companyShortName(), after.companyShortName()),
                of("creditCode", before.creditCode(), after.creditCode()),
                of("postCode", before.postCode(), after.postCode()),
                of("fax", before.fax(), after.fax()),
                of("legalPerson", before.legalPerson(), after.legalPerson()),
                of("address", before.address(), after.address()),
                of("phone", before.phone(), after.phone()),
                of("bankName", before.bankName(), after.bankName()),
                of("bankAccount", before.bankAccount(), after.bankAccount()),
                of("openCorpId", before.openCorpId(), after.openCorpId()),
                of("identStatus", before.identStatus(), after.identStatus()),
                of("isDefault", before.isDefault(), after.isDefault()),
                of("isActive", before.isActive(), after.isActive()));
    }
}
