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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private static final int COPY_BUFFER_SIZE = 8 * 1024;

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

    /**
     * 预检可下载标签和源文件总大小后，将 ZIP 直接写入调用方提供的输出流。
     * 调用方拥有输出流的关闭权；本方法只完成并刷新 ZIP 数据。
     */
    public void writeBatch(List<Long> detailIds, OutputStream responseOutput) throws IOException {
        long requestStartedAt = System.nanoTime();
        int requestedCount = detailIds.size();
        log.info("Shipping mark batch download started: requestedCount={}", requestedCount);
        try {
            BatchDownload batch = prepareBatch(detailIds, requestedCount);
            writeZip(batch, responseOutput, requestedCount, requestStartedAt);
        } catch (BusinessException exception) {
            log.warn("Shipping mark batch download failed: requestedCount={}, elapsedMs={}, reason={}",
                    requestedCount, elapsedMillis(requestStartedAt), exception.getMessage());
            throw exception;
        } catch (IOException exception) {
            log.warn("Shipping mark batch download stream failed: requestedCount={}, elapsedMs={}",
                    requestedCount, elapsedMillis(requestStartedAt), exception);
            throw exception;
        }
    }

    private BatchDownload prepareBatch(List<Long> detailIds, int requestedCount) {
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

        long sizeCheckStartedAt = System.nanoTime();
        long sourceBytes = 0;
        for (ShippingMarkDetail detail : details) {
            long fileSize = sourceFileSize(detail);
            if (fileSize > MAX_BATCH_BYTES - sourceBytes) {
                log.warn("Shipping mark batch download source size limit exceeded: detailId={}, cumulativeSourceBytes={}, maxSourceBytes={}",
                        detail.getId(), sourceBytes + fileSize, MAX_BATCH_BYTES);
                throw new BusinessException(ResultCode.SHIPPING_MARK_BATCH_TOO_LARGE);
            }
            sourceBytes += fileSize;
        }
        log.info("Shipping mark batch download source files prechecked: entryCount={}, sourceBytes={}, elapsedMs={}",
                details.size(), sourceBytes, elapsedMillis(sizeCheckStartedAt));
        return new BatchDownload(details, sourceBytes);
    }

    private void writeZip(BatchDownload batch, OutputStream responseOutput, int requestedCount, long requestStartedAt)
            throws IOException {
        log.info("Shipping mark batch download ZIP stream started: entryCount={}", batch.details().size());
        CountingOutputStream output = new CountingOutputStream(responseOutput);
        long sourceBytes = 0;
        Set<String> names = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            int entryIndex = 0;
            for (ShippingMarkDetail detail : batch.details()) {
                entryIndex++;
                String entryName = uniqueName(
                        safeName(detail.getPurchaseOrderNo()) + "_" + safeName(detail.getSkuCode()) + ".xlsx", names);
                long zipWriteStartedAt = System.nanoTime();
                zip.putNextEntry(new ZipEntry(entryName));
                long labelBytes = copyLabel(detail, zip, MAX_BATCH_BYTES - sourceBytes);
                sourceBytes += labelBytes;
                zip.closeEntry();
                zip.flush();
                log.info("Shipping mark batch download ZIP entry streamed: entryIndex={}, entryCount={}, detailId={}, entryName={}, sourceBytes={}, cumulativeSourceBytes={}, zipBytesSoFar={}, elapsedMs={}",
                        entryIndex, batch.details().size(), detail.getId(), entryName, labelBytes, sourceBytes, output.count(),
                        elapsedMillis(zipWriteStartedAt));
            }
            long zipFinishStartedAt = System.nanoTime();
            zip.finish();
            zip.flush();
            log.info("Shipping mark batch download completed: requestedCount={}, selectedCount={}, sourceBytes={}, plannedSourceBytes={}, zipBytes={}, zipFinishElapsedMs={}, elapsedMs={}",
                    requestedCount, batch.details().size(), sourceBytes, batch.sourceBytes(), output.count(),
                    elapsedMillis(zipFinishStartedAt), elapsedMillis(requestStartedAt));
        }
    }

    private long sourceFileSize(ShippingMarkDetail detail) {
        try {
            return fileStore.size(detail.getLabelFile());
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SHIPPING_MARK_FILE_NOT_FOUND);
        }
    }

    private long copyLabel(ShippingMarkDetail detail, ZipOutputStream zip, long remainingBytes) throws IOException {
        InputStream input = openLabel(detail);
        long copied = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (input) {
            while (true) {
                int read = readLabel(input, buffer);
                if (read == -1) {
                    return copied;
                }
                if (read > remainingBytes - copied) {
                    throw new BusinessException(ResultCode.SHIPPING_MARK_BATCH_TOO_LARGE);
                }
                zip.write(buffer, 0, read);
                copied += read;
            }
        }
    }

    private InputStream openLabel(ShippingMarkDetail detail) {
        try {
            return fileStore.open(detail.getLabelFile());
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SHIPPING_MARK_FILE_NOT_FOUND);
        }
    }

    private int readLabel(InputStream input, byte[] buffer) {
        try {
            return input.read(buffer);
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

    private record BatchDownload(List<ShippingMarkDetail> details, long sourceBytes) {
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        private CountingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }

        @Override
        public void close() throws IOException {
            flush();
        }

        private long count() {
            return count;
        }
    }
}
