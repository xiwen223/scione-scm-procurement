package com.scione.scm.bill.infrastructure.storage;

import com.scione.api.data.client.S3Client;
import com.scione.api.data.dto.FileInfo;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.port.ContractFileStore;
import feign.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.Files;

/** 通过数据平台上传和下载合同文件，数据库保存数据平台返回的对象键。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class S3ContractFileStore implements ContractFileStore {
    private static final String STORAGE_SERVICE = "scione-data-platform";
    private static final String BUCKET_HEADER = "X-Storage-Bucket";
    private final S3Client s3Client;

    @Override
    public String store(String contractNo, byte[] fileBytes, String extension) throws IOException {
        try {
            if (fileBytes == null || fileBytes.length == 0) throw new IOException("合同文件不能为空");
            log.info("合同文件上传开始：storageService={}, contractNo={}, bytes={}",
                    STORAGE_SERVICE, contractNo, fileBytes.length);
            MultipartFile file = new ContractMultipartFile(contractNo + "." + extension, getContentType(extension), fileBytes);
            ApiResponse<FileInfo> response = s3Client.upload(file, "contracts");
            if (response == null || !response.isSuccess()) {
                throw new IOException("数据平台上传失败：" + (response == null ? "无响应" : response.getMessage()));
            }
            FileInfo info = response.getData();
            if (info == null || info.getObjectKey() == null || info.getObjectKey().isBlank()) {
                throw new IOException("数据平台未返回合同对象键");
            }
            log.info("合同文件上传成功：storageService={}, contractNo={}, bucket={}, objectKey={}, bytes={}",
                    STORAGE_SERVICE, contractNo, info.getBucket(), info.getObjectKey(), fileBytes.length);
            return info.getObjectKey();
        } catch (IOException | RuntimeException ex) {
            log.error("合同文件上传失败：storageService={}, contractNo={}", STORAGE_SERVICE, contractNo, ex);
            if (ex instanceof IOException io) throw io;
            throw new IOException("合同文件上传失败", ex);
        }
    }

    @Override
    public byte[] download(String objectKey) throws IOException {
        if (objectKey == null || objectKey.isBlank()) throw new IOException("文件对象键不能为空");
        log.info("合同文件下载开始：storageService={}, objectKey={}", STORAGE_SERVICE, objectKey);
        try (Response response = s3Client.download(objectKey)) {
            if (response == null || response.status() != 200 || response.body() == null) {
                throw new IOException("数据平台下载失败：" + (response == null ? "无响应" : "HTTP " + response.status()));
            }
            try (InputStream input = response.body().asInputStream()) {
                byte[] bytes = input.readAllBytes();
                if (bytes.length == 0) throw new IOException("下载的合同文件为空");
                // bucket 由 Data 返回，不在 SCM 配置或推测；旧 Data 未返回时明确标记未知。
                String bucket = response.headers().entrySet().stream()
                        .filter(entry -> BUCKET_HEADER.equalsIgnoreCase(entry.getKey()))
                        .flatMap(entry -> entry.getValue().stream())
                        .findFirst().orElse("未知（Data未返回bucket响应头）");
                log.info("合同文件下载成功：storageService={}, bucket={}, objectKey={}, bytes={}",
                        STORAGE_SERVICE, bucket, objectKey, bytes.length);
                return bytes;
            }
        } catch (IOException | RuntimeException ex) {
            log.error("合同文件下载失败：storageService={}, objectKey={}", STORAGE_SERVICE, objectKey, ex);
            if (ex instanceof IOException io) throw io;
            throw new IOException("合同文件下载失败", ex);
        }
    }

    private String getContentType(String extension) {
        return switch (extension.toLowerCase()) {
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private record ContractMultipartFile(String filename, String contentType, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }
}
