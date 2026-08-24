package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ShippingMarkPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 箱唛主表 Mapper。
 */
public interface ShippingMarkMapper {

    int insert(ShippingMarkPO shippingMark);

    int update(ShippingMarkPO shippingMark);

    Optional<ShippingMarkPO> findById(@Param("id") Long id);

    Optional<ShippingMarkPO> findByBillNo(@Param("billNo") String billNo);

    long count(@Param("billNo") String billNo, @Param("billName") String billName,
               @Param("creator") String creator, @Param("statuses") List<Integer> statuses);

    List<ShippingMarkPO> findPage(@Param("billNo") String billNo, @Param("billName") String billName,
                                  @Param("creator") String creator, @Param("statuses") List<Integer> statuses,
                                  @Param("offset") int offset, @Param("pageSize") int pageSize);

    List<ShippingMarkPO> findAll();

    List<String> findDistinctCreators();
}
