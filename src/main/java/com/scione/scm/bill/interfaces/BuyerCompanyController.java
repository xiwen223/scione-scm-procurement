package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.BuyerCompanyApplicationService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/buyer-companies")
@RequiredArgsConstructor
@Tag(name = "需方公司", description = "合同需方公司基础信息管理")
public class BuyerCompanyController {

    private final BuyerCompanyApplicationService service;

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
            @Valid @RequestBody BuyerCompanyUpsertRequest request) {
        BuyerCompanyDetailResponse result = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }

    @PutMapping("/{id}")
    public ApiResponse<BuyerCompanyDetailResponse> update(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody BuyerCompanyUpsertRequest request) {
        BuyerCompanyDetailResponse result = service.update(id, request);
        return ApiResponse.success(result);
    }

    @PutMapping("/{id}/seal")
    public ApiResponse<BuyerCompanyDetailResponse> updateSeal(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody BuyerCompanySealRequest request) {
        BuyerCompanyDetailResponse result = service.updateSeal(id, request);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @Min(1) Long id) {
        service.delete(id);
        return ApiResponse.success(null);
    }
}
