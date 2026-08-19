package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ShippingMarkDetailPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 箱唛明细表 Mapper。
 */
public interface ShippingMarkDetailMapper {

    int insert(ShippingMarkDetailPO shippingMarkDetail);

    int update(ShippingMarkDetailPO shippingMarkDetail);

    Optional<ShippingMarkDetailPO> findById(@Param("id") Long id);

    List<ShippingMarkDetailPO> findByBillNo(@Param("billNo") String billNo);
}
