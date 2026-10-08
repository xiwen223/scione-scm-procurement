package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.ContractAutoCreateService;
import com.scione.scm.bill.application.PoSyncAppService;
import com.scione.scm.bill.application.ContractCreateProgressTracker;
import com.scione.scm.bill.application.ContractLingxingSyncService;
import com.scione.scm.bill.application.ContractSignAppService;
import com.scione.scm.bill.application.ContractUpdateService;
import com.scione.scm.bill.application.dto.ContractCancelRequest;
import com.scione.scm.bill.application.dto.ContractCreateRequest;
import com.scione.scm.bill.application.dto.ContractCreateResponse;
import com.scione.scm.bill.application.dto.ContractBatchDownloadRequest;
import com.scione.scm.bill.application.dto.ContractDetailResponse;
import com.scione.scm.bill.application.dto.ContractItemUpdateRequest;
import com.scione.scm.bill.application.dto.ContractLingxingSyncDTO;
import com.scione.scm.bill.application.dto.ContractListItemResponse;
import com.scione.scm.bill.application.dto.ContractListQueryRequest;
import com.scione.scm.bill.application.dto.ContractUpdateRequest;
import com.scione.scm.bill.application.dto.ContractUpdateResponse;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import com.scione.scm.bill.application.ContractQueryService;
import com.scione.scm.bill.application.ContractQueryService.PageResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/**
 * 合同接口
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
    private final PoSyncAppService poSyncAppService;
    private final ContractUpdateService contractUpdateService;
    private final ContractSignAppService contractSignAppService;
    private final ContractLingxingSyncService contractLingxingSyncService;
    private final ContractCreateProgressTracker contractCreateProgressTracker;

    @PostMapping("/auto-create/trigger")
    @Operation(summary = "手动触发合同自动创建",
            description = "扫描 po_status=1 且 has_contract=0 的 PO，自动生成合同")
    public ApiResponse<PoSyncAppService.SyncResult> triggerAutoCreate() {
        // 与 5 分钟原生调度使用同一条链路：先从领星拉取，再仅处理本次状态=1 的 PO。
        return ApiResponse.success(poSyncAppService.pullAndSync());
    }

    /** 手动创建前检查 PO 状态；非待下单只提示，用户仍可继续创建。 */
    @GetMapping("/manual/po-status")
    @Operation(summary = "查询手动创建采购单状态")
    public ApiResponse<ContractAutoCreateService.ManualPoStatus> checkManualPoStatus(
            @RequestParam("purchaseOrderNo") String purchaseOrderNo) {
        try {
            return ApiResponse.success(contractAutoCreateService.checkManualPoStatus(purchaseOrderNo));
        } catch (BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    /**
     * 手动创建弹窗的领星预填。用户填完采购单号后调用，把领星侧的供方档案、结算约定、
     * 默认收款账户和交货日期回填到表单，避免手工录入十余个字段。
     */
    @GetMapping("/manual/po-prefill")
    @Operation(summary = "查询手动创建领星预填数据")
    public ApiResponse<ContractAutoCreateService.ManualPoPrefill> loadManualPoPrefill(
            @RequestParam("purchaseOrderNo") String purchaseOrderNo) {
        try {
            return ApiResponse.success(contractAutoCreateService.loadManualPoPrefill(purchaseOrderNo));
        } catch (BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("手动创建领星预填失败：purchaseOrderNo={}", purchaseOrderNo, ex);
            return ApiResponse.fail(ResultCode.SYSTEM_ERROR.getCode(), ResultCode.SYSTEM_ERROR.getMessage());
        }
    }

    @PostMapping("/create")
    @Operation(summary = "手动创建合同",
            description = "指定采购单号创建合同，用于测试或补建合同；可选 progressKey 用于配合 /create/progress 展示创建步骤")
    public ApiResponse<ContractCreateResponse> createContract(
            @RequestBody @Validated ContractCreateRequest request,
            @RequestHeader("X-User-Email") String userEmail,
            @RequestParam(value = "progressKey", required = false) String progressKey) {

        log.info("手动创建合同请求：purchaseOrderNo={}", request.getPurchaseOrderNo());

        try {
            ContractCreateResponse response = contractAutoCreateService.createContract(request, userEmail, progressKey);
            return ApiResponse.success(response);

        } catch (BusinessException ex) {
            log.warn("手动创建合同校验未通过：purchaseOrderNo={}, reason={}",
                    request.getPurchaseOrderNo(), ex.getMessage());
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("手动创建合同发生未处理异常：purchaseOrderNo={}", request.getPurchaseOrderNo(), ex);
            return ApiResponse.fail(ResultCode.SYSTEM_ERROR.getCode(), ResultCode.SYSTEM_ERROR.getMessage());
        }
    }

    /**
     * 手动创建合同的进度查询。创建本身是同步长请求，前端拿不到中间状态，
     * 因此由前端在发起创建时生成 progressKey，并行轮询这里显示「第几步 / 在做什么」。
     * 查询不到（key 过期、后端多实例、创建已结束）时 data 为 null，前端退回通用文案，不影响创建。
     */
    @GetMapping("/create/progress")
    @Operation(summary = "查询手动创建合同进度", description = "配合 POST /create 的 progressKey 使用，仅用于前端展示步骤小字")
    public ApiResponse<ContractCreateProgressTracker.Snapshot> createProgress(
            @RequestParam("progressKey") String progressKey) {
        return ApiResponse.success(contractCreateProgressTracker.find(progressKey).orElse(null));
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
    @Operation(summary = "修改合同", description = "修改合同信息（只更新传入的字段）；仅更新数据库，下载或发起签署时才生成最新合同文件")
    public ApiResponse<ContractUpdateResponse> updateContract(
            @PathVariable Long contractId,
            @RequestBody @Validated ContractUpdateRequest request,
            @RequestHeader("X-User-Email") String userEmail) {

        log.info("修改合同请求：contractId={}", contractId);

        try {
            ContractUpdateResponse response =
                    contractUpdateService.updateContract(contractId, request, userEmail);
            return ApiResponse.success(response);

        } catch (com.scione.scm.bill.common.BusinessException ex) {
            // 业务校验（编号占用、类型非法、状态不允许等）要把原因原样返回给用户
            log.warn("修改合同校验未通过：contractId={}, reason={}", contractId, ex.getMessage());
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());

        } catch (IllegalArgumentException | IllegalStateException ex) {
            log.warn("修改合同参数非法：contractId={}, reason={}", contractId, ex.getMessage());
            return ApiResponse.fail(ResultCode.PARAM_ERROR.getCode(), ex.getMessage());

        } catch (RuntimeException ex) {
            log.error("修改合同发生未处理异常：contractId={}", contractId, ex);
            return ApiResponse.fail(ResultCode.SYSTEM_ERROR.getCode(), ResultCode.SYSTEM_ERROR.getMessage());
        }
    }

    @PutMapping("/{contractId}/items/{itemId}")
    @Operation(summary = "修改合同明细", description = "仅创建状态合同可修改；修改后自动重算金额并只更新数据库，下载或发起签署时才生成最新合同文件")
    public ApiResponse<ContractUpdateResponse> updateContractItem(
            @PathVariable Long contractId,
            @PathVariable Long itemId,
            @RequestBody @Validated ContractItemUpdateRequest request,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            return ApiResponse.success(contractUpdateService.updateContractItem(contractId, itemId, request, userEmail));
        } catch (BusinessException ex) {
            // 业务校验（合同/明细不存在、状态不允许修改等）把原因原样返回给用户
            log.warn("修改合同明细校验未通过：contractId={}, itemId={}, reason={}", contractId, itemId, ex.getMessage());
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            log.warn("修改合同明细参数非法：contractId={}, itemId={}, reason={}", contractId, itemId, ex.getMessage());
            return ApiResponse.fail(ResultCode.PARAM_ERROR.getCode(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("修改合同明细发生未处理异常：contractId={}, itemId={}", contractId, itemId, ex);
            return ApiResponse.fail(ResultCode.SYSTEM_ERROR.getCode(), ResultCode.SYSTEM_ERROR.getMessage());
        }
    }

    @PostMapping("/{contractId}/lingxing-sync/compare")
    @Operation(summary = "查询合同与领星字段差异", description = "返回逐字段差异，前端决定哪些字段应用")
    public ApiResponse<ContractLingxingSyncDTO.CompareResponse> compareLingxingData(
            @PathVariable Long contractId) {
        try {
            return ApiResponse.success(contractLingxingSyncService.compare(contractId));
        } catch (BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/lingxing-sync/apply")
    @Operation(summary = "按选择字段同步领星数据", description = "仅更新 selectedFieldKeys 指定字段，不覆盖未选择字段")
    public ApiResponse<ContractLingxingSyncDTO.ApplyResponse> applyLingxingData(
            @PathVariable Long contractId,
            @RequestBody ContractLingxingSyncDTO.ApplyRequest request,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            return ApiResponse.success(contractLingxingSyncService.apply(contractId, request, userEmail));
        } catch (BusinessException ex) {
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
        } catch (BusinessException ex) {
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
        } catch (BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @GetMapping("/{contractId}/sign-task-status")
    @Operation(summary = "查询法大大签署任务状态", description = "返回法大大任务及参与方当前签署状态，用于联调")
    public ApiResponse<ContractSignAppService.SignTaskStatusResult> getSignTaskStatus(@PathVariable Long contractId) {
        try {
            return ApiResponse.success(contractSignAppService.getSignTaskStatus(contractId));
        } catch (BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @GetMapping("/{contractId}/abolish-task-status")
    @Operation(summary = "查询法大大解除协议任务状态", description = "查询履行中合同作废流程的解除协议任务及参与方状态")
    public ApiResponse<ContractSignAppService.SignTaskStatusResult> getAbolishTaskStatus(
            @PathVariable Long contractId) {
        try {
            return ApiResponse.success(contractSignAppService.getAbolishTaskStatus(contractId));
        } catch (BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/sync-sign-task-status")
    @Operation(summary = "同步法大大已完成任务状态", description = "回调未送达时，查询法大大任务；仅 task_finished 才更新合同为履行中")
    public ApiResponse<ContractSignAppService.SignTaskSyncResult> syncSignTaskStatus(
            @PathVariable Long contractId,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            return ApiResponse.success(contractSignAppService.syncFinishedSignTask(contractId, userEmail));
        } catch (BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/start-sign")
    @Operation(summary = "发起合同签署", description = "我方免验证自动盖章并短信通知供应商签署")
    public ApiResponse<ContractSignAppService.StartSignResult> startSign(
            @PathVariable Long contractId,
            @RequestParam(value = "forceConfirm", defaultValue = "false") boolean forceConfirm,
            @RequestHeader("X-User-Email") String userEmail) {
        try {
            return ApiResponse.success(contractSignAppService.startSign(contractId, userEmail, forceConfirm));
        } catch (BusinessException ex) {
            return ApiResponse.fail(ex.getResultCode().getCode(), ex.getMessage());
        }
    }

    @PostMapping("/{contractId}/cancel")
    @Operation(summary = "作废合同", description = "创建状态直接作废；签署中撤销法大大任务；履行中发起解除协议，待法大大作废回调后置为取消")
    public ApiResponse<Void> cancelContract(
            @PathVariable Long contractId,
            @RequestBody(required = false) @Validated ContractCancelRequest request,
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
