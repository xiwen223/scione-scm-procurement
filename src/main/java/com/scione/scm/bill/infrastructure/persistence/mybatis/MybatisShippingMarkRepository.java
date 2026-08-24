package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.scione.scm.bill.domain.shippingmark.*;
import com.scione.scm.bill.domain.shippingmark.enums.MarkStatus;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ShippingMarkDetailMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ShippingMarkMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ShippingMarkDetailPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ShippingMarkPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis 的箱唛仓储适配器。
 */
@Repository
@RequiredArgsConstructor
public class MybatisShippingMarkRepository implements ShippingMarkRepository {

    private final ShippingMarkMapper shippingMarkMapper;
    private final ShippingMarkDetailMapper shippingMarkDetailMapper;

    @Override
    @Transactional
    public void save(ShippingMark mark) {
        if (mark.getId() != null) {
            throw new IllegalArgumentException("已存在单据不能再次创建");
        }
        ShippingMarkPO markPO = toPO(mark);
        shippingMarkMapper.insert(markPO);
        mark.setId(markPO.getId());
        for (ShippingMarkDetail detail : mark.getDetails()) {
            ShippingMarkDetailPO detailPO = toPO(detail);
            shippingMarkDetailMapper.insert(detailPO);
            detail.setId(detailPO.getId());
        }
    }

    @Override
    @Transactional
    public void update(ShippingMark mark) {
        shippingMarkMapper.update(toPO(mark));
    }

    @Override
    @Transactional
    public void updateDetail(ShippingMarkDetail detail) {
        shippingMarkDetailMapper.update(toPO(detail));
    }

    @Override
    public Optional<ShippingMark> findById(Long id) {
        return shippingMarkMapper.findById(id)
                .map(mark -> toDomain(mark, shippingMarkDetailMapper.findByBillNo(mark.getBillNo())));
    }

    @Override
    public Optional<ShippingMark> findByBillNo(String billNo) {
        return shippingMarkMapper.findByBillNo(billNo)
                .map(mark -> toDomain(mark, shippingMarkDetailMapper.findByBillNo(billNo)));
    }

    @Override
    public Optional<ShippingMarkDetail> findDetailById(Long detailId) {
        return shippingMarkDetailMapper.findById(detailId).map(this::toDomain);
    }

    @Override
    public List<ShippingMarkDetail> findDetailsByIds(List<Long> detailIds) {
        if (detailIds.isEmpty()) {
            return List.of();
        }
        return shippingMarkDetailMapper.findByIds(detailIds).stream().map(this::toDomain).toList();
    }

    @Override
    public ShippingMarkPage findPage(ShippingMarkQuery query) {
        List<Integer> statuses = query.statuses().stream().map(MarkStatus::getCode).toList();
        long total = shippingMarkMapper.count(query.billNo(), query.billName(), query.creator(), statuses);
        List<ShippingMark> records = shippingMarkMapper.findPage(
                        query.billNo(), query.billName(), query.creator(), statuses, query.offset(), query.pageSize())
                .stream()
                .map(mark -> toDomain(mark, shippingMarkDetailMapper.findByBillNo(mark.getBillNo())))
                .toList();
        return new ShippingMarkPage(total, records);
    }

    @Override
    public List<ShippingMark> findAll() {
        return shippingMarkMapper.findAll().stream()
                .map(mark -> toDomain(mark, shippingMarkDetailMapper.findByBillNo(mark.getBillNo())))
                .toList();
    }

    @Override
    public List<String> findDistinctCreators() {
        return shippingMarkMapper.findDistinctCreators();
    }

    private ShippingMarkPO toPO(ShippingMark mark) {
        ShippingMarkPO po = new ShippingMarkPO();
        po.setId(mark.getId());
        po.setBillNo(mark.getBillNo());
        po.setBillName(mark.getBillName());
        po.setStatus(mark.getStatus().getCode());
        po.setCreatedBy(mark.getCreatedBy());
        po.setCreator(mark.getCreator());
        po.setCreatedAt(mark.getCreatedAt());
        po.setUpdatedBy(mark.getUpdatedBy());
        po.setUpdator(mark.getUpdator());
        po.setUpdatedAt(mark.getUpdatedAt());
        po.setProcessedAt(mark.getProcessedAt());
        return po;
    }

    private ShippingMarkDetailPO toPO(ShippingMarkDetail detail) {
        ShippingMarkDetailPO po = new ShippingMarkDetailPO();
        po.setId(detail.getId());
        po.setBillNo(detail.getBillNo());
        po.setPurchaseOrderNo(detail.getPurchaseOrderNo());
        po.setSkuCode(detail.getSkuCode());
        po.setSkuName(detail.getSkuName());
        po.setSkuImage(detail.getSkuImage());
        po.setStatus(detail.getStatus().getCode());
        po.setErrorReason(detail.getErrorReason());
        po.setLabelFile(detail.getLabelFile());
        po.setCreatedBy(detail.getCreatedBy());
        po.setCreator(detail.getCreator());
        po.setCreatedAt(detail.getCreatedAt());
        po.setUpdatedBy(detail.getUpdatedBy());
        po.setUpdator(detail.getUpdator());
        po.setUpdatedAt(detail.getUpdatedAt());
        return po;
    }

    private ShippingMark toDomain(ShippingMarkPO mark, List<ShippingMarkDetailPO> detailPOs) {
        List<ShippingMarkDetail> details = detailPOs.stream().map(this::toDomain).toList();
        return ShippingMark.rehydrate(
                mark.getId(), mark.getBillNo(), mark.getBillName(), mark.getStatus(),
                mark.getCreatedBy(), mark.getCreator(), mark.getCreatedAt(),
                mark.getUpdatedBy(), mark.getUpdator(), mark.getUpdatedAt(), mark.getProcessedAt(), details);
    }

    private ShippingMarkDetail toDomain(ShippingMarkDetailPO detail) {
        return ShippingMarkDetail.rehydrate(
                detail.getId(), detail.getBillNo(), detail.getPurchaseOrderNo(),
                detail.getSkuCode(), detail.getSkuName(), detail.getSkuImage(), detail.getStatus(),
                detail.getErrorReason(), detail.getLabelFile(), detail.getCreatedBy(), detail.getCreator(),
                detail.getCreatedAt(), detail.getUpdatedBy(), detail.getUpdator(), detail.getUpdatedAt());
    }
}
