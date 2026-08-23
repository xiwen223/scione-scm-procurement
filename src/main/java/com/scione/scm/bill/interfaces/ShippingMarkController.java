package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.ShippingMarkAppService;
import com.scione.scm.bill.application.ShippingMarkDownloadAppService;
import com.scione.scm.bill.application.ShippingMarkFileAppService;
import com.scione.scm.bill.application.ShippingMarkImportAppService;
import com.scione.scm.bill.application.dto.*;
import com.scione.scm.bill.application.port.ShippingMarkImportParser.ImportDocument;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 箱唛导入、查询、预览和下载接口。
 */
@RestController
@Validated
@RequestMapping("/api/v1/shipping-marks")
@RequiredArgsConstructor
@Tag(name = "箱唛", description = "箱唛单据管理")
public class ShippingMarkController {

    private final ShippingMarkAppService shippingMarkAppService;
    private final ShippingMarkImportAppService importAppService;
    private final ShippingMarkDownloadAppService downloadAppService;
    private final ShippingMarkFileAppService fileAppService;

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ShippingMarkImportDTO>> importFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "operatorId", required = false) String operatorId,
            @RequestParam(value = "operatorName", required = false) String operatorName) {
        try {
            ShippingMarkImportDTO result = importAppService.importFile(importDocument(file), operatorId, operatorName);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success("导入任务已创建", result));
        } catch (BusinessException exception) {
            return ResponseEntity.ok()
                    .body(ApiResponse.fail(exception.getResultCode().getCode(), exception.getMessage()));
        }
    }

    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ShippingMarkParseDTO> parse(@RequestPart("file") MultipartFile file) {
        try {
            return ApiResponse.success(importAppService.parse(importDocument(file)));
        } catch (BusinessException exception) {
            return ApiResponse.fail(exception.getResultCode().getCode(), exception.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<PageResult<ShippingMarkListItemDTO>> list(
            @RequestParam(value = "bill_no", required = false) String billNo,
            @RequestParam(value = "bill_name", required = false) String billName,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于 0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页条数必须大于 0")
            @Max(value = 100, message = "每页条数不能超过 100") int pageSize) {
        return ApiResponse.success(shippingMarkAppService.findPage(billNo, billName, status, pageNum, pageSize));
    }

    @GetMapping("/{markId}")
    public ApiResponse<ShippingMarkDTO> detail(@PathVariable Long markId) {
        return ApiResponse.success(shippingMarkAppService.getById(markId));
    }

    /**
     * 兼容技术方案中的简写路径；markId 实际为原型箱唛明细（标签）ID。
     * 也支持包含主单 ID 的显式明细路径，方便调用方避免 ID 语义歧义。
     */
    @GetMapping({"/{detailId}/preview"})
    public ApiResponse<ShippingMarkPreviewDTO> preview(@PathVariable Long detailId) {
        return ApiResponse.success(downloadAppService.preview(detailId));
    }

    @GetMapping({"/{detailId}/download"})
    public ResponseEntity<byte[]> download(@PathVariable Long detailId) {
        return attachment(downloadAppService.download(detailId), downloadAppService.downloadFileName(detailId),
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @PostMapping(value = "/download", produces = "application/zip")
    public void downloadBatch(
            @Valid @org.springframework.web.bind.annotation.RequestBody BatchDownloadCmd command,
            jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("shipping-marks.zip", StandardCharsets.UTF_8).build().toString());
        try {
            downloadAppService.writeBatch(command.markIds(), response.getOutputStream());
        } catch (BusinessException exception) {
            if (abortStreamingFailure(response)) {
                return;
            }
            throw exception;
        } catch (IOException exception) {
            if (abortStreamingFailure(response)) {
                return;
            }
            throw exception;
        }
    }

    @GetMapping("/files/{billNo}/{category}/{fileName}")
    public ResponseEntity<byte[]> file(
            @PathVariable String billNo, @PathVariable String category, @PathVariable String fileName) {
        byte[] content = fileAppService.load(billNo, category, fileName);
        MediaType contentType = MediaTypeFactory.getMediaType(fileName).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(contentType).body(content);
    }

    private boolean abortStreamingFailure(jakarta.servlet.http.HttpServletResponse response) {
        if (response.isCommitted()) {
            return true;
        }
        response.reset();
        return false;
    }

    private ImportDocument importDocument(MultipartFile file) {
        if (file == null) {
            return new ImportDocument(null, null);
        }
        try {
            return new ImportDocument(decodeFilename(file.getOriginalFilename()), file.getBytes());
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.IMPORT_FILE_INVALID);
        }
    }

    private static final Pattern OCTAL_ESCAPE = Pattern.compile("\\\\([0-7]{1,3})");

    /**
     * 反转义部分客户端把文件名中文按 {@code \ooo} 八进制转义发送的问题，
     * 将其还原为 UTF-8 字节后再解码；正常文件名（不含反斜杠）原样返回。
     */
    private String decodeFilename(String fileName) {
        if (fileName == null || fileName.indexOf('\\') < 0) {
            return fileName;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Matcher matcher = OCTAL_ESCAPE.matcher(fileName);
        int last = 0;
        while (matcher.find()) {
            bytes.writeBytes(fileName.substring(last, matcher.start()).getBytes(StandardCharsets.UTF_8));
            bytes.write(Integer.parseInt(matcher.group(1), 8));
            last = matcher.end();
        }
        bytes.writeBytes(fileName.substring(last).getBytes(StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private ResponseEntity<byte[]> attachment(byte[] content, String fileName, MediaType contentType) {
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(content);
    }
}
