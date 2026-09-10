package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.DeliveryAlertAppService;
import com.scione.scm.bill.application.dto.DeliveryAlertComponentDTO;
import com.scione.scm.bill.application.dto.DeliveryAlertDetailDTO;
import com.scione.scm.bill.application.dto.DeliveryAlertListItemDTO;
import com.scione.scm.bill.application.dto.DeliveryAlertSummaryDTO;
import com.scione.scm.bill.application.dto.DeliveryAlertSyncTimeDTO;
import com.scione.scm.bill.application.dto.FilterOptionsDTO;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertQuery;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping
    public ApiResponse<PageResult<DeliveryAlertListItemDTO>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "supplier", required = false) String supplier,
            @RequestParam(value = "buyer", required = false) String buyer,
            @RequestParam(value = "warehouse", required = false) String warehouse,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于 0") int pageNum,
            @RequestParam(defaultValue = "6") @Min(value = 1, message = "每页条数必须大于 0")
            @Max(value = 100, message = "每页条数不能超过 100") int pageSize) {
        DeliveryAlertQuery query = new DeliveryAlertQuery(
                null, blankToNull(keyword), blankToNull(type), blankToNull(supplier),
                blankToNull(buyer), blankToNull(warehouse), blankToNull(riskLevel), blankToNull(view), pageNum, pageSize);
        return ApiResponse.success(deliveryAlertAppService.findPage(query));
    }

    @GetMapping("/summary")
    public ApiResponse<DeliveryAlertSummaryDTO> summary(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "supplier", required = false) String supplier,
            @RequestParam(value = "buyer", required = false) String buyer,
            @RequestParam(value = "warehouse", required = false) String warehouse) {
        DeliveryAlertQuery query = new DeliveryAlertQuery(
                null, blankToNull(keyword), null, blankToNull(supplier),
                blankToNull(buyer), blankToNull(warehouse), null, null, 1, 1);
        return ApiResponse.success(deliveryAlertAppService.summary(query));
    }

    @GetMapping("/filter-options")
    public ApiResponse<FilterOptionsDTO> filterOptions() {
        DeliveryAlertQuery query = new DeliveryAlertQuery(
                null, null, null, null, null, null, null, null, 1, 1);
        return ApiResponse.success(deliveryAlertAppService.filterOptions(query));
    }

    @GetMapping("/last-sync-time")
    public ApiResponse<DeliveryAlertSyncTimeDTO> lastSyncTime() {
        return ApiResponse.success(deliveryAlertAppService.lastSyncTime());
    }

    @GetMapping("/components")
    public ApiResponse<List<DeliveryAlertComponentDTO>> components(
            @RequestParam(value = "groupKey") String groupKey) {
        return ApiResponse.success(deliveryAlertAppService.findComponents(groupKey));
    }

    @GetMapping("/{groupKey}")
    public ApiResponse<DeliveryAlertDetailDTO> detail(@PathVariable String groupKey) {
        return ApiResponse.success(deliveryAlertAppService.findDetail(groupKey));
    }

    @GetMapping("/{groupKey}/components/{componentId}")
    public ApiResponse<DeliveryAlertDetailDTO> componentDetail(
            @PathVariable String groupKey,
            @PathVariable Long componentId) {
        return ApiResponse.success(deliveryAlertAppService.findComponentDetail(groupKey, componentId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
