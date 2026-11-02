package com.scione.scm.bill.application;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertQuery;
import com.scione.scm.bill.infrastructure.excel.DeliveryAlertExportWorkbook;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertExportRowPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跟单预警看板导出编排：从每日快照筛出主事项范围，再从 lx_ 前缀表实时铺平采购单+SKU 明细。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryAlertExportService {

    /** 明细行上限，避免一次导出占满内存。 */
    private static final int ROW_LIMIT = 100_000;

    private final DeliveryAlertSnapshotService snapshotService;
    private final DeliveryAlertExportWorkbook workbook;

    public byte[] export(DeliveryAlertQuery query) {
        long begin = System.currentTimeMillis();
        Map<String, String> typeByGroup = snapshotService.findGroupTypes(query);
        long groupsDone = System.currentTimeMillis();

        List<DeliveryAlertExportRowPO> allRows = snapshotService.findExportRows(ROW_LIMIT + 1);
        if (allRows.size() > ROW_LIMIT) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "导出明细超过" + ROW_LIMIT + "条，请缩小筛选范围后重试");
        }
        List<DeliveryAlertExportRowPO> rows = mergeRows(allRows, typeByGroup);
        rows.sort(rowOrder());
        long rowsDone = System.currentTimeMillis();

        byte[] content = workbook.write(rows, typeByGroup);
        long finished = System.currentTimeMillis();
        log.info("跟单预警看板导出完成：主事项范围读快照表 groups={} 取数={}ms，明细实时读 lx_ 前缀表 取数={}ms、"
                        + "合并后 rows={}，生成Excel={}ms 总计={}ms 文件={}KB",
                typeByGroup.size(), groupsDone - begin, rowsDone - groupsDone, rows.size(),
                finished - rowsDone, finished - begin, content.length / 1024);
        return content;
    }

    /**
     * 只保留筛选命中的主事项；同一采购明细可能落在多个主事项下，先按明细去重，
     * 再按采购单+SKU 合并数量，保证每行唯一且数量不重复累加。
     */
    private static List<DeliveryAlertExportRowPO> mergeRows(List<DeliveryAlertExportRowPO> allRows,
                                                            Map<String, String> typeByGroup) {
        Map<Long, DeliveryAlertExportRowPO> componentRows = distinctByComponent(allRows, typeByGroup);
        Map<String, DeliveryAlertExportRowPO> uniqueRows = new LinkedHashMap<>();
        for (DeliveryAlertExportRowPO row : componentRows.values()) {
            String key = row.getOrderSn() + "\u0000" + row.getSku();
            DeliveryAlertExportRowPO existing = uniqueRows.get(key);
            if (existing == null) {
                uniqueRows.put(key, row);
                continue;
            }
            existing.setQtyReady(existing.getQtyReady() + row.getQtyReady());
            existing.setQtyPlan(existing.getQtyPlan() + row.getQtyPlan());
            if (existing.getPlanSn() == null) {
                existing.setPlanSn(row.getPlanSn());
            }
            if (existing.getProductName() == null) {
                existing.setProductName(row.getProductName());
            }
        }
        return new ArrayList<>(uniqueRows.values());
    }

    /** 同一采购明细在多个主事项下各有一行，取第一条即可，避免数量重复。 */
    private static Map<Long, DeliveryAlertExportRowPO> distinctByComponent(List<DeliveryAlertExportRowPO> allRows,
                                                                           Map<String, String> typeByGroup) {
        Map<Long, DeliveryAlertExportRowPO> componentRows = new LinkedHashMap<>();
        for (DeliveryAlertExportRowPO row : allRows) {
            if (!typeByGroup.containsKey(row.getGroupKey())) {
                continue;
            }
            componentRows.putIfAbsent(row.getComponentId(), row);
        }
        return componentRows;
    }

    /** 按采购单创建日、采购单、SKU 排序，保证导出结果稳定。 */
    private static Comparator<DeliveryAlertExportRowPO> rowOrder() {
        Comparator<String> textOrder = Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(DeliveryAlertExportRowPO::getCreatedTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DeliveryAlertExportRowPO::getOrderSn, textOrder)
                .thenComparing(DeliveryAlertExportRowPO::getSku, textOrder);
    }
}
