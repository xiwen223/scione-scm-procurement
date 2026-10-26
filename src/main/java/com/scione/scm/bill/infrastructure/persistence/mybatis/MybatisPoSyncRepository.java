package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRecordItem;
import com.scione.scm.bill.domain.posync.PoSyncRepository;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.PoSyncRecordItemMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.PoSyncRecordMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PoSyncRecordItemPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PoSyncRecordPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 基于 MyBatis 的采购单同步仓储适配器。
 */
@Repository
@RequiredArgsConstructor
public class MybatisPoSyncRepository implements PoSyncRepository {

    private final PoSyncRecordMapper recordMapper;
    private final PoSyncRecordItemMapper itemMapper;
    private final DataSource dataSource;

    @Override
    public <T> T withSyncLock(Supplier<T> action) {
        // 专用连接只持有命名锁，不在网络请求期间开启数据库事务。
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement("SELECT GET_LOCK('contract_po_sync',40)");
                 var result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("采购单同步正在执行，本次未取得同步锁，进度不变");
                }
            }
            try {
                return action.get();
            } finally {
                try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK('contract_po_sync')");
                     var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new SQLException("采购单同步锁释放失败");
                    }
                } catch (SQLException ex) {
                    // 不把仍可能持锁的会话交回连接池。
                    connection.abort(Runnable::run);
                    throw ex;
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("采购单同步锁连接异常，停止本次同步", ex);
        }
    }

    @Override
    public Optional<LocalDateTime> findUpdatedUntil() {
        return new JdbcTemplate(dataSource).query(
                "SELECT updated_until FROM po_sync_state WHERE source_code='contract_po'",
                (result, row) -> result.getObject(1, LocalDateTime.class)).stream().findFirst();
    }

    @Override
    public void initializeUpdatedUntil(LocalDateTime initialBoundary) {
        new JdbcTemplate(dataSource).update(
                "INSERT INTO po_sync_state(source_code,updated_until) VALUES ('contract_po',?) "
                        + "ON DUPLICATE KEY UPDATE source_code=VALUES(source_code)", initialBoundary);
    }

    @Override
    public void advanceUpdatedUntil(LocalDateTime updatedUntil) {
        new JdbcTemplate(dataSource).update(
                "INSERT INTO po_sync_state(source_code,updated_until) VALUES ('contract_po',?) "
                        + "ON DUPLICATE KEY UPDATE updated_until=GREATEST(updated_until,VALUES(updated_until))",
                updatedUntil);
    }

    @Override
    @Transactional
    public void save(PoSyncRecord record) {
        // 1. 单头 upsert
        recordMapper.upsert(toPO(record));
        // 2. 明细
        // 替换整套 PO 明细前先删除旧记录；与单头 upsert 共用事务，插入失败会回滚本次删除。
        itemMapper.deleteByOrderNo(record.getPurchaseOrderNo());
        List<PoSyncRecordItem> items = record.getItems();
        if (items != null && !items.isEmpty()) {
            List<PoSyncRecordItemPO> itemPOs = items.stream().map(this::toPO).toList();
            itemMapper.batchInsert(itemPOs);
        }
    }

    @Override
    public List<PoSyncRecord> findPendingForContract() {
        List<PoSyncRecordPO> pos = recordMapper.selectPendingForContract();
        return pos.stream().map(this::toDomain).toList();
    }

    @Override
    public List<PoSyncRecord> findPendingForContractByOrderNos(List<String> purchaseOrderNos) {
        if (purchaseOrderNos == null || purchaseOrderNos.isEmpty()) {
            return List.of();
        }
        List<PoSyncRecordPO> pos = recordMapper.selectPendingForContractByOrderNos(purchaseOrderNos);
        return pos.stream().map(this::toDomain).toList();
    }

    @Override
    public void markContractCreated(String purchaseOrderNo, long contractId) {
        recordMapper.markContractCreated(purchaseOrderNo, contractId);
    }

    /**
     * PO 转领域对象（包含明细）。
     */
    private PoSyncRecord toDomain(PoSyncRecordPO po) {
        PoSyncRecord record = new PoSyncRecord();
        record.setId(po.getId());
        record.setPurchaseOrderNo(po.getPurchaseOrderNo());
        record.setCustomOrderSn(po.getCustomOrderSn());
        record.setSupplierId(po.getSupplierId());
        record.setSupplierName(po.getSupplierName());
        record.setSupplierPhone(po.getSupplierPhone());
        record.setContactPerson(po.getContactPerson());
        record.setPoStatus(po.getPoStatus());
        record.setPoStatusText(po.getPoStatusText());
        record.setStatusShipped(po.getStatusShipped());
        record.setStatusShippedText(po.getStatusShippedText());
        record.setAmountTotal(po.getAmountTotal());
        record.setTotalPrice(po.getTotalPrice());
        record.setQuantityTotal(po.getQuantityTotal());
        record.setWarehouseName(po.getWarehouseName());
        record.setRemark(po.getRemark());
        record.setPoOrderTime(po.getPoOrderTime());
        record.setPoCreateTime(po.getPoCreateTime());
        record.setPoUpdateTime(po.getPoUpdateTime());
        record.setSyncTime(po.getSyncTime());

        // 加载明细
        List<PoSyncRecordItemPO> itemPOs = itemMapper.selectByOrderNo(po.getPurchaseOrderNo());
        List<PoSyncRecordItem> items = itemPOs.stream().map(this::toItemDomain).toList();
        record.setItems(items);

        return record;
    }

    /**
     * 明细 PO 转领域对象。
     */
    private PoSyncRecordItem toItemDomain(PoSyncRecordItemPO po) {
        PoSyncRecordItem item = new PoSyncRecordItem();
        item.setId(po.getId());
        item.setPurchaseOrderNo(po.getPurchaseOrderNo());
        item.setLxItemId(po.getLxItemId());
        item.setPlanSn(po.getPlanSn());
        item.setProductId(po.getProductId());
        item.setProductName(po.getProductName());
        item.setSku(po.getSku());
        item.setFnsku(po.getFnsku());
        item.setModel(po.getModel());
        item.setUnitPrice(po.getUnitPrice());
        item.setUnitPriceWithoutTax(po.getUnitPriceWithoutTax());
        item.setAmount(po.getAmount());
        item.setQuantityPlan(po.getQuantityPlan());
        item.setQuantityReal(po.getQuantityReal());
        item.setQuantityReceive(po.getQuantityReceive());
        item.setTaxRate(po.getTaxRate());
        item.setSpu(po.getSpu());
        item.setSpuName(po.getSpuName());
        item.setWarehouseName(po.getWarehouseName());
        item.setExpectArriveTime(po.getExpectArriveTime());
        item.setRemark(po.getRemark());
        item.setAttributeJson(po.getAttributeJson());
        item.setSyncTime(po.getSyncTime());
        return item;
    }

    private PoSyncRecordPO toPO(PoSyncRecord r) {
        PoSyncRecordPO po = new PoSyncRecordPO();
        po.setPurchaseOrderNo(r.getPurchaseOrderNo());
        po.setCustomOrderSn(r.getCustomOrderSn());
        po.setSupplierId(r.getSupplierId());
        po.setSupplierName(r.getSupplierName());
        po.setSupplierPhone(r.getSupplierPhone());
        po.setContactPerson(r.getContactPerson());
        po.setPoStatus(r.getPoStatus());
        po.setPoStatusText(r.getPoStatusText());
        po.setStatusShipped(r.getStatusShipped());
        po.setStatusShippedText(r.getStatusShippedText());
        po.setAmountTotal(r.getAmountTotal());
        po.setTotalPrice(r.getTotalPrice());
        po.setQuantityTotal(r.getQuantityTotal());
        po.setWarehouseName(r.getWarehouseName());
        po.setRemark(r.getRemark());
        po.setPoOrderTime(r.getPoOrderTime());
        po.setPoCreateTime(r.getPoCreateTime());
        po.setPoUpdateTime(r.getPoUpdateTime());
        po.setSyncTime(r.getSyncTime());
        return po;
    }

    private PoSyncRecordItemPO toPO(PoSyncRecordItem i) {
        PoSyncRecordItemPO po = new PoSyncRecordItemPO();
        po.setPurchaseOrderNo(i.getPurchaseOrderNo());
        po.setLxItemId(i.getLxItemId());
        po.setPlanSn(i.getPlanSn());
        po.setProductId(i.getProductId());
        po.setProductName(i.getProductName());
        po.setSku(i.getSku());
        po.setFnsku(i.getFnsku());
        po.setModel(i.getModel());
        po.setUnitPrice(i.getUnitPrice());
        po.setUnitPriceWithoutTax(i.getUnitPriceWithoutTax());
        po.setAmount(i.getAmount());
        po.setQuantityPlan(i.getQuantityPlan());
        po.setQuantityReal(i.getQuantityReal());
        po.setQuantityReceive(i.getQuantityReceive());
        po.setTaxRate(i.getTaxRate());
        po.setSpu(i.getSpu());
        po.setSpuName(i.getSpuName());
        po.setWarehouseName(i.getWarehouseName());
        po.setExpectArriveTime(i.getExpectArriveTime());
        po.setRemark(i.getRemark());
        po.setAttributeJson(i.getAttributeJson());
        po.setSyncTime(i.getSyncTime());
        return po;
    }

    @Override
    public Optional<PoSyncRecord> findByPurchaseOrderNo(String purchaseOrderNo) {
        PoSyncRecordPO po = recordMapper.selectByPurchaseOrderNo(purchaseOrderNo);  // ← 改成 recordMapper
        if (po == null) {
            return Optional.empty();
        }

        // 查询明细
        List<PoSyncRecordItemPO> itemPOs = itemMapper.selectByOrderNo(purchaseOrderNo);  // ← 改成 itemMapper

        // 组装领域对象
        PoSyncRecord record = toDomain(po);
        List<PoSyncRecordItem> items = itemPOs.stream()
                .map(this::toItemDomain)
                .toList();
        record.setItems(items);

        return Optional.of(record);
    }
}
