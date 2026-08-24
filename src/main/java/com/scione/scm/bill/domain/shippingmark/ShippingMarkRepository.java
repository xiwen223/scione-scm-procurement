package com.scione.scm.bill.domain.shippingmark;

import java.util.List;
import java.util.Optional;

/**
 * 箱唛仓储端口，由基础设施层实现。
 */
public interface ShippingMarkRepository {

    void save(ShippingMark mark);

    void update(ShippingMark mark);

    void updateDetail(ShippingMarkDetail detail);

    Optional<ShippingMark> findById(Long id);

    Optional<ShippingMark> findByBillNo(String billNo);

    Optional<ShippingMarkDetail> findDetailById(Long detailId);

    List<ShippingMarkDetail> findDetailsByIds(List<Long> detailIds);

    ShippingMarkPage findPage(ShippingMarkQuery query);

    List<ShippingMark> findAll();

    List<String> findDistinctCreators();
}
