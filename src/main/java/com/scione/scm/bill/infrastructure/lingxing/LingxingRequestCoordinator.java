package com.scione.scm.bill.infrastructure.lingxing;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.config.LingxingOpenApiProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 单实例请求排队：同 appId + 接口只用一个工作线程。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LingxingRequestCoordinator {
    private final LingxingOpenApiProperties properties;
    private final Map<String, RequestQueue> queues = new ConcurrentHashMap<>();
    private final AtomicInteger threadNumber = new AtomicInteger();

    public <T> T execute(String path, Supplier<T> request) {
        String key = properties.getAppId() + "\n" + path;
        RequestQueue queue = queues.computeIfAbsent(key, ignored -> new RequestQueue(new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "lingxing-request-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy())));
        long queuedAt = System.nanoTime();
        CompletableFuture<Void> started = new CompletableFuture<>();
        // 0=排队、1=已经开始、2=排队取消。超时取消与开始只能有一个成功。
        AtomicInteger state = new AtomicInteger();
        FutureTask<T> task = new FutureTask<>(request::get);
        try {
            queue.executor.execute(() -> {
                if (state.get() != 0) return;
                // 在单线程队列内补足间隔；这段等待也计入40秒排队上限。
                long intervalNanos = Math.max(0, properties.getMinRequestInterval().toNanos());
                long remaining = queue.hasStarted ? intervalNanos - (System.nanoTime() - queue.lastStartedNanos) : 0;
                if (remaining > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(remaining);
                    } catch (InterruptedException ex) {
                        state.compareAndSet(0, 2);
                        started.completeExceptionally(failure("请求间隔等待被中断，未发送请求", path));
                        task.cancel(false);
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!state.compareAndSet(0, 1)) return;
                // 仅真正准备执行的请求更新时间，排队取消不占用下一次间隔。
                queue.lastStartedNanos = System.nanoTime();
                queue.hasStarted = true;
                long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAt);
                if (waitedMs > 100) log.info("领星请求排队完成：path={}, waitMs={}", path, waitedMs);
                started.complete(null);
                // 必须等响应读取完成才执行队列下一项；调用方离开页面也不会放出第二个并发请求。
                task.run();
            });
        } catch (RejectedExecutionException ex) {
            throw failure("请求队列已满或服务正在关闭，未发送请求，请稍后重试", path);
        }
        try {
            try {
                started.get(properties.getRequestQueueTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                if (state.compareAndSet(0, 2)) {
                    task.cancel(false);
                    throw failure("排队等待超时，未发送请求，请稍后重试", path);
                }
                // 刚好已开始的请求继续等待结果，不能误报未发送或再发一遍。
            }
            return task.get();
        } catch (InterruptedException ex) {
            boolean cancelledBeforeStart = state.compareAndSet(0, 2);
            if (cancelledBeforeStart) task.cancel(false);
            Thread.currentThread().interrupt();
            throw failure(cancelledBeforeStart ? "排队被中断，未发送请求" : "等待结果被中断，请求已开始", path);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            if (ex.getCause() instanceof Error error) throw error;
            throw failure("请求执行异常", path);
        }
    }

    @PreDestroy
    public void stop() {
        queues.values().forEach(queue -> queue.executor.shutdown());
    }

    /** 时间状态只由对应接口的单个工作线程访问。 */
    private static final class RequestQueue {
        private final ThreadPoolExecutor executor;
        private long lastStartedNanos;
        private boolean hasStarted;

        private RequestQueue(ThreadPoolExecutor executor) { this.executor = executor; }
    }

    private BusinessException failure(String reason, String path) {
        return new BusinessException(ResultCode.LINGXING_API_ERROR, "领星接口调用失败：" + reason + "；接口=" + path);
    }
}
