package com.scione.scm.bill.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 文件路径工具类。
 */
public class FilePathUtils {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private FilePathUtils() {
    }

    /**
     * 构建 S3 Object Key（带时间戳，避免重名）。
     *
     * @param folder   文件夹路径（如 "contracts"）
     * @param fileName 文件名（如 "HT20260922xxxx.xlsx"）
     * @return objectKey（如 "contracts/HT20260922xxxx_20260922143025.xlsx"）
     */
    public static String buildObjectKey(String folder, String fileName) {
        String suffix = getFileExtension(fileName);
        String baseName = getFileNameWithoutExtension(fileName);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String newFileName = baseName + "_" + timestamp;

        if (suffix != null && !suffix.isBlank()) {
            newFileName = newFileName + "." + suffix;
        }

        return (folder == null || folder.isBlank()) ? newFileName : folder + "/" + newFileName;
    }

    /**
     * 获取文件扩展名。
     */
    private static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot > 0) ? fileName.substring(lastDot + 1) : "";
    }

    /**
     * 获取不带扩展名的文件名。
     */
    private static String getFileNameWithoutExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot > 0) ? fileName.substring(0, lastDot) : fileName;
    }
}