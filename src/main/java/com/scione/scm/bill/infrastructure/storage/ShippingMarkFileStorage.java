package com.scione.scm.bill.infrastructure.storage;

import com.scione.scm.bill.application.port.ShippingMarkFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

/**
 * 箱唛图片和生成文件的本地文件系统存储适配器。
 */
@Component
public class ShippingMarkFileStorage implements ShippingMarkFileStore {

    private static final String ROOT_PUBLIC_PREFIX = "/api/v1/shipping-marks/files/";
    private final Path root;
    private final String publicPrefix;

    public ShippingMarkFileStorage(
            @Value("${shipping-mark.storage-path:storage/shipping-marks}") String storagePath,
            @Value("${server.servlet.context-path:}") String contextPath) {
        this.root = Paths.get(storagePath).toAbsolutePath().normalize();
        String normalizedContextPath = contextPath == null || contextPath.isBlank() ? "" : contextPath.replaceAll("/+$", "");
        this.publicPrefix = normalizedContextPath + ROOT_PUBLIC_PREFIX;
    }

    @Override
    public String storeImage(String billNo, byte[] content, String extension) throws IOException {
        return store(billNo, "images", content, normalizeImageExtension(extension));
    }

    @Override
    public String storeLabel(String billNo, byte[] content) throws IOException {
        return store(billNo, "labels", content, "xlsx");
    }

    @Override
    public byte[] load(String billNo, String category, String fileName) throws IOException {
        Path path = resolve(billNo, category, fileName);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Stored shipping mark file does not exist");
        }
        return Files.readAllBytes(path);
    }

    @Override
    public byte[] read(String publicUrl) throws IOException {
        Path path = resolvePublicUrl(publicUrl);
        return Files.readAllBytes(path);
    }

    private String store(String billNo, String category, byte[] content, String extension) throws IOException {
        if (content == null || content.length == 0) {
            throw new IOException("File content is empty");
        }
        validateBillNo(billNo);
        String fileName = UUID.randomUUID() + "." + extension;
        Path directory = root.resolve(billNo).resolve(category).normalize();
        Files.createDirectories(directory);
        Path target = directory.resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Invalid storage path");
        }
        Files.write(target, content);
        return publicPrefix + billNo + "/" + category + "/" + fileName;
    }

    private Path resolvePublicUrl(String publicUrl) throws IOException {
        if (publicUrl == null || !publicUrl.startsWith(publicPrefix)) {
            throw new IOException("Invalid shipping mark file URL");
        }
        String[] pathParts = publicUrl.substring(publicPrefix.length()).split("/");
        if (pathParts.length != 3) {
            throw new IOException("Invalid shipping mark file URL");
        }
        return resolve(pathParts[0], pathParts[1], pathParts[2]);
    }

    private Path resolve(String billNo, String category, String fileName) throws IOException {
        validateBillNo(billNo);
        if (!("images".equals(category) || "labels".equals(category))
                || !fileName.matches("[0-9a-fA-F-]+\\.(png|jpg|jpeg|gif|xlsx)")) {
            throw new IOException("Invalid storage path");
        }
        Path path = root.resolve(billNo).resolve(category).resolve(fileName).normalize();
        if (!path.startsWith(root)) {
            throw new IOException("Invalid storage path");
        }
        return path;
    }

    private void validateBillNo(String billNo) throws IOException {
        if (billNo == null || !billNo.matches("[A-Za-z0-9-]{1,30}")) {
            throw new IOException("Invalid bill number");
        }
    }

    private String normalizeImageExtension(String extension) {
        String normalized = extension == null ? "png" : extension.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "jpg", "jpeg", "png", "gif" -> normalized;
            default -> "png";
        };
    }
}
