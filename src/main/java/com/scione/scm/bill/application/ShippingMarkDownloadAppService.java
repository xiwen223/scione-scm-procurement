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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 箱唛预览及单个、批量下载用例。
 */
@Slf4j
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
        long requestStartedAt = System.nanoTime();
        int requestedCount = detailIds.size();
        log.info("Shipping mark batch download started: requestedCount={}", requestedCount);
        try {
            long deduplicateStartedAt = System.nanoTime();
            List<Long> uniqueDetailIds = detailIds.stream().distinct().toList();
            log.info("Shipping mark batch download deduplicated: requestedCount={}, uniqueCount={}, elapsedMs={}",
                    requestedCount, uniqueDetailIds.size(), elapsedMillis(deduplicateStartedAt));
            if (uniqueDetailIds.isEmpty()) {
                throw new BusinessException(ResultCode.SHIPPING_MARK_NOT_READY);
            }

            long queryStartedAt = System.nanoTime();
            List<ShippingMarkDetail> queriedDetails = repository.findDetailsByIds(uniqueDetailIds);
            log.info("Shipping mark batch download metadata queried: uniqueCount={}, queriedCount={}, elapsedMs={}",
                    uniqueDetailIds.size(), queriedDetails.size(), elapsedMillis(queryStartedAt));

            long filterStartedAt = System.nanoTime();
            List<ShippingMarkDetail> details = selectGeneratedDetails(uniqueDetailIds, queriedDetails);
            int skippedCount = uniqueDetailIds.size() - details.size();
            log.info("Shipping mark batch download generated details selected: selectedCount={}, skippedCount={}, elapsedMs={}",
                    details.size(), skippedCount, elapsedMillis(filterStartedAt));
            if (details.isEmpty()) {
                throw new BusinessException(ResultCode.SHIPPING_MARK_NOT_READY);
            }

            try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
                log.info("Shipping mark batch download ZIP creation started: entryCount={}", details.size());
                Set<String> names = new HashSet<>();
                long totalBytes = 0;
                int entryIndex = 0;
                for (ShippingMarkDetail detail : details) {
                    entryIndex++;
                    long fileReadStartedAt = System.nanoTime();
                    byte[] labelContent = fileStore.read(detail.getLabelFile());
                    long fileReadElapsedMs = elapsedMillis(fileReadStartedAt);
                    totalBytes += labelContent.length;
                    log.info("Shipping mark batch download label read: entryIndex={}, entryCount={}, detailId={}, sourceBytes={}, cumulativeSourceBytes={}, elapsedMs={}",
                            entryIndex, details.size(), detail.getId(), labelContent.length, totalBytes, fileReadElapsedMs);
                    if (totalBytes > MAX_BATCH_BYTES) {
                        log.warn("Shipping mark batch download source size limit exceeded: detailId={}, cumulativeSourceBytes={}, maxSourceBytes={}",
                                detail.getId(), totalBytes, MAX_BATCH_BYTES);
                        throw new BusinessException(ResultCode.SHIPPING_MARK_BATCH_TOO_LARGE);
                    }

                    String entryName = uniqueName(safeName(detail.getPurchaseOrderNo()) + "_" + safeName(detail.getSkuCode()) + ".xlsx", names);
                    long zipWriteStartedAt = System.nanoTime();
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(labelContent);
                    zip.closeEntry();
                    log.info("Shipping mark batch download ZIP entry written: entryIndex={}, entryCount={}, detailId={}, entryName={}, sourceBytes={}, zipBytesSoFar={}, elapsedMs={}",
                            entryIndex, details.size(), detail.getId(), entryName, labelContent.length, output.size(),
                            elapsedMillis(zipWriteStartedAt));
                }

                long zipFinishStartedAt = System.nanoTime();
                zip.finish();
                long zipFinishElapsedMs = elapsedMillis(zipFinishStartedAt);
                long responseBuildStartedAt = System.nanoTime();
                byte[] content = output.toByteArray();
                long responseBuildElapsedMs = elapsedMillis(responseBuildStartedAt);
                log.info("Shipping mark batch download completed: requestedCount={}, selectedCount={}, sourceBytes={}, zipBytes={}, zipFinishElapsedMs={}, responseBuildElapsedMs={}, elapsedMs={}",
                        requestedCount, details.size(), totalBytes, content.length, zipFinishElapsedMs, responseBuildElapsedMs,
                        elapsedMillis(requestStartedAt));
                return content;
            }
        } catch (BusinessException exception) {
            log.warn("Shipping mark batch download failed: requestedCount={}, elapsedMs={}, reason={}",
                    requestedCount, elapsedMillis(requestStartedAt), exception.getMessage());
            throw exception;
        } catch (IOException exception) {
            log.warn("Shipping mark batch download failed because a label file could not be read: requestedCount={}, elapsedMs={}",
                    requestedCount, elapsedMillis(requestStartedAt), exception);
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

    private List<ShippingMarkDetail> selectGeneratedDetails(
            List<Long> detailIds, List<ShippingMarkDetail> queriedDetails) {
        Map<Long, ShippingMarkDetail> detailsById = queriedDetails.stream()
                .collect(Collectors.toMap(ShippingMarkDetail::getId, Function.identity()));
        return detailIds.stream()
                .map(detailsById::get)
                .filter(detail -> detail != null
                        && detail.getStatus() == DetailStatus.SUCCESS
                        && detail.getLabelFile() != null)
                .toList();
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
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
