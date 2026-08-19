package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.ShippingMarkPreviewDTO;
import com.scione.scm.bill.application.port.ShippingMarkFileStore;
import com.scione.scm.bill.application.port.ShippingMarkLabelRenderer;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkDetail;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkRepository;
import com.scione.scm.bill.domain.shippingmark.enums.DetailStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 箱唛预览及单个、批量下载用例。
 */
@Service
@RequiredArgsConstructor
public class ShippingMarkDownloadAppService {

    private static final long MAX_BATCH_BYTES = 50L * 1024 * 1024;

    private final ShippingMarkRepository repository;
    private final ShippingMarkLabelRenderer labelRenderer;
    private final ShippingMarkFileStore fileStore;

    public ShippingMarkPreviewDTO preview(Long detailId) {
        ShippingMarkDetail detail = requireGeneratedDetail(detailId);
        try {
            return new ShippingMarkPreviewDTO(
                    detail.getId(), detail.getPurchaseOrderNo(), detail.getSkuCode(), detail.getSkuName(),
                    detail.getSkuImage(), labelRenderer.barcodeDataUrl(detail.getSkuCode()));
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR);
        }
    }

    public byte[] download(Long detailId) {
        ShippingMarkDetail detail = requireGeneratedDetail(detailId);
        try {
            return fileStore.read(detail.getLabelFile());
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SHIPPING_MARK_FILE_NOT_FOUND);
        }
    }

    public String downloadFileName(Long detailId) {
        ShippingMarkDetail detail = requireGeneratedDetail(detailId);
        return safeName(detail.getPurchaseOrderNo()) + "_" + safeName(detail.getSkuCode()) + ".xlsx";
    }

    public byte[] downloadBatch(List<Long> detailIds) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            Set<String> names = new HashSet<>();
            long totalBytes = 0;
            for (Long detailId : detailIds.stream().distinct().toList()) {
                ShippingMarkDetail detail = requireGeneratedDetail(detailId);
                byte[] labelContent = fileStore.read(detail.getLabelFile());
                totalBytes += labelContent.length;
                if (totalBytes > MAX_BATCH_BYTES) {
                    throw new BusinessException(ResultCode.SHIPPING_MARK_BATCH_TOO_LARGE);
                }
                String entryName = uniqueName(safeName(detail.getPurchaseOrderNo()) + "_" + safeName(detail.getSkuCode()) + ".xlsx", names);
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(labelContent);
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SHIPPING_MARK_FILE_NOT_FOUND);
        }
    }

    private ShippingMarkDetail requireGeneratedDetail(Long detailId) {
        ShippingMarkDetail detail = repository.findDetailById(detailId)
                .orElseThrow(() -> new BusinessException(ResultCode.SHIPPING_MARK_DETAIL_NOT_FOUND));
        if (detail.getStatus() != DetailStatus.SUCCESS || detail.getLabelFile() == null) {
            throw new BusinessException(ResultCode.SHIPPING_MARK_NOT_READY);
        }
        return detail;
    }

    private String safeName(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        return normalized.isBlank() ? "shipping-mark" : normalized;
    }

    private String uniqueName(String candidate, Set<String> names) {
        if (names.add(candidate)) {
            return candidate;
        }
        int dot = candidate.lastIndexOf('.');
        String base = dot < 0 ? candidate : candidate.substring(0, dot);
        String extension = dot < 0 ? "" : candidate.substring(dot);
        int suffix = 2;
        String result;
        do {
            result = base + "_" + suffix++ + extension;
        } while (!names.add(result));
        return result;
    }
}
