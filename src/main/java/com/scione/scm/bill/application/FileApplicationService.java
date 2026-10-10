package com.scione.scm.bill.application;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import com.scione.scm.bill.config.StorageProperties;
import com.scione.scm.bill.common.FilePathUtils;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import com.scione.scm.bill.application.dto.FileUploadResponse;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.regex.Pattern;

/**
 * 通用文件服务：封装 AWS S3 对象存储的上传与临时访问地址获取，供各业务模块复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileApplicationService {

    /** 调用方未指定上传目录时使用的默认目录。 */
    private static final String DEFAULT_UPLOAD_FOLDER = "other";

    /** 文件访问地址默认有效期（分钟）。 */
    private static final long DEFAULT_URL_MINUTES = 30L;

    private static final Pattern UPLOAD_FOLDER_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9/_-]{0,99}");

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;

    /**
     * 上传文件到对象存储，返回 objectKey 与访问地址；
     * 上传接口不落库，objectKey 由调用方在保存业务数据时写入。
     */
    public FileUploadResponse upload(MultipartFile file, String path) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件不能为空");
        }

        String folder = requireUploadFolder(path);
        String objectKey = FilePathUtils.buildObjectKey(folder, file.getOriginalFilename());
        try (InputStream input = file.getInputStream()) {
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(storageProperties.getBucket()).key(objectKey)
                    .contentType(file.getContentType()).contentLength(file.getSize()).build(),
                    RequestBody.fromInputStream(input, file.getSize()));
            return new FileUploadResponse(objectKey, null, file.getOriginalFilename(),
                    file.getContentType(), file.getSize());
        } catch (IOException | RuntimeException exception) {
            log.error("S3 upload failed, objectKey={}", objectKey, exception);
            throw storageError("上传文件", "存储操作失败");
        }
    }

    /**
     * 按 objectKey 获取文件的临时访问地址，minutes 为空时取默认有效期。
     */
    public String presignedUrl(String objectKey, Long minutes) {
        String key = requireText(objectKey, "objectKey 不能为空");
        long effectiveMinutes = minutes == null || minutes <= 0 ? DEFAULT_URL_MINUTES : minutes;

        try {
            return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(effectiveMinutes))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(storageProperties.getBucket()).key(key).build()).build())
                    .url().toString();
        } catch (RuntimeException exception) {
            log.error("S3 presign failed, objectKey={}", key, exception);
            throw storageError("获取文件访问地址", "存储操作失败");
        }
    }

    /**
     * 按 objectKey 删除对象存储中的文件。
     *
     * <p>objectKey 为空时视为无需删除，直接返回（幂等）：调用方清空签章等场景下，
     * 历史数据可能只有 Base64 而没有对象键。</p>
     */
    public void delete(String objectKey) {
        String key = blankToNull(objectKey);
        if (key == null) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(storageProperties.getBucket()).key(key).build());
        } catch (RuntimeException exception) {
            log.error("S3 delete failed, objectKey={}", key, exception);
            throw storageError("删除文件", "存储操作失败");
        }
    }

    /**
     * 归一化对象存储目录：去掉首尾斜杠（避免 objectKey 出现重复分隔符），
     * 为空时使用默认目录，含非法字符时拒绝，防止拼出越界的对象键。
     */
    private static String requireUploadFolder(String path) {
        if (path == null || path.isBlank()) {
            return DEFAULT_UPLOAD_FOLDER;
        }
        String folder = path.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        if (folder.isEmpty()) {
            return DEFAULT_UPLOAD_FOLDER;
        }
        if (!UPLOAD_FOLDER_PATTERN.matcher(folder).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传目录不合法");
        }
        return folder;
    }

    private BusinessException storageError(String action, String reason) {
        String message = ResultCode.STORAGE_API_ERROR.getMessage() + "（" + action + "）"
                + (blankToNull(reason) == null ? "" : "：" + reason);
        return new BusinessException(ResultCode.STORAGE_API_ERROR, message);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
