package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.ContractTemplateApplicationService;
import com.scione.scm.bill.application.ProcurementOperationLogRecorder;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.scione.scm.bill.application.ProcurementOperationLogRecorder.OPERATOR_HEADER;
import static com.scione.scm.bill.application.ProcurementOperationLogRecorder.details;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementBusinessType.CONTRACT_TEMPLATE;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType.CREATE;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType.DELETE;
import static com.scione.scm.bill.domain.procurementlog.enums.ProcurementOperationType.UPDATE;

@RestController
@Validated
@RequestMapping("/api/v1/contract-templates")
@RequiredArgsConstructor
@Tag(name = "合同模板", description = "合同模板管理")
public class ContractTemplateController {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ContractTemplateApplicationService service;
    private final ProcurementOperationLogRecorder operationLog;

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
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorEmail,
            @Valid @RequestBody ContractTemplateUpsertRequest request) {
        ContractTemplateDetailResponse result = service.create(request);
        operationLog.record(CONTRACT_TEMPLATE, Long.valueOf(result.id()), result.templateName(), CREATE,
                operatorEmail, templateDetails(result));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }

    @PostMapping("/update")
    public ApiResponse<ContractTemplateDetailResponse> update(
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorEmail,
            @Valid @RequestBody ContractTemplateUpdateRequest request) {
        ContractTemplateDetailResponse result = service.update(request.id(), request.toUpsertRequest());
        operationLog.record(CONTRACT_TEMPLATE, request.id(), result.templateName(), UPDATE,
                operatorEmail, templateDetails(result));
        return ApiResponse.success(result);
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorEmail,
            @Valid @RequestBody ContractTemplateIdRequest request) {
        // 逻辑删除不会清掉名称，但日志要留名称快照，所以在删除前先读取一次
        String templateName = service.getById(request.id()).templateName();
        service.delete(request.id());
        operationLog.record(CONTRACT_TEMPLATE, request.id(), templateName, DELETE, operatorEmail);
        return ApiResponse.success(null);
    }

    /** 模板变更（新增 / 编辑）记录的业务关键字段。 */
    private static Map<String, Object> templateDetails(ContractTemplateDetailResponse template) {
        return details(
                "templateName", template.templateName(),
                "contractType", template.contractType(),
                "objectKey", template.objectKey(),
                "isDefault", template.isDefault(),
                "isActive", template.isActive());
    }
}
