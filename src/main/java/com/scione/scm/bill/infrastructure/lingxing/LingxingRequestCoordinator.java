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
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 实例内排队，并用共享MySQL命名锁协调Java实例与Python的同应用、同接口请求。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LingxingRequestCoordinator {
    private final LingxingOpenApiProperties properties;
    private final DataSource dataSource;
    private final Map<String, RequestQueue> queues = new ConcurrentHashMap<>();
    private final AtomicInteger threadNumber = new AtomicInteger();

    /**
     * 按应用及接口串行执行请求，控制发送间隔并限制排队容量和等待时间。
     *
     * @param <T> 请求任务返回结果类型
     * @param path 第三方接口路径，不包含域名和鉴权查询参数
     * @param request 在队列中实际执行远程调用的任务
     * @return 请求任务的实际执行结果
     */
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
        FutureTask<T> task = new FutureTask<>(() -> executeShared(path, request));
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

    /** 与Python约定相同锁名：lx-api: + SHA-256(appId + 换行 + path)的前40位。 */
    static String sharedLockName(String appId, String path) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((appId + "\n" + path).getBytes(StandardCharsets.UTF_8));
            return "lx-api:" + HexFormat.of().formatHex(hash).substring(0, 40);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private <T> T executeShared(String path, Supplier<T> request) {
        String name = sharedLockName(properties.getAppId(), path);
        // 同一MySQL实例上的命名锁不区分schema。连接不可用时停止请求，不能绕过协调。
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,?)")) {
                statement.setString(1, name);
                statement.setLong(2, Math.max(1, properties.getRequestQueueTimeout().toSeconds()));
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw failure("跨进程接口锁等待超时，未发送请求", path);
                    }
                }
            }
            long started = System.nanoTime();
            try {
                return request.get();
            } finally {
                try {
                    // 连同HTTP请求全程持锁，且两次请求至少间隔1.1秒；失败响应也占用间隔。
                    long interval = Math.max(TimeUnit.MILLISECONDS.toNanos(1100),
                            properties.getMinRequestInterval().toNanos());
                    long remaining = interval - (System.nanoTime() - started);
                    if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        statement.setString(1, name);
                        try (var result = statement.executeQuery()) {
                            if (!result.next() || result.getInt(1) != 1) throw new SQLException("接口锁释放失败");
                        }
                    } catch (SQLException ex) {
                        connection.abort(Runnable::run);
                        throw ex;
                    }
                }
            }
        } catch (SQLException ex) {
            throw failure("共享接口锁连接异常，停止调用", path);
        }
    }
    /**
     * 服务关闭时停止各领星接口队列接收新任务并关闭执行器。
     */
    @PreDestroy
    public void stop() {
        queues.values().forEach(queue -> queue.executor.shutdown());
    }

    /** 时间状态只由对应接口的单个工作线程访问。 */
    private static final class RequestQueue {
        private final ThreadPoolExecutor executor;
        private long lastStartedNanos;
        private boolean hasStarted;

        /**
         * 使用独立执行器初始化单个领星接口请求队列。
         *
         * @param executor 该接口队列使用的单线程请求执行器
         */
        private RequestQueue(ThreadPoolExecutor executor) { this.executor = executor; }
    }

    /**
     * 创建附带接口路径的排队或执行失败异常。
     *
     * @param reason 待保存或展示的失败原因
     * @param path 第三方接口路径，不包含域名和鉴权查询参数
     * @return 领星请求协调失败业务异常
     */
    private BusinessException failure(String reason, String path) {
        return new BusinessException(ResultCode.LINGXING_API_ERROR, "领星接口调用失败：" + reason + "；接口=" + path);
    }
}
