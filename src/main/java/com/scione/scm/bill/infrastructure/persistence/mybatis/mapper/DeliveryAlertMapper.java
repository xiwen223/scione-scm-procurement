package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertComponentPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertDetailPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertExportRowPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertSnapshotPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertSnapshotSummaryPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertSummaryPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryBusinessLogPO;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购交付预警看板 Mapper。
 */
public interface DeliveryAlertMapper {

    List<DeliveryFollowUp.Key> findFollowUpKeys(
            @Param("keys") List<DeliveryFollowUp.Key> keys,
            @Param("startDate") LocalDate startDate);

    DeliveryFollowUp lockFollowUp(@Param("key") DeliveryFollowUp.Key key);

    int saveFollowUp(@Param("row") DeliveryFollowUp row,
                     @Param("operator") String operator);

    List<DeliveryFollowUp> findFollowUps(
            @Param("groupKeys") List<String> groupKeys);

    long count(@Param("startDate") LocalDate startDate,
               @Param("keyword") String keyword,
               @Param("types") List<String> types,
               @Param("suppliers") List<String> suppliers,
               @Param("buyers") List<String> buyers,
               @Param("warehouses") List<String> warehouses,
               @Param("followers") List<String> followers,
               @Param("riskLevels") List<String> riskLevels,
               @Param("view") String view,
               @Param("createDateFrom") LocalDate createDateFrom,
               @Param("createDateTo") LocalDate createDateTo);

    List<DeliveryAlertPO> findPage(@Param("startDate") LocalDate startDate,
                                   @Param("keyword") String keyword,
                                   @Param("types") List<String> types,
                                   @Param("suppliers") List<String> suppliers,
                                   @Param("buyers") List<String> buyers,
                                   @Param("warehouses") List<String> warehouses,
                                   @Param("followers") List<String> followers,
                                   @Param("riskLevels") List<String> riskLevels,
                                   @Param("view") String view,
                                   @Param("createDateFrom") LocalDate createDateFrom,
                                   @Param("createDateTo") LocalDate createDateTo,
                                   @Param("offset") int offset,
                                   @Param("pageSize") int pageSize);

    /** 按列表同一套条件读取全部主事项，limit 为服务端安全上限。快照刷新时使用。 */
    List<DeliveryAlertPO> findForExport(@Param("startDate") LocalDate startDate,
                                        @Param("keyword") String keyword,
                                        @Param("types") List<String> types,
                                        @Param("suppliers") List<String> suppliers,
                                        @Param("buyers") List<String> buyers,
                                        @Param("warehouses") List<String> warehouses,
                                        @Param("followers") List<String> followers,
                                        @Param("riskLevels") List<String> riskLevels,
                                        @Param("view") String view,
                                        @Param("createDateFrom") LocalDate createDateFrom,
                                        @Param("createDateTo") LocalDate createDateTo,
                                        @Param("limit") int limit);

    /** 导出的扁平行：明细实时读 lx_ 前缀表，limit 为服务端安全上限。 */
    List<DeliveryAlertExportRowPO> findExportRows(@Param("startDate") LocalDate startDate,
                                                 @Param("limit") int limit);

    DeliveryAlertSummaryPO summary(@Param("startDate") LocalDate startDate,
                                   @Param("keyword") String keyword,
                                   @Param("types") List<String> types,
                                   @Param("suppliers") List<String> suppliers,
                                   @Param("buyers") List<String> buyers,
                                   @Param("warehouses") List<String> warehouses,
               @Param("followers") List<String> followers,
                                   @Param("riskLevels") List<String> riskLevels,
                                   @Param("createDateFrom") LocalDate createDateFrom,
                                   @Param("createDateTo") LocalDate createDateTo);

    LocalDateTime findLastOrderSyncTime();

    List<String> findDistinctSuppliers(@Param("startDate") LocalDate startDate);

    List<String> findDistinctBuyers(@Param("startDate") LocalDate startDate);

    List<String> findDistinctWarehouses(@Param("startDate") LocalDate startDate);

    List<String> findDistinctFollowers(@Param("startDate") LocalDate startDate);

    List<DeliveryAlertComponentPO> findComponents(@Param("groupKey") String groupKey);

    DeliveryAlertDetailPO findDetailMeta(@Param("groupKey") String groupKey);

    DeliveryAlertComponentPO findComponent(@Param("groupKey") String groupKey,
                                            @Param("componentId") Long componentId);

    List<DeliveryBusinessLogPO> findBusinessLogsByGroupKey(@Param("groupKey") String groupKey);

    List<DeliveryBusinessLogPO> findBusinessLogsByOrderSn(@Param("orderSn") String orderSn);

    // ==================== 每日快照 ====================

    long countSnapshot(@Param("keyword") String keyword,
                       @Param("types") List<String> types,
                       @Param("suppliers") List<String> suppliers,
                       @Param("buyers") List<String> buyers,
                       @Param("warehouses") List<String> warehouses,
                       @Param("followers") List<String> followers,
                       @Param("riskLevels") List<String> riskLevels,
                       @Param("view") String view,
                       @Param("createDateFrom") LocalDate createDateFrom,
                       @Param("createDateTo") LocalDate createDateTo);

    List<DeliveryAlertSnapshotPO> findSnapshotPage(@Param("keyword") String keyword,
                                                   @Param("types") List<String> types,
                                                   @Param("suppliers") List<String> suppliers,
                                                   @Param("buyers") List<String> buyers,
                                                   @Param("warehouses") List<String> warehouses,
                                                   @Param("followers") List<String> followers,
                                                   @Param("riskLevels") List<String> riskLevels,
                                                   @Param("view") String view,
                                                   @Param("createDateFrom") LocalDate createDateFrom,
                                                   @Param("createDateTo") LocalDate createDateTo,
                                                   @Param("offset") int offset,
                                                   @Param("pageSize") int pageSize);

    DeliveryAlertSnapshotSummaryPO snapshotSummary(@Param("keyword") String keyword,
                                                   @Param("types") List<String> types,
                                                   @Param("suppliers") List<String> suppliers,
                                                   @Param("buyers") List<String> buyers,
                                                   @Param("warehouses") List<String> warehouses,
                                                   @Param("followers") List<String> followers,
                                                   @Param("riskLevels") List<String> riskLevels,
                                                   @Param("createDateFrom") LocalDate createDateFrom,
                                                   @Param("createDateTo") LocalDate createDateTo);

    List<DeliveryAlertSnapshotPO> findSnapshotOptions();

    List<DeliveryAlertSnapshotPO> findSnapshotGroupTypes(@Param("keyword") String keyword,
                                                         @Param("types") List<String> types,
                                                         @Param("suppliers") List<String> suppliers,
                                                         @Param("buyers") List<String> buyers,
                                                         @Param("warehouses") List<String> warehouses,
                                                         @Param("followers") List<String> followers,
                                                         @Param("riskLevels") List<String> riskLevels,
                                                         @Param("view") String view,
                                                         @Param("createDateFrom") LocalDate createDateFrom,
                                                         @Param("createDateTo") LocalDate createDateTo,
                                                         @Param("limit") int limit);

    LocalDateTime findSnapshotAt();

    int deleteSnapshot();

    int insertSnapshotBatch(@Param("rows") List<DeliveryAlertSnapshotPO> rows);
}
