package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.FileApplicationService;
import com.scione.scm.bill.application.dto.FileUploadResponse;
import com.scione.scm.bill.application.dto.FileUrlRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用文件接口：供各业务模块上传文件、换取文件临时访问地址。
 */
@RestController
@Validated
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Tag(name = "文件", description = "通用文件上传与访问地址")
public class FileController {

    private final FileApplicationService service;

    /**
     * 上传文件到对象存储；返回的 objectKey 由调用方在保存业务数据时写入对应字段。
     * path 为对象存储目录，不传时使用默认目录 other。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        return ApiResponse.success(service.upload(file, path));
    }

    /**
     * 按 objectKey 获取文件的临时访问地址（预签名 URL），用于页面预览或下载。
     */
    @PostMapping("/file-url")
    public ApiResponse<String> fileUrl(@Valid @RequestBody FileUrlRequest request) {
        return ApiResponse.success(service.presignedUrl(request.objectKey(), request.minutes()));
    }
}
