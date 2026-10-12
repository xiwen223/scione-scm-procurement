package com.scione.scm.bill.infrastructure.storage;

import com.scione.api.data.client.S3Client;
import com.scione.api.data.dto.FileInfo;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.port.ContractFileStore;
import com.scione.scm.bill.config.StorageProperties;
import feign.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;

/** 通过数据平台存储合同文件，新文件保存对象键，兼容历史完整 URL。 */
@Service
@RequiredArgsConstructor
public class S3ContractFileStore implements ContractFileStore {
    private final S3Client s3Client;
    // 仅用于识别历史 URL 中的 bucket，不再用于连接 S3。
    private final StorageProperties storageProperties;

    @Override
    public String store(String contractNo, byte[] fileBytes, String extension) throws IOException {
        try {
            if (fileBytes == null || fileBytes.length == 0) throw new IOException("合同文件不能为空");
            MultipartFile file = new ContractMultipartFile(contractNo + "." + extension, getContentType(extension), fileBytes);
            ApiResponse<FileInfo> response = s3Client.upload(file, "contracts");
            if (response == null || !response.isSuccess()) {
                throw new IOException("数据平台上传失败：" + (response == null ? "无响应" : response.getMessage()));
            }
            FileInfo info = response.getData();
            if (info == null || info.getObjectKey() == null || info.getObjectKey().isBlank()) {
                throw new IOException("数据平台未返回合同对象键");
            }
            return info.getObjectKey();
        } catch (RuntimeException ex) { throw new IOException("合同文件上传失败", ex); }
    }

    @Override
    public byte[] download(String fileUrl) throws IOException {
        try (Response response = s3Client.download(extractObjectKey(fileUrl))) {
            if (response == null || response.status() != 200 || response.body() == null) {
                throw new IOException("数据平台下载失败：" + (response == null ? "无响应" : "HTTP " + response.status()));
            }
            try (InputStream input = response.body().asInputStream()) {
                byte[] bytes = input.readAllBytes();
                if (bytes.length == 0) throw new IOException("下载的合同文件为空");
                return bytes;
            }
        } catch (RuntimeException ex) { throw new IOException("合同文件下载失败", ex); }
    }

    String extractObjectKey(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) throw new IllegalArgumentException("文件地址不能为空");
        String value = fileUrl.trim();
        if (!value.regionMatches(true, 0, "http://", 0, 7) && !value.regionMatches(true, 0, "https://", 0, 8)) return value;
        String path = URI.create(value).getPath();
        int contracts = path.indexOf("/contracts/");
        if (contracts >= 0) return path.substring(contracts + 1);
        String bucket = storageProperties.getBucket();
        if (bucket != null && !bucket.isBlank() && path.startsWith("/" + bucket + "/")) return path.substring(bucket.length() + 2);
        return path.replaceFirst("^/", "");
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
