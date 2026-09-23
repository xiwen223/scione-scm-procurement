package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.ContractTemplateApplicationService;
import com.scione.scm.bill.application.dto.ContractTemplateDetailResponse;
import com.scione.scm.bill.application.dto.ContractTemplateIdRequest;
import com.scione.scm.bill.application.dto.ContractTemplateListItemResponse;
import com.scione.scm.bill.application.dto.ContractTemplatePageRequest;
import com.scione.scm.bill.application.dto.ContractTemplateUpdateRequest;
import com.scione.scm.bill.application.dto.ContractTemplateUpsertRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/contract-templates")
@RequiredArgsConstructor
@Tag(name = "合同模板", description = "合同模板管理")
public class ContractTemplateController {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ContractTemplateApplicationService service;

    @PostMapping("/page")
    public ApiResponse<PageResult<ContractTemplateListItemResponse>> page(
            @Valid @RequestBody ContractTemplatePageRequest request) {
        PageResult<ContractTemplateListItemResponse> result = service.findPage(
                request.keyword(),
                request.contractType(),
                request.isActive(),
                Boolean.TRUE.equals(request.defaultOnly()),
                request.pageNum() == null ? DEFAULT_PAGE_NUM : request.pageNum(),
                request.pageSize() == null ? DEFAULT_PAGE_SIZE : request.pageSize());
        return ApiResponse.success(result);
    }

    @PostMapping("/detail")
    public ApiResponse<ContractTemplateDetailResponse> detail(
            @Valid @RequestBody ContractTemplateIdRequest request) {
        ContractTemplateDetailResponse result = service.getById(request.id());
        return ApiResponse.success(result);
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<ContractTemplateDetailResponse>> create(
            @Valid @RequestBody ContractTemplateUpsertRequest request) {
        ContractTemplateDetailResponse result = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }

    @PostMapping("/update")
    public ApiResponse<ContractTemplateDetailResponse> update(
            @Valid @RequestBody ContractTemplateUpdateRequest request) {
        ContractTemplateDetailResponse result = service.update(request.id(), request.toUpsertRequest());
        return ApiResponse.success(result);
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(@Valid @RequestBody ContractTemplateIdRequest request) {
        service.delete(request.id());
        return ApiResponse.success(null);
    }
}
