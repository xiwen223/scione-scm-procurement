package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.*;
import com.scione.scm.bill.application.port.ContractFileStore;
import com.scione.scm.bill.application.port.ContractPdfConverter;
import com.scione.scm.bill.application.port.ContractTemplateService;
import com.scione.scm.bill.application.port.LingxingProductClient;
import com.scione.scm.bill.config.FadadaOpenApiProperties;
import com.scione.scm.bill.domain.company.BuyerCompany;
import com.scione.scm.bill.domain.company.BuyerCompanyRepository;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.domain.contract.ContractPage;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractStatus;
import com.scione.scm.bill.infrastructure.fadada.FadadaOpenApiClient;
import com.scione.scm.bill.infrastructure.fadada.FadadaAlertContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 合同查询应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractQueryService {

    private volatile HttpClient downloadHttpClient;

    private HttpClient downloadHttpClient() {
        if (downloadHttpClient == null) {
            synchronized (this) {
                if (downloadHttpClient == null) downloadHttpClient = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(fadadaOpenApiProperties.getFileConnectTimeout()).build();
            }
        }
        return downloadHttpClient;
    }

    private final ContractRepository contractRepository;
    private final ObjectProvider<ContractQueryService> selfProvider;
    private final ContractFileStore contractFileStore;
    /** 法大大下载要按合同自己需方公司的 openCorpId 定位任务归属方，不能只依赖全局配置。 */
    private final BuyerCompanyRepository buyerCompanyRepository;
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
        // 列表步骤1：先规范页码和分页大小，后续 SQL 使用修正后的参数计算 offset。
        request.validateAndFix();

        // 查询数据库
        // 列表步骤2：查询总数和当前页主信息，不在此逐份读取 PDF 或调用法大大。
        ContractPage page = contractRepository.findByPage(request);

        // 催办时间不落合同表（只写操作日志），列表展示时按当前页合同批量取最近一次催办时间
        // 列表步骤3：只汇总本页合同的催办日志，一次补齐展示字段，避免按每行查询。
        Map<Long, LocalDateTime> lastUrgeTimes = contractRepository.findLatestUrgeTimes(
                page.records().stream().map(Contract::getId).toList());

        // Domain 转 DTO
        List<ContractListItemResponse> items = page.records().stream()
                .map(contract -> ContractListItemResponse.from(contract, lastUrgeTimes.get(contract.getId())))
                .collect(Collectors.toList());

        log.info("查询合同列表成功：total={}, records={}", page.total(), items.size());
        return new PageResult<>(page.total(), items);
    }

    /**
     * 查询合同详情。
     */
    public ContractDetailResponse getContractDetail(Long contractId) {
        log.info("查询合同详情：contractId={}", contractId);

        // 合同不存在属于业务上的「资源不存在」，必须抛 BusinessException，
        // 否则会被 GlobalExceptionHandler 归类为系统异常，前端拿到 HTTP 500 而非 404。
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                        "合同不存在：contractId=" + contractId));

        // 详情步骤2：历史明细缺图时补展示图片；补图不代表重新同步合同价格或供方字段。
        fillMissingItemImages(contract);
        // 详情步骤3：将领域快照转成对外 DTO，输出日期、金额、主状态及过程标记。
        ContractDetailResponse response = ContractDetailResponse.from(contract);

        log.info("查询合同详情成功：contractNo={}", contract.getContractNo());
        return response;
    }

    /**
     * 新合同创建时会将领星商品图片保存到 contract_item。历史合同可能没有保存图片，
     * 详情查询时按同一 SKU 查询逻辑补齐展示数据，并**把补到的图片回写 contract_item**，
     * 这样后续详情查询、下载与 PDF 生成都能直接复用，不必每次再查一次领星。
     *
     * <p><b>取数一律走批量</b>：先把整单缺图的 SKU 一次性查库（复用历史合同同 SKU 图片），
     * 仍未命中的 SKU 再一次批量请求领星。按明细逐条查会变成 2N 次调用（N 次 SQL + N 次 HTTP），
     * 既慢又容易触发领星限流。</p>
     *
     * <p>详情查询本身是只读语义，因此回写失败只记日志，绝不影响详情返回。</p>
     */
    private void fillMissingItemImages(Contract contract) {
        if (contract.getItems() == null || contract.getItems().isEmpty()) {
            return;
        }
        List<ContractItem> missing = contract.getItems().stream()
                .filter(item -> !StringUtils.hasText(item.getPicUrl()) && StringUtils.hasText(item.getSku()))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Set<String> skus = missing.stream()
                .map(ContractItem::getSku)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 1. 一次查库：复用历史合同已保存的同 SKU 图片
        Map<String, String> resolvedPicUrls = new LinkedHashMap<>();
        List<String> needRemote = new ArrayList<>();
        Map<String, String> cachedPicUrls = Map.of();
        try {
            cachedPicUrls = contractRepository.findLatestItemPicUrlsBySkus(skus);
        } catch (Exception e) {
            // 补图失败绝不能影响合同详情主流程：查库失败就全部退化为查领星
            log.info("合同明细图片缓存查询失败（改为直接查领星）：contractNo={}", contract.getContractNo(), e);
        }
        for (String sku : skus) {
            String cached = cachedPicUrls.get(sku);
            if (StringUtils.hasText(cached)) {
                resolvedPicUrls.put(sku, cached);
            } else {
                needRemote.add(sku);
            }
        }

        // 2. 一次请求：仍未命中的 SKU 批量查领星（接口本身支持 skus 数组）
        if (!needRemote.isEmpty()) {
            try {
                lingxingProductClient.findBySkus(needRemote).forEach((sku, product) -> {
                    if (product != null && StringUtils.hasText(product.picUrl())) {
                        resolvedPicUrls.put(sku, product.picUrl());
                    }
                });
            } catch (Exception e) {
                // 图片展示失败不影响合同详情主流程；下次查询仍会尝试补齐。
                log.info("合同明细图片批量查询失败：contractNo={}, 待查 SKU 数={}",
                        contract.getContractNo(), needRemote.size(), e);
            }
        }

        // 3. 回填内存对象并落库
        List<ContractItem> filledItems = new ArrayList<>();
        for (ContractItem item : missing) {
            String picUrl = resolvedPicUrls.get(item.getSku());
            if (StringUtils.hasText(picUrl)) {
                item.setPicUrl(picUrl);
                filledItems.add(item);
            }
        }
        persistResolvedItemImages(contract, filledItems);
        log.info("合同详情明细图片补齐完成：contractNo={}, 缺图={}, 补齐={}, 领星查询={}",
                contract.getContractNo(), missing.size(), filledItems.size(), needRemote.size());
    }

    /**
     * 把补齐的图片回写 contract_item（只更新 pic_url 一列），失败不影响详情返回。
     */
    private void persistResolvedItemImages(Contract contract, List<ContractItem> resolved) {
        if (resolved.isEmpty()) {
            return;
        }
        int persisted = 0;
        for (ContractItem item : resolved) {
            if (item.getId() == null) {
                continue;
            }
            try {
                contractRepository.updateItemPicUrl(item.getId(), item.getPicUrl());
                persisted++;
            } catch (Exception e) {
                log.info("合同明细图片落库失败（不影响详情展示）：contractNo={}, itemId={}",
                        contract.getContractNo(), item.getId(), e);
            }
        }
        log.info("合同详情明细图片落库：contractNo={}, 补齐={}, 落库={}",
                contract.getContractNo(), resolved.size(), persisted);
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
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                        "合同不存在：contractId=" + contractId));

        try {
            return downloadContractFileContent(contract, type);
        } catch (Exception ex) {
            throw recordDownloadFailure(contract, "单个下载", ex);
        }
    }

    private DownloadResult downloadContractFileContent(Contract contract, String type) throws IOException {
        // 仅创建状态允许编辑，因此只对创建状态在 URL 为空时按当前数据库数据即时生成。
        // 签署中及之后直接读取已保存文件，避免下载时重复模板填充、PDF 转换及存储上传。
        if (!"signed".equals(type)) {
            // 下载分支1：创建状态有缓存 URL 就下载；URL 被编辑清空或从未生成时，才按最新数据重建。
            if (contract.getStatus() == ContractStatus.CREATED) {
                if (!StringUtils.hasText(contract.getContractPdfUrl())) {
                    byte[] latestPdf = generateLatestContractPdf(contract, "下载创建状态合同");
                    return new DownloadResult(latestPdf, contract.getContractNo() + ".pdf");
                }
                byte[] cachedPdf = contractFileStore.download(contract.getContractPdfUrl());
                return new DownloadResult(cachedPdf, contract.getContractNo() + ".pdf");
            }
            // 下载分支2：非创建状态不能重新编辑生成原合同，读取已保存文件，缺地址则明确报资源不存在。
            String fileUrl = getOriginalPdfUrl(contract);
            if (!StringUtils.hasText(fileUrl)) {
                // 合同本身存在、但未保存过合同文件：属于「文件资源不存在」，给 404 而不是系统异常
                throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                        "合同文件不存在，无法下载：contractNo=" + contract.getContractNo());
            }
            byte[] fileBytes = contractFileStore.download(fileUrl);
            log.info("下载非创建状态合同，直接使用已保存文件：contractNo={}, status={}, fileUrl={}",
                    contract.getContractNo(), contract.getStatus(), fileUrl);
            return new DownloadResult(fileBytes, extractFileName(fileUrl, contract.getContractNo(), "original"));
        }

        String fileUrl = getSignedPdfUrl(contract);

        if (fileUrl == null || fileUrl.isEmpty()) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                    "合同文件不存在，无法下载：contractNo=" + contract.getContractNo() + ", type=" + type);
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
            // 重建步骤1：按当前数据库快照填充模板并转 PDF，只有生成成功才继续上传和替换 URL。
            byte[] pdfBytes = contractPdfConverter.convert(contractTemplateService.fillTemplate(contract),
                    contract.getContractNo());
            // 重建步骤2：文件先保存到 S3，再写数据库 URL；这里任一步失败都会进入下载失败日志。
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
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                        "合同不存在：contractId=" + contractId));
        if (!usesFadadaDocument(contract.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "创建状态合同应下载未盖章原合同");
        }
        try {
            return selfProvider.getObject().getFadadaDocumentDownloadUrl(contract);
        } catch (Exception ex) {
            throw recordDownloadFailure(contract, "获取签署文件下载地址", ex);
        }
    }

    /**
     * 获取法大大合同文档下载地址，并通过注解补充当前合同的告警上下文。
     * <p>单个及批量下载均通过 selfProvider 获取代理后调用，确保每份合同分别触发切面。</p>
     * @param contract 已查询的合同对象，直接用于提取公司名称和合同编号
     * @return 法大大返回的短期文档下载地址
     */
    @FadadaAlertContext(FadadaAlertContext.Type.CONTRACT)
    public String getFadadaDocumentDownloadUrl(Contract contract) {
        if (contract.getFadadaTaskId() == null || contract.getFadadaTaskId().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "合同未关联法大大签署任务");
        }
        // 归属方必须与签署任务实际发起的企业一致：法大大按「应用 + openId」校验任务归属，
        // 用当前配置的企业去查另一个企业签出的任务，会分别报 210032（企业用户不存在，
        // 该企业在当前应用下没有）与 211150（发起方或者参与方不匹配）。
        // 历史数据里需方公司存在多个 openCorpId，所以这里取合同自己需方公司的 openCorpId；
        // 该企业所属应用的凭据由客户端按 fadada.open-api.apps 自动选用（见 FadadaOpenApiClient）。
        String ownerOpenCorpId = resolveFadadaOwnerOpenCorpId(contract);
        boolean signing = contract.getStatus() == ContractStatus.SIGNING;
        // 法大大下载接口会根据文档类型补上 .pdf，customName 只传不带扩展名的名称。
        String customName = contract.getContractNo() + (signing ? "-签署中合同" : "-已签署合同");
        String downloadUrl;
        try {
            downloadUrl = fadadaOpenApiClient.getSignTaskDownloadUrl(
                    new FadadaOpenApiClient.DownloadUrlRequest("corp", ownerOpenCorpId,
                            contract.getFadadaTaskId(), customName, false, "download"));
        } catch (BusinessException exception) {
            throw new BusinessException(ResultCode.FADADA_API_ERROR,
                    exception.getMessage() + describeOwnerMismatch(contract, ownerOpenCorpId));
        }
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

    /**
     * 解析签署任务的归属企业：取合同关联需方公司的 openCorpId。
     *
     * <p>需方公司未做企业授权时无法定位归属方，这里直接给出可操作的业务提示，
     * 避免把法大大底层的 210032 / 211150 抛给用户。</p>
     */
    private String resolveFadadaOwnerOpenCorpId(Contract contract) {
        BuyerCompany buyer = buyerCompanyRepository.findById(contract.getBuyerCompanyId())
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                        "合同关联的需方公司不存在，无法下载法大大签署文件"));
        if (!StringUtils.hasText(buyer.getOpenCorpId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "需方公司「" + buyer.getCompanyName() + "」未配置法大大企业标识，无法下载签署文件，请先完成企业授权");
        }
        return buyer.getOpenCorpId();
    }

    /**
     * 需方企业未登记任何应用凭据时补充可操作提示。
     *
     * <p>法大大的企业挂在应用（appId）之下，客户端遇到「应用不匹配」错误码时会自动换用
     * {@code fadada.open-api.apps} 中登记的其他应用重试。因此只有该企业完全没登记时
     * 才会走到这里，此时必须补配置或重新授权。</p>
     */
    private String describeOwnerMismatch(Contract contract, String ownerOpenCorpId) {
        String configuredOpenCorpId = fadadaOpenApiProperties.getOpenCorpId();
        boolean isConfiguredPrimary = StringUtils.hasText(configuredOpenCorpId)
                && configuredOpenCorpId.equals(ownerOpenCorpId);
        if (isConfiguredPrimary || isRegisteredInApps(ownerOpenCorpId)) {
            return "";
        }
        log.info("法大大任务归属企业未登记任何应用凭据，下载会失败：contractNo={}, signTaskId={}, buyerOpenCorpId={}, appId={}",
                contract.getContractNo(), contract.getFadadaTaskId(), maskId(ownerOpenCorpId),
                fadadaOpenApiProperties.getAppId());
        return "；该合同需方公司使用的法大大企业（openCorpId=" + maskId(ownerOpenCorpId)
                + "）未登记任何应用凭据，请在 fadada.open-api.apps 中补充该企业所属应用的 appId/appSecret，"
                + "或把需方公司重新授权到当前应用（appId=" + fadadaOpenApiProperties.getAppId() + "）";
    }

    private boolean isRegisteredInApps(String openCorpId) {
        if (!StringUtils.hasText(openCorpId) || fadadaOpenApiProperties.getApps() == null) {
            return false;
        }
        return fadadaOpenApiProperties.getApps().stream()
                .anyMatch(app -> app != null && openCorpId.equals(app.getOpenCorpId()));
    }

    private String maskId(String value) {
        if (!StringUtils.hasText(value)) {
            return "<empty>";
        }
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }

    private byte[] downloadFadadaDocument(Contract contract) throws IOException {
        String downloadUrl = selfProvider.getObject().getFadadaDocumentDownloadUrl(contract);
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> transfer = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .GET()
                    .timeout(fadadaOpenApiProperties.getFileDownloadTimeout())
                    .build();
            transfer = downloadHttpClient().sendAsync(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> response = transfer.get(fadadaOpenApiProperties.getFileDownloadTimeout().toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
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
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IOException("法大大文件下载超时", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IOException("法大大文件传输失败", ex.getCause());
        } finally {
            if (transfer != null && !transfer.isDone()) transfer.cancel(true);
        }
    }

    public record PageResult<T>(long total, List<T> records) {
    }

    /**
     * 下载结果封装。
     */
    public record DownloadResult(byte[] fileBytes, String fileName) {
    }

    /**
     * 批量下载合同文件（返回ZIP压缩包）
     */
    public byte[] downloadContractBatch(List<Long> contractIds) throws IOException {
        log.info("合同批量下载开始: contractIds={}", contractIds);

        // 去重
        // 批量步骤1：去重避免同一合同重复导出；此处去重不改变每份合同的下载来源。
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

        // 批量步骤2：创建 ZIP 输出流；所有文件按顺序写入，try-with-resources 负责异常时关闭。
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {

            Set<String> fileNames = new HashSet<>();

            for (Contract contract : contracts) {
                try {
                // 仅创建状态需要按当前可编辑数据生成；其他状态直接读取已保存文件。
                String fileUrl = null;
                byte[] fileBytes;
                // 批量步骤3：每份合同独立按状态取文件，不能把混合状态的一批合同都按同一个来源下载。
                if (contract.getStatus() == ContractStatus.CREATED) {
                    if (StringUtils.hasText(contract.getContractPdfUrl())) {
                        fileUrl = contract.getContractPdfUrl();
                        fileBytes = contractFileStore.download(fileUrl);
                    } else {
                        fileBytes = generateLatestContractPdf(contract, "批量下载原始合同");
                        fileUrl = contract.getContractPdfUrl();
                    }
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
                } catch (Exception ex) {
                    // 当前策略是单份失败即终止本次导出，不返回不完整 ZIP；记录具体合同便于用户定位。
                    throw recordDownloadFailure(contract, "批量下载", ex);
                }
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

    /** 文件下载失败同时写服务日志和合同操作日志，日志写入失败不覆盖原始下载错误。 */
    private BusinessException recordDownloadFailure(Contract contract, String scene, Exception exception) {
        String reason = StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "文件处理失败";
        Throwable cause = exception;
        // 失败步骤1：沿 cause 查底层异常，最多追溯十层，避免循环引用导致无限遍历。
        for (int depth = 0; depth < 10 && cause.getCause() != null && cause.getCause() != cause; depth++) {
            cause = cause.getCause();
        }
        if (cause != exception && StringUtils.hasText(cause.getMessage())) {
            reason += "；原因：" + cause.getMessage();
        }
        log.error("合同下载失败：scene={}, contractId={}, contractNo={}, status={}, templateId={}",
                scene, contract.getId(), contract.getContractNo(), contract.getStatus(), contract.getTemplateId(), exception);
        try {
            String operator = Contract.SYSTEM_OPERATOR;
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes requestAttributes) {
                String email = requestAttributes.getRequest().getHeader("X-User-Email");
                if (StringUtils.hasText(email)) operator = email;
            }
            ContractOperationLog operation = ContractOperationLog.ofUpdate(contract.getId(), contract.getContractNo(),
                    operator, operator, "合同下载失败",
                    "下载方式=" + scene + "；合同状态=" + contract.getStatus() + "；模板ID=" + contract.getTemplateId()
                            + "；失败原因=" + reason.substring(0, Math.min(reason.length(), 1000)));
            operation.setOperationType(ContractOperationLog.TYPE_DOWNLOAD_FAILED);
            // 失败步骤2：写数据库操作日志；写日志异常单独处理，不能覆盖最初的下载失败。
            contractRepository.saveOperationLog(operation);
        } catch (Exception logException) {
            log.error("下载失败操作日志保存失败：contractId={}, contractNo={}",
                    contract.getId(), contract.getContractNo(), logException);
        }
        ResultCode code = exception instanceof BusinessException businessException
                ? businessException.getResultCode() : ResultCode.SYSTEM_ERROR;
        return new BusinessException(code, scene + "失败，合同编号：" + contract.getContractNo()
                + "；" + (StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "文件处理失败"));
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
