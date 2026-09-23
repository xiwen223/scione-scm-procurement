package com.scione.scm.bill.application;

import com.scione.api.data.client.S3Client;
import com.scione.api.data.dto.FileInfo;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.dto.FileUploadResponse;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 通用文件服务：封装数据平台对象存储的上传与临时访问地址获取，供各业务模块复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileApplicationService {

    /** 调用方未指定上传目录时使用的默认目录，与 S3Client#uploadInputStream 的默认值保持一致。 */
    private static final String DEFAULT_UPLOAD_FOLDER = "other";

    /** 文件访问地址默认有效期（分钟），与 S3Client#getPresignedUrl 的默认值保持一致。 */
    private static final long DEFAULT_URL_MINUTES = 30L;

    private static final Pattern UPLOAD_FOLDER_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9/_-]{0,99}");

    private final S3Client s3Client;

    /**
     * 上传文件到对象存储，返回 objectKey 与访问地址；
     * 上传接口不落库，objectKey 由调用方在保存业务数据时写入。
     */
    public FileUploadResponse upload(MultipartFile file, String path) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件不能为空");
        }

        String folder = requireUploadFolder(path);
        FileInfo fileInfo = callStorage(() -> s3Client.upload(file, folder), "上传文件");
        if (fileInfo == null || blankToNull(fileInfo.getObjectKey()) == null) {
            log.error("Storage upload response has no objectKey, path={}", folder);
            throw storageError("上传文件", "存储服务未返回 objectKey");
        }

        return new FileUploadResponse(fileInfo.getObjectKey(), fileInfo.getUrl(),
                fileInfo.getOriginalName(), fileInfo.getContentType(), fileInfo.getSize());
    }

    /**
     * 按 objectKey 获取文件的临时访问地址，minutes 为空时取默认有效期。
     */
    public String presignedUrl(String objectKey, Long minutes) {
        String key = requireText(objectKey, "objectKey 不能为空");
        long effectiveMinutes = minutes == null || minutes <= 0 ? DEFAULT_URL_MINUTES : minutes;

        String url = callStorage(() -> s3Client.getPresignedUrl(key, effectiveMinutes), "获取文件访问地址");
        if (blankToNull(url) == null) {
            log.error("Storage presigned url is empty, objectKey={}", key);
            throw storageError("获取文件访问地址", "存储服务未返回访问地址");
        }

        return url;
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

    /**
     * 调用数据平台对象存储接口：业务码失败、响应为空或服务不可用时统一抛 STORAGE_API_ERROR。
     */
    private <T> T callStorage(Supplier<ApiResponse<T>> call, String action) {
        ApiResponse<T> response;
        try {
            response = call.get();
        } catch (FeignException exception) {
            log.error("Storage request failed, action={}", action, exception);
            throw storageError(action, exception.status() > 0 ? "HTTP " + exception.status() : "网络连接失败");
        }
        if (response == null) {
            log.error("Storage service returned no body, action={}", action);
            throw storageError(action, "存储服务无响应");
        }
        if (!response.isSuccess()) {
            String reason = "业务码 " + response.getCode()
                    + (blankToNull(response.getMessage()) == null ? "" : "：" + response.getMessage());
            log.warn("Storage request was rejected, action={}, reason={}", action, reason);
            throw storageError(action, reason);
        }
        return response.getData();
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
