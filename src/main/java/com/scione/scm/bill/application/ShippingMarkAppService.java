package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.CreateShippingMarkDTO;
import com.scione.scm.bill.application.vo.ShippingMarkVO;
import com.scione.scm.bill.application.vo.ShippingMarkListItemVO;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.shippingmark.*;
import com.scione.scm.bill.domain.shippingmark.enums.MarkStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 箱唛查询与手工建单应用服务。
 */
@Service
@RequiredArgsConstructor
public class ShippingMarkAppService {

    private final ShippingMarkRepository repository;

    public ShippingMarkVO create(CreateShippingMarkDTO cmd) {
        if (repository.findByBillNo(cmd.getBillNo()).isPresent()) {
            throw new BusinessException(ResultCode.SHIPPING_MARK_ALREADY_EXISTS);
        }
        ShippingMark mark = ShippingMark.create(cmd.getBillNo(), cmd.getBillName(), cmd.getCreatedBy(), cmd.getCreator());
        cmd.getDetails().forEach(detail -> mark.addDetail(new ShippingMarkDetail(
                cmd.getBillNo(), detail.getPurchaseOrderNo(), detail.getSkuCode(), detail.getSkuName(), detail.getSkuImage(),
                cmd.getCreatedBy(), cmd.getCreator())));
        repository.save(mark);
        return ShippingMarkVO.from(mark);
    }

    public ShippingMarkVO getById(Long id) {
        return repository.findById(id)
                .map(ShippingMarkVO::from)
                .orElseThrow(() -> new BusinessException(ResultCode.SHIPPING_MARK_NOT_FOUND));
    }

    public ShippingMarkVO getByBillNo(String billNo) {
        return repository.findByBillNo(billNo)
                .map(ShippingMarkVO::from)
                .orElseThrow(() -> new BusinessException(ResultCode.SHIPPING_MARK_NOT_FOUND));
    }

    public PageResult<ShippingMarkListItemVO> findPage(
            String billNo, String billName, String creator, String status, int pageNum, int pageSize) {
        ShippingMarkQuery query = new ShippingMarkQuery(
                billNo, billName, creator, parseStatuses(status), pageNum, pageSize);
        ShippingMarkPage page = repository.findPage(query);
        return PageResult.of(pageNum, pageSize, page.total(),
                page.records().stream().map(ShippingMarkListItemVO::from).toList());
    }

    public List<ShippingMarkVO> list() {
        return repository.findAll().stream().map(ShippingMarkVO::from).toList();
    }

    public List<String> listCreators() {
        return repository.findDistinctCreators();
    }

    private List<MarkStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(status.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(this::parseStatus)
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
    }

    private MarkStatus parseStatus(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "1", "待处理" -> MarkStatus.PENDING;
            case "2", "处理中" -> MarkStatus.PROCESSING;
            case "3", "已处理", "已完成" -> MarkStatus.DONE;
            default -> throw new IllegalArgumentException("Unknown mark status");
        };
    }
}
