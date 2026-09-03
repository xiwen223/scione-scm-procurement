package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertComponentPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertDetailPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertSummaryPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryBusinessLogPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 采购交付预警看板 Mapper。
 */
public interface DeliveryAlertMapper {

    long count(@Param("startDate") LocalDate startDate,
               @Param("keyword") String keyword,
               @Param("supplier") String supplier,
               @Param("buyer") String buyer,
               @Param("warehouse") String warehouse,
               @Param("type") String type,
               @Param("riskLevel") String riskLevel,
               @Param("view") String view);

    List<DeliveryAlertPO> findPage(@Param("startDate") LocalDate startDate,
                                   @Param("keyword") String keyword,
                                   @Param("supplier") String supplier,
                                   @Param("buyer") String buyer,
                                   @Param("warehouse") String warehouse,
                                   @Param("type") String type,
                                   @Param("riskLevel") String riskLevel,
                                   @Param("view") String view,
                                   @Param("offset") int offset,
                                   @Param("pageSize") int pageSize);

    DeliveryAlertSummaryPO summary(@Param("startDate") LocalDate startDate,
                                   @Param("keyword") String keyword,
                                   @Param("supplier") String supplier,
                                   @Param("buyer") String buyer,
                                   @Param("warehouse") String warehouse);

    List<String> findDistinctSuppliers(@Param("startDate") LocalDate startDate);

    List<String> findDistinctBuyers(@Param("startDate") LocalDate startDate);

    List<String> findDistinctWarehouses(@Param("startDate") LocalDate startDate);

    List<DeliveryAlertComponentPO> findComponents(@Param("groupKey") String groupKey);

    DeliveryAlertDetailPO findDetailMeta(@Param("groupKey") String groupKey);

    DeliveryAlertComponentPO findComponent(@Param("groupKey") String groupKey,
                                            @Param("componentId") Long componentId);

    List<DeliveryBusinessLogPO> findBusinessLogsByGroupKey(@Param("groupKey") String groupKey);

    List<DeliveryBusinessLogPO> findBusinessLogsByOrderSn(@Param("orderSn") String orderSn);
}
