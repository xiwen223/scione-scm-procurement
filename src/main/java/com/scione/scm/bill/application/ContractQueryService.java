package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.*;
import com.scione.scm.bill.application.port.ContractFileStore;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractPage;
import com.scione.scm.bill.domain.contract.ContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 合同查询应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractQueryService {

    private final ContractRepository contractRepository;
    private final ContractFileStore contractFileStore;

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

        ContractDetailResponse response = ContractDetailResponse.from(contract);

        log.info("查询合同详情成功：contractNo={}", contract.getContractNo());
        return response;
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

        // 根据类型选择 URL
        String fileUrl = "signed".equals(type)
                ? getSignedPdfUrl(contract)
                : getOriginalPdfUrl(contract);

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
                // 优先下载已签署PDF，如果没有则下载原始文件
                String fileUrl = contract.getSignedPdfUrl() != null
                        ? contract.getSignedPdfUrl()
                        : contract.getContractPdfUrl();

                if (fileUrl == null) {
                    log.warn("合同文件不存在，跳过: contractNo={}", contract.getContractNo());
                    continue;
                }

                // 下载文件
                byte[] fileBytes = contractFileStore.download(fileUrl);
                totalBytes += fileBytes.length;

                // 检查总大小限制
                if (totalBytes > MAX_BATCH_BYTES) {
                    throw new BusinessException(ResultCode.SHIPPING_MARK_BATCH_TOO_LARGE,
                            "批量下载文件总大小超过限制(100MB)");
                }

                // 生成唯一文件名：合同编号.pdf
                String extension = fileUrl.endsWith(".xlsx") ? ".xlsx" : ".pdf";
                String fileName = uniqueFileName(contract.getContractNo() + extension, fileNames);

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