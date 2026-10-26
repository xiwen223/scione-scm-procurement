package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.DeliveryAlertAppService;
import com.scione.scm.bill.application.DeliveryFollowUpImportService;
import com.scione.scm.bill.application.ProcurementOperationLogRecorder;
import com.scione.scm.bill.application.vo.DeliveryFollowUpImportVO;
import com.scione.scm.bill.application.vo.DeliveryAlertComponentVO;
import com.scione.scm.bill.application.vo.DeliveryAlertDetailVO;
import com.scione.scm.bill.application.vo.DeliveryAlertListItemVO;
import com.scione.scm.bill.application.vo.DeliveryAlertSummaryVO;
import com.scione.scm.bill.application.vo.DeliveryAlertSyncTimeVO;
import com.scione.scm.bill.application.vo.FilterOptionsVO;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertQuery;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 采购交付预警看板查询接口。
 */
@RestController
@Validated
@RequestMapping("/api/v1/delivery-alerts")
@RequiredArgsConstructor
@Tag(name = "采购交付预警看板", description = "采购交付预警看板")
public class DeliveryAlertController {

    private final DeliveryAlertAppService deliveryAlertAppService;
    private final DeliveryFollowUpImportService importService;

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        return excelResponse(importService.template(), "跟单情况导入模板.xlsx");
    }

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DeliveryFollowUpImportVO> importFollowUp(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = ProcurementOperationLogRecorder.OPERATOR_HEADER, required = false) String operator) {
        try {
            return ApiResponse.success(importService.importFile(file.getOriginalFilename(), file.getBytes(), operator));
        } catch (IOException exception) {
            return ApiResponse.success(importService.rejectFile(file.getOriginalFilename(),
                    "上传文件读取失败，请重新上传", operator));
        }
    }

    @GetMapping("/imports/{logId}/errors")
    public ResponseEntity<byte[]> importErrors(@PathVariable @Min(1) Long logId) {
        return excelResponse(importService.downloadErrors(logId), "跟单情况导入错误.xlsx");
    }

    private static ResponseEntity<byte[]> excelResponse(byte[] content, String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }

    @GetMapping
    public ApiResponse<PageResult<DeliveryAlertListItemVO>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "types", required = false) List<String> types,
            @RequestParam(value = "suppliers", required = false) List<String> suppliers,
            @RequestParam(value = "buyers", required = false) List<String> buyers,
            @RequestParam(value = "warehouses", required = false) List<String> warehouses,
            @RequestParam(value = "riskLevels", required = false) List<String> riskLevels,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "createDateFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateFrom,
            @RequestParam(value = "createDateTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateTo,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于 0") int pageNum,
            @RequestParam(defaultValue = "6") @Min(value = 1, message = "每页条数必须大于 0")
            @Max(value = 100, message = "每页条数不能超过 100") int pageSize) {
        DeliveryAlertQuery query = new DeliveryAlertQuery(
                null, blankToNull(keyword), blankToNull(types), blankToNull(suppliers),
                blankToNull(buyers), blankToNull(warehouses), blankToNull(riskLevels), blankToNull(view),
                createDateFrom, createDateTo, pageNum, pageSize);
        return ApiResponse.success(deliveryAlertAppService.findPage(query));
    }

    @GetMapping("/summary")
    public ApiResponse<DeliveryAlertSummaryVO> summary(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "types", required = false) List<String> types,
            @RequestParam(value = "suppliers", required = false) List<String> suppliers,
            @RequestParam(value = "buyers", required = false) List<String> buyers,
            @RequestParam(value = "warehouses", required = false) List<String> warehouses,
            @RequestParam(value = "riskLevels", required = false) List<String> riskLevels,
            @RequestParam(value = "createDateFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateFrom,
            @RequestParam(value = "createDateTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateTo) {
        DeliveryAlertQuery query = new DeliveryAlertQuery(
                null, blankToNull(keyword), blankToNull(types), blankToNull(suppliers),
                blankToNull(buyers), blankToNull(warehouses), blankToNull(riskLevels), null,
                createDateFrom, createDateTo, 1, 1);
        return ApiResponse.success(deliveryAlertAppService.summary(query));
    }

    @GetMapping("/filter-options")
    public ApiResponse<FilterOptionsVO> filterOptions() {
        DeliveryAlertQuery query = new DeliveryAlertQuery(
                null, null, null, null, null, null, null, null, null, null, 1, 1);
        return ApiResponse.success(deliveryAlertAppService.filterOptions(query));
    }

    @GetMapping("/last-sync-time")
    public ApiResponse<DeliveryAlertSyncTimeVO> lastSyncTime() {
        return ApiResponse.success(deliveryAlertAppService.lastSyncTime());
    }

    @GetMapping("/components")
    public ApiResponse<List<DeliveryAlertComponentVO>> components(
            @RequestParam(value = "groupKey") String groupKey) {
        return ApiResponse.success(deliveryAlertAppService.findComponents(groupKey));
    }

    @GetMapping("/{groupKey}")
    public ApiResponse<DeliveryAlertDetailVO> detail(@PathVariable String groupKey) {
        return ApiResponse.success(deliveryAlertAppService.findDetail(groupKey));
    }

    @GetMapping("/{groupKey}/components/{componentId}")
    public ApiResponse<DeliveryAlertDetailVO> componentDetail(
            @PathVariable String groupKey,
            @PathVariable Long componentId) {
        return ApiResponse.success(deliveryAlertAppService.findComponentDetail(groupKey, componentId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 供应商多选：空集合 / 全空白项归一为 null，避免拼出多余的 IN 条件。 */
    private static List<String> blankToNull(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> trimmed = values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
