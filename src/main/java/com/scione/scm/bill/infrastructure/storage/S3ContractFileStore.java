package com.scione.scm.bill.infrastructure.storage;

import com.scione.scm.bill.application.port.ContractFileStore;
import com.scione.scm.bill.common.FilePathUtils;
import com.scione.scm.bill.config.StorageProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

/**
 * S3 文件存储实现（合同文档专用）。
 */
@Slf4j
@Service
public class S3ContractFileStore implements ContractFileStore {

    @Resource
    private S3Client s3Client;

    @Resource
    private StorageProperties storageProperties;

    @Override
    public String store(String contractNo, byte[] fileBytes, String extension) throws IOException {
        try {
            // 构建文件名：HT20260922xxxx.xlsx
            String fileName = contractNo + "." + extension;

            // 构建 Object Key：contracts/HT20260922xxxx_20260922143025.xlsx
            String objectKey = FilePathUtils.buildObjectKey("contracts", fileName);

            // 上传到 S3
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(objectKey)
                    .contentType(getContentType(extension))
                    .contentLength((long) fileBytes.length)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(fileBytes));

            // 生成访问 URL
            String fileUrl = getUrl(objectKey);
            log.info("合同文件已上传至 S3：contractNo={}, objectKey={}, url={}",
                    contractNo, objectKey, fileUrl);
            return fileUrl;

        } catch (Exception ex) {
            log.error("S3 上传失败：contractNo={}", contractNo, ex);
            throw new IOException("S3 上传失败", ex);
        }
    }

    @Override
    public byte[] download(String fileUrl) throws IOException {
        try {
            // 从 URL 提取 Object Key
            String objectKey = extractObjectKey(fileUrl);

            log.info("开始下载合同文件：fileUrl={}, objectKey={}", fileUrl, objectKey);

            // 从 S3 下载文件
            byte[] fileBytes = s3Client.getObjectAsBytes(builder -> builder
                    .bucket(storageProperties.getBucket())
                    .key(objectKey)
            ).asByteArray();

            log.info("合同文件下载成功：objectKey={}, size={}", objectKey, fileBytes.length);
            return fileBytes;

        } catch (Exception ex) {
            log.error("S3 下载失败：fileUrl={}", fileUrl, ex);
            throw new IOException("S3 下载失败：" + fileUrl, ex);
        }
    }

    /**
     * 从 URL 提取 Object Key。
     * 例如：https://domain.com/contracts/HT20260922xxxx_20260922143025.xlsx -> contracts/HT20260922xxxx_20260922143025.xlsx
     */
    private String extractObjectKey(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            throw new IllegalArgumentException("文件 URL 不能为空");
        }

        // 如果是完整 URL，提取路径部分
        if (fileUrl.startsWith("http://") || fileUrl.startsWith("https://")) {
            int lastSlashBeforeObjectKey = fileUrl.indexOf('/', 8); // 跳过 "https://"
            if (lastSlashBeforeObjectKey > 0) {
                // 找到 bucket 后的第一个 /
                int bucketEndIndex = fileUrl.indexOf('/', lastSlashBeforeObjectKey + 1);
                if (bucketEndIndex > 0) {
                    return fileUrl.substring(bucketEndIndex + 1);
                }
                // 如果没有 bucket，直接返回域名后的路径
                return fileUrl.substring(lastSlashBeforeObjectKey + 1);
            }
        }

        // 如果已经是 Object Key 格式，直接返回
        return fileUrl;
    }

    /**
     * 获取文件访问 URL。
     */
    private String getUrl(String objectKey) {
        // 优先使用配置的 domain（CDN 或自定义域名）
        if (StringUtils.hasText(storageProperties.getDomain())) {
            return storageProperties.getDomain() + "/" + objectKey;
        }

        // 否则使用 endpoint + bucket 拼接
        return String.format(
                "%s/%s/%s",
                storageProperties.getEndpoint(),
                storageProperties.getBucket(),
                objectKey
        );
    }

    /**
     * 根据扩展名获取 Content-Type。
     */
    private String getContentType(String extension) {
        return switch (extension.toLowerCase()) {
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }
}