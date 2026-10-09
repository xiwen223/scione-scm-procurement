package com.scione.scm.bill.infrastructure.template;

import com.scione.scm.bill.config.ContractImageDownloadProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 只并发下载字节；Workbook 的写入仍在合同生成线程顺序执行。 */
@Slf4j
@Component
public class ContractImageDownloader {
    private final RestTemplate restTemplate;
    private final ThreadPoolExecutor executor;

    public ContractImageDownloader(ContractImageDownloadProperties properties, RestTemplate restTemplate) {
        int concurrency = properties.getConcurrency();
        if (concurrency < 1 || concurrency > 100) {
            throw new IllegalArgumentException("contract.image-download.concurrency 必须在 1 到 100 之间");
        }
        this.restTemplate = restTemplate;
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency * 2), runnable -> {
                    Thread thread = new Thread(runnable, "contract-image-download-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, (task, pool) -> {
                    // 队列满时提交方等待，不能 CallerRuns，否则多个合同会突破全局并发上限。
                    try {
                        while (!pool.isShutdown()) {
                            if (pool.getQueue().offer(task, 1, TimeUnit.SECONDS)) {
                                if (pool.isShutdown() && pool.remove(task)) {
                                    throw new RejectedExecutionException("图片下载线程池已关闭");
                                }
                                return;
                            }
                        }
                        throw new RejectedExecutionException("图片下载线程池已关闭");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException("图片下载排队被中断", ex);
                    }
                });
    }

    /** 同次生成按 URL 去重，失败图片保持空白，沿用原先不阻断合同生成的规则。 */
    public Map<String, byte[]> download(String contractNo, Collection<String> urls) throws InterruptedException {
        long start = System.nanoTime();
        Map<String, Future<byte[]>> tasks = new LinkedHashMap<>();
        Map<String, byte[]> images = new LinkedHashMap<>();
        try {
            for (String url : urls) {
                if (!StringUtils.hasText(url) || tasks.containsKey(url)) continue;
                tasks.put(url, executor.submit(() -> {
                    try {
                        byte[] bytes = restTemplate.getForObject(url, byte[].class);
                        if (bytes == null || bytes.length == 0) {
                            log.warn("合同商品图片下载为空：contractNo={}", contractNo);
                            return null;
                        }
                        return bytes;
                    } catch (RuntimeException ex) {
                        // 不打印可能包含临时凭据的图片 URL。
                        log.warn("合同商品图片下载失败，继续生成：contractNo={}, exception={}",
                                contractNo, ex.getClass().getSimpleName());
                        return null;
                    }
                }));
            }
            for (Map.Entry<String, Future<byte[]>> entry : tasks.entrySet()) {
                try {
                    byte[] bytes = entry.getValue().get();
                    if (bytes != null) images.put(entry.getKey(), bytes);
                } catch (ExecutionException ex) {
                    throw new IllegalStateException("图片下载任务执行失败", ex.getCause());
                }
            }
            log.info("合同图片并发下载完成：contractNo={}, 去重URL数={}, 成功={}, 失败={}, elapsedMs={}, 全局并发上限={}",
                    contractNo, tasks.size(), images.size(), tasks.size() - images.size(),
                    (System.nanoTime() - start) / 1_000_000, executor.getMaximumPoolSize());
            return images;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } finally {
            tasks.values().forEach(task -> { if (!task.isDone()) task.cancel(true); });
        }
    }

    @PreDestroy
    public void close() { executor.shutdown(); }
}
