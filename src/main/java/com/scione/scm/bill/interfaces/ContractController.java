package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.ContractAutoCreateService;
import com.scione.scm.bill.application.ContractAutoCreateService.AutoCreateResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.scione.scm.bill.application.ContractQueryService;
import com.scione.scm.bill.application.ContractQueryService.PageResult;
import com.scione.scm.bill.application.dto.ContractDetailResponse;
import com.scione.scm.bill.application.dto.ContractListItemResponse;
import com.scione.scm.bill.application.dto.ContractListQueryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.scione.scm.bill.application.dto.ContractCreateRequest;
import com.scione.scm.bill.application.dto.ContractCreateResponse;
import lombok.extern.slf4j.Slf4j;
import com.scione.scm.bill.application.dto.ContractBatchDownloadRequest;
/**
 * 合同接口（手动触发合同自动创建，联调用；生产由 XXL-Job 定时触发）。
 */
@Slf4j
@RestController
@Validated
@RequestMapping("/api/v1/contracts")
@RequiredArgsConstructor
@Tag(name = "合同管理", description = "合同自动创建手动触发")
public class ContractController {
    private final ContractQueryService contractQueryService;
    private final ContractAutoCreateService contractAutoCreateService;

    @PostMapping("/auto-create/trigger")
    @Operation(summary = "手动触发合同自动创建",
            description = "扫描 po_status=1 且 has_contract=0 的 PO，自动生成合同")
    public ApiResponse<AutoCreateResult> triggerAutoCreate() {
        AutoCreateResult result = contractAutoCreateService.autoCreate(null);  // null = 全表扫描
        return ApiResponse.success(result);
    }

    @PostMapping("/create")
    @Operation(summary = "手动创建合同",
            description = "指定采购单号创建合同，用于测试或补建合同")
    public ApiResponse<ContractCreateResponse> createContract(
            @RequestBody @Validated ContractCreateRequest request) {

        log.info("手动创建合同请求：purchaseOrderNo={}", request.getPurchaseOrderNo());

        try {
            ContractCreateResponse response = contractAutoCreateService.createContract(request);
            return ApiResponse.success(response);

        } catch (RuntimeException ex) {
            log.error("手动创建合同失败：purchaseOrderNo={}", request.getPurchaseOrderNo(), ex);
            return ApiResponse.fail(500, ex.getMessage());
        }
    }

    @GetMapping
    @Operation(summary = "分页查询合同列表", description = "支持按合同号、采购单号、供应商名称、状态、日期范围筛选")
    public ResponseEntity<PageResult<ContractListItemResponse>> queryContractList(
            @ModelAttribute ContractListQueryRequest request) {
        PageResult<ContractListItemResponse> result = contractQueryService.queryContractList(request);
        return ResponseEntity.ok(result);
    }
    @GetMapping("/{contractId}")
    @Operation(summary = "查询合同详情", description = "查看合同完整信息，包含明细")
    public ResponseEntity<ContractDetailResponse> getContractDetail(
            @PathVariable Long contractId) {
        ContractDetailResponse response = contractQueryService.getContractDetail(contractId);
        return ResponseEntity.ok(response);
    }
    @GetMapping("/{contractId}/download")
    @Operation(summary = "下载合同文件", description = "type=original下载原始合同（Excel），type=signed下载已签署合同（PDF）")
    public ResponseEntity<byte[]> downloadContractPdf(
            @PathVariable Long contractId,
            @RequestParam(value = "type", defaultValue = "signed") String type) throws java.io.IOException {

        // 下载文件（返回结果包含文件字节和文件名）
        ContractQueryService.DownloadResult result = contractQueryService.downloadContractFile(contractId, type);

        // 根据文件扩展名动态设置 Content-Type
        MediaType contentType = getContentType(result.fileName());

        // 设置响应头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.setContentDispositionFormData("attachment", result.fileName());
        headers.setContentLength(result.fileBytes().length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(result.fileBytes());
    }

    @PostMapping("/download-batch")
    @Operation(summary = "批量下载合同", description = "批量下载合同文件，返回ZIP压缩包")
    public ResponseEntity<byte[]> downloadContractBatch(
            @RequestBody @Validated ContractBatchDownloadRequest request) throws java.io.IOException {

        log.info("批量下载合同请求: contractIds={}", request.contractIds());

        // 调用服务层批量下载
        byte[] zipBytes = contractQueryService.downloadContractBatch(request.contractIds());

        // 设置响应头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", "contracts_" + System.currentTimeMillis() + ".zip");
        headers.setContentLength(zipBytes.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(zipBytes);
    }

    /**
     * 根据文件名获取 Content-Type。
     */
    private MediaType getContentType(String fileName) {
        if (fileName.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        } else if (fileName.endsWith(".xlsx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } else if (fileName.endsWith(".xls")) {
            return MediaType.parseMediaType("application/vnd.ms-excel");
        } else {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}