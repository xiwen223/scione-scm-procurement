package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.*;
import com.scione.scm.bill.application.port.ContractFileStore;
import com.scione.scm.bill.application.port.ContractPdfConverter;
import com.scione.scm.bill.application.port.ContractTemplateService;
import com.scione.scm.bill.application.port.LingxingProductClient;
import com.scione.scm.bill.config.FadadaOpenApiProperties;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.domain.contract.ContractPage;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.ContractStatus;
import com.scione.scm.bill.infrastructure.fadada.FadadaOpenApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 合同查询应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractQueryService {

    private static final HttpClient DOWNLOAD_HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final ContractRepository contractRepository;
    private final ContractFileStore contractFileStore;
    private final ContractTemplateService contractTemplateService;
    private final ContractPdfConverter contractPdfConverter;
    private final FadadaOpenApiClient fadadaOpenApiClient;
    private final FadadaOpenApiProperties fadadaOpenApiProperties;
    private final LingxingProductClient lingxingProductClient;

    /**
     * 分页查询合同列表。
     */
    public PageResult<ContractListItemResponse> queryContractList(ContractListQueryRequest request) {
        log.info("查询合同列表：contractNo={}, purchaseOrderNo={}, supplierName={}, status={}, pageNum={}, pageSize={}",
                request.getContractNo(), request.getPurchaseOrderNo(), request.getSupplierName(),
                request.getStatus(), request.getPageNum(), request.getPageSize());

        // 校验并修正分页参数
        request.validateAndFix();

        // 查询数据库
        ContractPage page = contractRepository.findByPage(request);

        // Domain 转 DTO
        List<ContractListItemResponse> items = page.records().stream()
                .map(ContractListItemResponse::from)
                .collect(Collectors.toList());

        log.info("查询合同列表成功：total={}, records={}", page.total(), items.size());
        return new PageResult<>(page.total(), items);
    }

    /**
     * 查询合同详情。
     */
    public ContractDetailResponse getContractDetail(Long contractId) {
        log.info("查询合同详情：contractId={}", contractId);

        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new RuntimeException("合同不存在：contractId=" + contractId));

        fillMissingItemImages(contract);
        ContractDetailResponse response = ContractDetailResponse.from(contract);

        log.info("查询合同详情成功：contractNo={}", contract.getContractNo());
        return response;
    }

    /**
     * 新合同创建时会将领星商品图片保存到 contract_item。历史合同可能没有保存图片，
     * 详情查询时按同一 SKU 查询逻辑补齐展示数据，不修改合同明细本身。
     */
    private void fillMissingItemImages(Contract contract) {
        if (contract.getItems() == null || contract.getItems().isEmpty()) {
            return;
        }
        int queried = 0;
        int filled = 0;
        for (ContractItem item : contract.getItems()) {
            if (StringUtils.hasText(item.getPicUrl()) || !StringUtils.hasText(item.getSku())) {
                continue;
            }
            queried++;
            try {
                var cachedPicUrl = contractRepository.findLatestItemPicUrlBySku(item.getSku());
                if (cachedPicUrl.isPresent()) {
                    item.setPicUrl(cachedPicUrl.get());
                    filled++;
                    continue;
                }
                var product = lingxingProductClient.findBySku(item.getSku());
                if (product.isPresent() && StringUtils.hasText(product.get().picUrl())) {
                    item.setPicUrl(product.get().picUrl());
                    filled++;
                }
            } catch (Exception e) {
                // 图片展示失败不影响合同详情主流程；下次查询仍会尝试补齐。
                log.warn("合同明细图片查询失败：contractNo={}, sku={}",
                        contract.getContractNo(), item.getSku(), e);
            }
        }
        if (queried > 0) {
            log.info("合同详情明细图片补齐完成：contractNo={}, queried={}, filled={}",
                    contract.getContractNo(), queried, filled);
        }
    }

    /**
     * 下载合同文件。
     *
     * @param contractId 合同 ID
     * @param type       下载类型：original-原始合同 signed-已签署合同
     * @return 下载结果（包含文件字节和文件名）
     */
    public DownloadResult downloadContractFile(Long contractId, String type) throws IOException {
        log.info("下载合同文件：contractId={}, type={}", contractId, type);

        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new RuntimeException("合同不存在：contractId=" + contractId));

        // 仅创建状态允许编辑，因此只对创建状态按当前数据库数据即时生成。
        // 签署中及之后直接读取已保存文件，避免下载时重复模板填充、PDF 转换及存储上传。
        if (!"signed".equals(type)) {
            if (contract.getStatus() == ContractStatus.CREATED) {
                byte[] latestPdf = generateLatestContractPdf(contract, "下载创建状态合同");
                return new DownloadResult(latestPdf, contract.getContractNo() + ".pdf");
            }
            String fileUrl = getOriginalPdfUrl(contract);
            if (!StringUtils.hasText(fileUrl)) {
                throw new RuntimeException("合同文件不存在，无法下载：contractId=" + contractId);
            }
            byte[] fileBytes = contractFileStore.download(fileUrl);
            log.info("下载非创建状态合同，直接使用已保存文件：contractNo={}, status={}, fileUrl={}",
                    contract.getContractNo(), contract.getStatus(), fileUrl);
            return new DownloadResult(fileBytes, extractFileName(fileUrl, contract.getContractNo(), "original"));
        }

        String fileUrl = getSignedPdfUrl(contract);

        if (fileUrl == null || fileUrl.isEmpty()) {
            throw new RuntimeException("合同文件不存在：type=" + type);
        }

        // 从 S3 下载文件
        byte[] fileBytes = contractFileStore.download(fileUrl);

        // 从 URL 提取文件名
        String fileName = extractFileName(fileUrl, contract.getContractNo(), type);

        log.info("下载合同文件成功：contractNo={}, fileName={}, fileSize={}",
                contract.getContractNo(), fileName, fileBytes.length);

        return new DownloadResult(fileBytes, fileName);
    }

    private byte[] generateLatestContractPdf(Contract contract, String scene) throws IOException {
        try {
            log.info("{}：开始按当前合同数据生成文件：contractNo={}, 原价={}, 折扣={}, 实际金额={}",
                    scene, contract.getContractNo(), contract.getOriginalAmount(),
                    contract.getDiscountedAmount(), contract.getContractAmount());
            byte[] pdfBytes = contractPdfConverter.convert(contractTemplateService.fillTemplate(contract),
                    contract.getContractNo());
            String fileUrl = contractFileStore.store(contract.getContractNo(), pdfBytes, "pdf");
            contractRepository.updatePdfUrl(contract.getId(), fileUrl);
            contract.setContractPdfUrl(fileUrl);
            log.info("{}：最新合同文件已生成：contractNo={}, fileUrl={}, bytes={}",
                    scene, contract.getContractNo(), fileUrl, pdfBytes.length);
            return pdfBytes;
        } catch (Exception ex) {
            log.error("{}：生成最新合同文件失败：contractNo={}", scene, contract.getContractNo(), ex);
            throw new IOException("生成最新合同文件失败", ex);
        }
    }

    /**
     * 从 URL 提取文件名。
     */
    private String extractFileName(String fileUrl, String contractNo, String type) {
        // 从 URL 中提取文件名（最后一个 / 后面的部分）
        int lastSlash = fileUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < fileUrl.length() - 1) {
            return fileUrl.substring(lastSlash + 1);
        }

        // 如果提取失败，根据类型生成默认文件名
        String extension = "signed".equals(type) ? ".pdf" : ".xlsx";
        return contractNo + extension;
    }

    private String getOriginalPdfUrl(Contract contract) {
        return contract.getContractPdfUrl();  // ← 直接从领域对象获取
    }

    private String getSignedPdfUrl(Contract contract) {
        return contract.getSignedPdfUrl();    // ← 直接从领域对象获取
    }

    /**
     * 分页结果封装。
     */
    /** 获取法大大已签署合同的短期下载地址；该 URL 不落库。 */
    public String getFadadaSignedDownloadUrl(Long contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new RuntimeException("合同不存在：contractId=" + contractId));
        if (!usesFadadaDocument(contract.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "创建状态合同应下载未盖章原合同");
        }
        return getFadadaDocumentDownloadUrl(contract);
    }

    private String getFadadaDocumentDownloadUrl(Contract contract) {
        if (contract.getFadadaTaskId() == null || contract.getFadadaTaskId().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "合同未关联法大大签署任务");
        }
        String ownerOpenCorpId = fadadaOpenApiProperties.getOpenCorpId();
        if (ownerOpenCorpId == null || ownerOpenCorpId.isBlank()) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "法大大发起企业 openCorpId 未配置");
        }
        boolean signing = contract.getStatus() == ContractStatus.SIGNING;
        String downloadUrl = fadadaOpenApiClient.getSignTaskDownloadUrl(
                new FadadaOpenApiClient.DownloadUrlRequest("corp", ownerOpenCorpId, contract.getFadadaTaskId(),
                        contract.getContractNo() + (signing ? "-签署中合同.pdf" : "-已签署合同.pdf"),
                        false, "download"));
        log.info("已获取法大大合同下载地址：contractId={}, contractNo={}, status={}, signTaskId={}, documentStage={}",
                contract.getId(), contract.getContractNo(), contract.getStatus(), contract.getFadadaTaskId(),
                signing ? "我方已盖章、供方待签" : "双方已盖章");
        return downloadUrl;
    }

    private boolean usesFadadaDocument(ContractStatus status) {
        return status == ContractStatus.SIGNING
                || status == ContractStatus.EXECUTING
                || status == ContractStatus.COMPLETED;
    }

    private byte[] downloadFadadaDocument(Contract contract) throws IOException {
        String downloadUrl = getFadadaDocumentDownloadUrl(contract);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<byte[]> response = DOWNLOAD_HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length == 0) {
                throw new IOException("法大大文件下载失败，HTTP状态=" + response.statusCode());
            }
            log.info("法大大合同文件下载成功：contractNo={}, status={}, bytes={}",
                    contract.getContractNo(), contract.getStatus(), response.body().length);
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("法大大文件下载被中断", ex);
        } catch (IllegalArgumentException ex) {
            throw new IOException("法大大文件下载地址无效", ex);
        }
    }

    public record PageResult<T>(long total, List<T> records) {
    }

    /**
     * 下载结果封装。
     */
    public record DownloadResult(byte[] fileBytes, String fileName) {
    }

    // 添加到类的成员变量区域（如果没有 MAX_BATCH_BYTES）
    private static final long MAX_BATCH_BYTES = 100 * 1024 * 1024; // 100MB

    /**
     * 批量下载合同文件（返回ZIP压缩包）
     */
    public byte[] downloadContractBatch(List<Long> contractIds) throws IOException {
        log.info("合同批量下载开始: contractIds={}", contractIds);

        // 去重
        List<Long> uniqueIds = contractIds.stream().distinct().toList();
        if (uniqueIds.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "合同ID列表不能为空");
        }

        // 批量查询合同信息（循环单个查询）
        List<Contract> contracts = new ArrayList<>();
        for (Long contractId : uniqueIds) {
            contractRepository.findById(contractId).ifPresent(contracts::add);
        }
        if (contracts.isEmpty()) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "未找到任何合同");
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {

            Set<String> fileNames = new HashSet<>();
            long totalBytes = 0;

            for (Contract contract : contracts) {
                // 仅创建状态需要按当前可编辑数据生成；其他状态直接读取已保存文件。
                String fileUrl = null;
                byte[] fileBytes;
                if (contract.getStatus() == ContractStatus.CREATED) {
                    fileBytes = generateLatestContractPdf(contract, "批量下载原始合同");
                    fileUrl = contract.getContractPdfUrl();
                } else if (usesFadadaDocument(contract.getStatus())) {
                    fileBytes = downloadFadadaDocument(contract);
                } else {
                    fileUrl = StringUtils.hasText(contract.getSignedPdfUrl())
                            ? contract.getSignedPdfUrl() : contract.getContractPdfUrl();
                    if (!StringUtils.hasText(fileUrl)) {
                        throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                                "合同文件不存在：contractNo=" + contract.getContractNo());
                    }
                    fileBytes = contractFileStore.download(fileUrl);
                    log.info("批量下载非创建状态合同，直接使用已保存文件：contractNo={}, status={}",
                            contract.getContractNo(), contract.getStatus());
                }
                totalBytes += fileBytes.length;

                // 检查总大小限制
                if (totalBytes > MAX_BATCH_BYTES) {
                    throw new BusinessException(ResultCode.SHIPPING_MARK_BATCH_TOO_LARGE,
                            "批量下载文件总大小超过限制(100MB)");
                }

                // 生成唯一文件名：合同编号.pdf
                String extension = fileUrl != null && fileUrl.endsWith(".xlsx") ? ".xlsx" : ".pdf";
                String stageSuffix = contract.getStatus() == ContractStatus.SIGNING ? "-签署中" : "";
                String fileName = uniqueFileName(contract.getContractNo() + stageSuffix + extension, fileNames);

                // 写入ZIP
                zip.putNextEntry(new ZipEntry(fileName));
                zip.write(fileBytes);
                zip.closeEntry();

                log.info("合同文件已添加到ZIP: contractNo={}, fileName={}, size={}",
                        contract.getContractNo(), fileName, fileBytes.length);
            }

            zip.finish();
            byte[] zipBytes = output.toByteArray();

            log.info("合同批量下载完成: 请求数量={}, 成功数量={}, ZIP大小={}",
                    contractIds.size(), contracts.size(), zipBytes.length);

            return zipBytes;

        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("合同批量下载失败", e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "合同文件下载失败: " + e.getMessage());
        }
    }

    /**
     * 生成唯一文件名（如果重名则添加序号）
     */
    private String uniqueFileName(String fileName, Set<String> existingNames) {
        if (!existingNames.contains(fileName)) {
            existingNames.add(fileName);
            return fileName;
        }

        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        String extension = fileName.substring(fileName.lastIndexOf('.'));

        int counter = 1;
        String uniqueName;
        do {
            uniqueName = baseName + "_" + counter + extension;
            counter++;
        } while (existingNames.contains(uniqueName));

        existingNames.add(uniqueName);
        return uniqueName;
    }
}
