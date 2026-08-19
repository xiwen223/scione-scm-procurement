package com.scione.scm.bill.application;

import com.scione.scm.bill.application.port.ShippingMarkFileStore;
import com.scione.scm.bill.application.port.ShippingMarkLabelRenderer;
import com.scione.scm.bill.domain.shippingmark.ShippingMark;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkDetail;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkRepository;
import com.scione.scm.bill.domain.shippingmark.enums.DetailStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 后台逐条生成箱唛标签，并持续持久化主单和明细状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingMarkProcessingService {

    private final ShippingMarkRepository repository;
    private final ShippingMarkLabelRenderer labelRenderer;
    private final ShippingMarkFileStore fileStore;

    public void process(Long markId) {
        repository.findById(markId).ifPresent(this::processMark);
    }

    private void processMark(ShippingMark mark) {
        for (ShippingMarkDetail detail : mark.getDetails()) {
            if (detail.getStatus() == DetailStatus.SUCCESS || detail.getStatus() == DetailStatus.FAILED) {
                continue;
            }
            try {
                if (detail.getStatus() == DetailStatus.PENDING) {
                    detail.start();
                    repository.updateDetail(detail);
                }
                String reason = validate(detail);
                if (reason != null) {
                    detail.markFailed(reason);
                } else {
                    byte[] label = labelRenderer.render(detail);
                    detail.markSuccess(fileStore.storeLabel(detail.getBillNo(), label));
                }
            } catch (Exception exception) {
                log.warn("Shipping mark detail generation failed: billNo={}, detailId={}",
                        mark.getBillNo(), detail.getId(), exception);
                detail.markFailed("箱唛生成失败");
            }
            repository.updateDetail(detail);
        }
        mark.complete();
        repository.update(mark);
    }

    private String validate(ShippingMarkDetail detail) {
        StringBuilder reason = new StringBuilder();
        if (detail.getPurchaseOrderNo() == null || detail.getPurchaseOrderNo().isBlank()) {
            reason.append("采购单号为空");
        }
        if (detail.getSkuCode() == null || detail.getSkuCode().isBlank()) {
            appendReason(reason, "SKU为空");
        } else if (!isCode128Encodable(detail.getSkuCode())) {
            appendReason(reason, "SKU条码未生成");
        }
        return reason.isEmpty() ? null : reason.toString();
    }

    private boolean isCode128Encodable(String skuCode) {
        return skuCode.codePoints().allMatch(character -> character >= 32 && character <= 126);
    }

    private void appendReason(StringBuilder reason, String value) {
        if (!reason.isEmpty()) {
            reason.append('；');
        }
        reason.append(value);
    }
}
