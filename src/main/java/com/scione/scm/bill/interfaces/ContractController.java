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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.net.URI;
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
    private final com.scione.scm.bill.application.PoSyncAppService poSyncAppService;
    private final com.scione.scm.bill.application.ContractUpdateService contractUpdateService;
    private final com.scione.scm.bill.application.ContractSignAppService contractSignAppService;
    private final com.scione.scm.bill.application.ContractLingxingSyncService contractLingxingSyncService;

    @PostMapping("/auto-create/trigger")
    @Operation(summary = "手动触发合同自动创建",
            description = "扫描 po_status=1 且 has_contract=0 的 PO，自动生成合同")
    public ApiResponse<com.scione.scm.bill.application.PoSyncAppService.SyncResult> triggerAutoCreate() {
        // 与 5 分钟原生调度使用同一条链路：先从领星拉取，再仅处理本次状态=1 的 PO。
        return ApiResponse.success(poSyncAppService.pullAndSync());
    }

    @PostMapping("/create")
    @Operation(summary = "手动创建合同",
            description = "指定采购单号创建合同，用于测试或补建合同")
    public ApiResponse<ContractCreateResponse> createContract(
            @RequestBody @Validated ContractCreateRequest request,
            @RequestHeader("X-User-Email") String userEmail) {

        log.info("手动创建合同请求：purchaseOrderNo={}", request.getPurchaseOrderNo());

        try {
            ContractCreateResponse response = contractAutoCreateService.createContract(request, userEmail);
            return ApiResponse.success(response);

        } catch (com.scione.scm.bill.common.BusinessException ex) {
            log.warn("手动创建合同校验未通过：purchaseOrderNo={}, reason={}",
                    request.getPurchaseOrderNo(), ex.getMessage());
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
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

        if ("signed".equals(type)) {
            String downloadUrl = contractQueryService.getFadadaSignedDownloadUrl(contractId);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(downloadUrl))
                    .build();
        }

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

    @PutMapping("/{contractId}")
    @Operation(summary = "修改合同", description = "修改合同信息（只更新传入的字段），修改后会重新生成合同文件")
    public ApiResponse<com.scione.scm.bill.application.dto.ContractUpdateResponse> updateContract(
            @PathVariable Long contractId,
            @RequestBody @Validated com.scione.scm.bill.application.dto.ContractUpdateRequest request,
            @RequestHeader("X-User-Email") String userEmail) {

        log.info("修改合同请求：contractId={}", contractId);

        try {
            com.scione.scm.bill.application.dto.ContractUpdateResponse response =
                    contractUpdateService.updateContract(contractId, request, userEmail);
            return ApiResponse.success(response);

        } catch (RuntimeException ex) {
            log.error("修改合同失败：contractId={}", contractId, ex);
            return ApiResponse.fail(500, ex.getMessage());
        }
    }

    @PutMapping("/{contractId}/items/{itemId}")
    @Operation(summary = "修改合同明细", description = "仅创建状态合同可修改；修改后自动重算原价、折扣后金额并重新生成合同文件")
    public ApiResponse<com.scione.scm.bill.application.dto.ContractUpdateResponse> updateContractItem(
            @PathVariable Long contractId,
            @PathVariable Long itemId,
            @RequestBody @Validated com.scione.scm.bill.application.dto.ContractItemUpdateRequest request,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            return ApiResponse.success(contractUpdateService.updateContractItem(contractId, itemId, request, userEmail));
        } catch (RuntimeException ex) {
            log.error("修改合同明细失败：contractId={}, itemId={}", contractId, itemId, ex);
            return ApiResponse.fail(500, ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/lingxing-sync/compare")
    @Operation(summary = "查询合同与领星字段差异", description = "返回逐字段差异，前端决定哪些字段应用")
    public ApiResponse<com.scione.scm.bill.application.dto.ContractLingxingSyncDTO.CompareResponse> compareLingxingData(
            @PathVariable Long contractId) {
        try {
            return ApiResponse.success(contractLingxingSyncService.compare(contractId));
        } catch (com.scione.scm.bill.common.BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/lingxing-sync/apply")
    @Operation(summary = "按选择字段同步领星数据", description = "仅更新 selectedFieldKeys 指定字段，不覆盖未选择字段")
    public ApiResponse<com.scione.scm.bill.application.dto.ContractLingxingSyncDTO.ApplyResponse> applyLingxingData(
            @PathVariable Long contractId,
            @RequestBody com.scione.scm.bill.application.dto.ContractLingxingSyncDTO.ApplyRequest request,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            return ApiResponse.success(contractLingxingSyncService.apply(contractId, request, userEmail));
        } catch (com.scione.scm.bill.common.BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/lingxing-sync/proceed-with-differences")
    @Operation(summary = "确认领星差异后继续签署", description = "仅记录当前比对结果的完整差异日志，不修改合同数据")
    public ApiResponse<Void> proceedWithLingxingDifferences(
            @PathVariable Long contractId,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            contractLingxingSyncService.recordProceedWithoutSync(contractId, userEmail);
            return ApiResponse.success(null);
        } catch (com.scione.scm.bill.common.BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/urge-sign")
    @Operation(summary = "催办签署", description = "仅签署中合同可催签，调用法大大催办接口")
    public ApiResponse<Void> urgeSign(@PathVariable Long contractId,
                                      @RequestHeader("X-User-Email") String userEmail) {
        try {
            contractSignAppService.urgeSign(contractId, userEmail);
            return ApiResponse.success(null);
        } catch (com.scione.scm.bill.common.BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @GetMapping("/{contractId}/sign-task-status")
    @Operation(summary = "查询法大大签署任务状态", description = "返回法大大任务及参与方当前签署状态，用于联调")
    public ApiResponse<com.scione.scm.bill.application.ContractSignAppService.SignTaskStatusResult> getSignTaskStatus(@PathVariable Long contractId) {
        try {
            return ApiResponse.success(contractSignAppService.getSignTaskStatus(contractId));
        } catch (com.scione.scm.bill.common.BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/sync-sign-task-status")
    @Operation(summary = "同步法大大已完成任务状态", description = "回调未送达时，查询法大大任务；仅 task_finished 才更新合同为履行中")
    public ApiResponse<com.scione.scm.bill.application.ContractSignAppService.SignTaskSyncResult> syncSignTaskStatus(
            @PathVariable Long contractId,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            return ApiResponse.success(contractSignAppService.syncFinishedSignTask(contractId, userEmail));
        } catch (com.scione.scm.bill.common.BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/start-sign")
    @Operation(summary = "发起合同签署", description = "我方免验证自动盖章并短信通知供应商签署")
    public ApiResponse<com.scione.scm.bill.application.ContractSignAppService.StartSignResult> startSign(
            @PathVariable Long contractId,
            @RequestParam(value = "forceConfirm", defaultValue = "false") boolean forceConfirm,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            return ApiResponse.success(contractSignAppService.startSign(contractId, userEmail, forceConfirm));
        } catch (com.scione.scm.bill.common.BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/cancel")
    @Operation(summary = "作废合同", description = "创建状态直接作废；签署中状态将先撤销法大大签署任务")
    public ApiResponse<Void> cancelContract(
            @PathVariable Long contractId,
            @RequestBody(required = false) @Validated com.scione.scm.bill.application.dto.ContractCancelRequest request,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            contractSignAppService.cancel(contractId, request, userEmail);
            return ApiResponse.success(null);
        } catch (com.scione.scm.bill.common.BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
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
