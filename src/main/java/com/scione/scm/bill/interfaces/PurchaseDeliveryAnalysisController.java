package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.PurchaseDeliveryAnalysisAppService;
import com.scione.scm.bill.application.dto.PurchaseDeliveryFilterOptionsDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryOrderDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryOrderDetailDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryOrderSkuLineDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryPlanDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryPlanDetailDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliverySummaryDTO;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryAnalysisQuery;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/** 采购交付流程分析看板查询接口。 */
@RestController
@Validated
@RequestMapping("/api/v1/purchase-delivery-analysis")
@RequiredArgsConstructor
@Tag(name = "采购交付流程分析看板", description = "PP 到 PO、CR、有效 IB 的只读流程分析")
public class PurchaseDeliveryAnalysisController {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> NODES = Set.of(
            "PP_CREATE", "PP_APPROVAL", "PO_CREATE", "PO_APPROVAL", "SUPPLIER_SENT",
            "FINANCE_PAYMENT", "CR_RECEIPT", "IB_CREATE", "COMPLETED");
    private static final Set<String> DELIVERY_STATUSES = Set.of(
            "PENDING", "IN_TRANSIT", "IN_TRANSIT_OVERDUE",
            "COMPLETED_WITHIN_SLA", "COMPLETED_OVER_SLA");
    private static final Set<String> RISKS = Set.of("order", "delivery");
    private static final Set<String> SORT_FIELDS = Set.of(
            "createdAt", "orderLeadHours", "maxDeliveryHours", "achievementRate",
            "orderRisk", "deliveryRisk", "overdueFirst");
    private static final Set<String> SORT_ORDERS = Set.of("asc", "desc");

    private final PurchaseDeliveryAnalysisAppService appService;

    @GetMapping
    public ApiResponse<PageResult<PurchaseDeliveryPlanDTO>> list(
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Size(max = 32) String node,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdEnd,
            @RequestParam(required = false) @Size(max = 100) String buyer,
            @RequestParam(required = false) @Size(max = 100) String supplier,
            @RequestParam(required = false) @Size(max = 100) String warehouse,
            @RequestParam(required = false) @Size(max = 32) String deliveryStatus,
            @RequestParam(required = false) @Size(max = 16) String risk,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "asc") String sortOrder,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        PurchaseDeliveryAnalysisQuery query = query(createdStart, createdEnd, keyword, node, buyer, supplier,
                warehouse, deliveryStatus, risk, sortBy, sortOrder, pageNum, pageSize);
        return ApiResponse.success(appService.findPage(query));
    }

    @GetMapping("/summary")
    public ApiResponse<PurchaseDeliverySummaryDTO> summary(
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdEnd,
            @RequestParam(required = false) @Size(max = 100) String buyer,
            @RequestParam(required = false) @Size(max = 100) String supplier,
            @RequestParam(required = false) @Size(max = 100) String warehouse,
            @RequestParam(required = false) @Size(max = 32) String deliveryStatus) {
        PurchaseDeliveryAnalysisQuery query = query(createdStart, createdEnd, keyword, null, buyer, supplier,
                warehouse, deliveryStatus, null, "createdAt", "desc", 1, 1);
        return ApiResponse.success(appService.summary(query));
    }

    @GetMapping("/filter-options")
    public ApiResponse<PurchaseDeliveryFilterOptionsDTO> filterOptions() {
        PurchaseDeliveryAnalysisQuery query = query(null, null, null, null, null, null, null,
                null, null, "createdAt", "desc", 1, 1);
        return ApiResponse.success(appService.filterOptions(query));
    }

    @GetMapping("/{planSn}/orders")
    public ApiResponse<List<PurchaseDeliveryOrderDTO>> orders(
            @PathVariable @NotBlank @Size(max = 64) String planSn,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Size(max = 100) String buyer,
            @RequestParam(required = false) @Size(max = 100) String supplier,
            @RequestParam(required = false) @Size(max = 100) String warehouse,
            @RequestParam(required = false) @Size(max = 32) String deliveryStatus) {
        PurchaseDeliveryAnalysisQuery query = query(null, null, keyword, null, buyer, supplier,
                warehouse, deliveryStatus, null, "createdAt", "asc", 1, 100);
        return ApiResponse.success(appService.findOrders(planSn.trim(), query));
    }

    @GetMapping("/{planSn}/order-sku-lines")
    public ApiResponse<List<PurchaseDeliveryOrderSkuLineDTO>> orderSkuLines(
            @PathVariable @NotBlank @Size(max = 64) String planSn,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Size(max = 100) String buyer,
            @RequestParam(required = false) @Size(max = 100) String supplier,
            @RequestParam(required = false) @Size(max = 100) String warehouse,
            @RequestParam(required = false) @Size(max = 32) String deliveryStatus) {
        PurchaseDeliveryAnalysisQuery query = query(null, null, keyword, null, buyer, supplier,
                warehouse, deliveryStatus, null, "createdAt", "asc", 1, 100);
        return ApiResponse.success(appService.findOrderSkuLines(planSn.trim(), query));
    }

    @GetMapping("/{planSn}")
    public ApiResponse<PurchaseDeliveryPlanDetailDTO> planDetail(
            @PathVariable @NotBlank @Size(max = 64) String planSn) {
        return ApiResponse.success(appService.findPlanDetail(planSn.trim(), emptyQuery()));
    }

    @GetMapping("/orders/{orderSn}")
    public ApiResponse<PurchaseDeliveryOrderDetailDTO> orderDetail(
            @PathVariable @NotBlank @Size(max = 64) String orderSn) {
        return ApiResponse.success(appService.findOrderDetail(orderSn.trim(), emptyQuery()));
    }

    private PurchaseDeliveryAnalysisQuery emptyQuery() {
        return query(null, null, null, null, null, null, null, null, null,
                "createdAt", "desc", 1, 100);
    }

    private PurchaseDeliveryAnalysisQuery query(
            LocalDate createdStart, LocalDate createdEnd,
            String keyword, String node, String buyer, String supplier, String warehouse,
            String deliveryStatus, String risk, String sortBy, String sortOrder,
            int pageNum, int pageSize) {
        String normalizedNode = blankToNull(node);
        String normalizedDeliveryStatus = blankToNull(deliveryStatus);
        String normalizedRisk = blankToNull(risk);
        String normalizedSortBy = defaultIfBlank(sortBy, "createdAt");
        String normalizedSortOrder = defaultIfBlank(sortOrder, "asc").toLowerCase();
        if (createdStart != null && createdEnd != null && createdStart.isAfter(createdEnd)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "createdStart 不能晚于 createdEnd");
        }
        validateWhitelist("node", normalizedNode, NODES);
        validateWhitelist("deliveryStatus", normalizedDeliveryStatus, DELIVERY_STATUSES);
        validateWhitelist("risk", normalizedRisk, RISKS);
        validateWhitelist("sortBy", normalizedSortBy, SORT_FIELDS);
        validateWhitelist("sortOrder", normalizedSortOrder, SORT_ORDERS);
        return new PurchaseDeliveryAnalysisQuery(
                LocalDateTime.now(REPORT_ZONE), createdStart, createdEnd, blankToNull(keyword), normalizedNode,
                blankToNull(buyer), blankToNull(supplier), blankToNull(warehouse), normalizedDeliveryStatus,
                normalizedRisk, normalizedSortBy, normalizedSortOrder, pageNum, pageSize);
    }

    private static void validateWhitelist(String name, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, name + " 参数不支持: " + value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }
}
