package com.scione.scm.bill.application;

import com.scione.scm.bill.application.port.LingxingPurchaseOrderClient;
import com.scione.scm.bill.application.port.LingxingPurchaseOrderClient.PurchaseOrderData;
import com.scione.scm.bill.application.port.LingxingPurchaseOrderClient.PurchaseOrderItemData;
import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRecordItem;
import com.scione.scm.bill.domain.posync.PoSyncRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购单同步应用服务：定时从领星拉取变动采购单并落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoSyncAppService {

    /** 时间搜索维度：按更新时间拉取（新增单创建时也会写 update_time，故一次覆盖新增+变更）。 */
    private static final String SEARCH_FIELD_UPDATE_TIME = "update_time";

    /** 拉取窗口分钟数：调度周期 5 分钟 + 1 分钟重叠冗余，靠 upsert 幂等兜底。 */
    private static final int PULL_WINDOW_MINUTES = 6;

    private final LingxingPurchaseOrderClient purchaseOrderClient;
    private final PoSyncRepository poSyncRepository;
    private final ContractAutoCreateService contractAutoCreateService;

    /**
     * 拉取最近窗口内变动的采购单并落库。
     *
     * @return 本次同步汇总
     */
    public SyncResult pullAndSync() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = now.minusMinutes(PULL_WINDOW_MINUTES);
        return pullAndSync(startTime, now);
    }

    /**
     * 拉取指定时间窗口内变动的采购单并落库（供手动触发按自定义区间回补）。
     */
    public SyncResult pullAndSync(LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime syncTime = LocalDateTime.now();
        log.info("采购单同步开始，窗口 [{} ~ {}]，维度={}", startTime, endTime, SEARCH_FIELD_UPDATE_TIME);

        List<PurchaseOrderData> orders;
        try {
            orders = purchaseOrderClient.fetchPurchaseOrders(startTime, endTime, SEARCH_FIELD_UPDATE_TIME);
        } catch (RuntimeException ex) {
            // 拉取本身失败（网络/签名/限流等）：整批放弃，记错误日志，不抛给调度器让它反复报警
            log.error("采购单拉取失败，窗口 [{} ~ {}]", startTime, endTime, ex);
            return new SyncResult(0, 0, 0);
        }

        int success = 0;
        int failed = 0;
        List<String> successOrderNos = new ArrayList<>();  // ← 新增这一行

        for (PurchaseOrderData order : orders) {
            String orderSn = order.orderSn();
            if (!StringUtils.hasText(orderSn)) {
                // 采购单号是唯一键、非空，缺了无法落库，只能跳过
                failed++;
                log.warn("跳过无采购单号的记录：{}", order);
                continue;
            }
            try {
                poSyncRepository.save(toRecord(order, syncTime));
                success++;
                successOrderNos.add(orderSn);  // ← 新增这一行
            } catch (RuntimeException ex) {
                failed++;
                log.error("采购单落库失败，orderSn={}", orderSn, ex);
            }
        }

        SyncResult result = new SyncResult(orders.size(), success, failed);
        log.info("采购单同步结束：拉取 {}，成功 {}，失败 {}", result.total(), result.success(), result.failed());

        if (!successOrderNos.isEmpty()) {
            try {
                log.info("采购单同步成功 {} 条，开始为这些 PO 自动创建合同", successOrderNos.size());
                ContractAutoCreateService.AutoCreateResult contractResult =
                        contractAutoCreateService.autoCreate(successOrderNos);
                log.info("合同自动创建完成：拉取 {}，创建 {}，跳过 {}，失败 {}",
                        contractResult.total(),
                        contractResult.created(),
                        contractResult.skipped(),
                        contractResult.failed());
            } catch (Exception ex) {
                log.error("合同自动创建失败（不影响 PO 同步）", ex);
            }
        }

        return result;
    }

    private PoSyncRecord toRecord(PurchaseOrderData order, LocalDateTime syncTime) {
        PoSyncRecord record = new PoSyncRecord();
        record.setPurchaseOrderNo(order.orderSn());
        record.setCustomOrderSn(order.customOrderSn());
        record.setSupplierId(order.supplierId());
        record.setSupplierName(order.supplierName());
        record.setSupplierPhone(order.contactNumber());   // contactNumber → supplierPhone
        record.setContactPerson(order.contactPerson());
        record.setPoStatus(order.status());
        record.setPoStatusText(order.statusText());
        record.setStatusShipped(order.statusShipped());
        record.setStatusShippedText(order.statusShippedText());
        record.setAmountTotal(order.amountTotal());
        record.setTotalPrice(order.totalPrice());
        record.setQuantityTotal(order.quantityTotal());
        record.setWarehouseName(order.warehouseName());
        record.setRemark(order.remark());
        record.setPoOrderTime(order.orderTime());
        record.setPoCreateTime(order.createTime());
        record.setPoUpdateTime(order.updateTime());
        // hasContract / contractId 不设置：由建合同流程维护，同步不碰（upsert 也不更新这两列）
        record.setSyncTime(syncTime);

        List<PurchaseOrderItemData> items = order.items();
        if (items != null) {
            List<PoSyncRecordItem> recordItems = new ArrayList<>(items.size());
            for (PurchaseOrderItemData item : items) {
                recordItems.add(toItem(order.orderSn(), item, syncTime));
            }
            record.setItems(recordItems);
        }
        return record;
    }

    private PoSyncRecordItem toItem(String orderSn, PurchaseOrderItemData item, LocalDateTime syncTime) {
        PoSyncRecordItem po = new PoSyncRecordItem();
        po.setPurchaseOrderNo(orderSn);
        po.setLxItemId(item.lxItemId());
        po.setPlanSn(item.planSn());
        po.setProductId(item.productId());
        po.setProductName(item.productName());
        po.setSku(item.sku());
        po.setFnsku(item.fnsku());
        po.setModel(item.model());
        po.setUnitPrice(item.price());   // price → unitPrice
        po.setAmount(item.amount());
        po.setQuantityPlan(item.quantityPlan());
        po.setQuantityReal(item.quantityReal());
        po.setQuantityReceive(item.quantityReceive());
        po.setTaxRate(item.taxRate());
        po.setSpu(item.spu());
        po.setSpuName(item.spuName());
        po.setWarehouseName(item.warehouseName());
        po.setExpectArriveTime(item.expectArriveTime());
        po.setRemark(item.remark());
        po.setAttributeJson(item.attributeJson());
        po.setSyncTime(syncTime);
        return po;
    }

    /** 单次同步汇总。 */
    public record SyncResult(int total, int success, int failed) {
    }
}