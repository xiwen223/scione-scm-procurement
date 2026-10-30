package com.scione.scm.bill.infrastructure.persistence;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

/**
 * MySQL 命名锁：锁绑定在一条独立连接上，不参与调用方的业务事务。
 * 催办发送串行与小时同步互斥都用它，避免两处各写一套 GET_LOCK/RELEASE_LOCK。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlNamedLock {

    /** 等待期间的日志间隔：锁被占用时每 5 秒报一次进度，避免长时间等待。 */
    private static final long WAIT_LOG_INTERVAL_MILLIS = 5_000;

    private final DataSource dataSource;

    /**
     * 在超时时间内轮询获取锁；等待期间不持有事务，便于应用关闭时中断。
     *
     * @param waitSeconds 单次 GET_LOCK 的阻塞秒数，0 表示立即返回
     * @param pollInterval 未取得锁时的重试间隔
     */
    /** 在超时时间内轮询获取命名锁；拿到返回锁句柄，超时/中断则抛异常。 */
    public LockHandle acquire(String name, Duration timeout, int waitSeconds, Duration pollInterval,
                              String timeoutMessage, String interruptedMessage) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw unavailable(timeoutMessage);//超时时间不合法
        }
        long deadline = System.nanoTime() + timeout.toNanos();//最终截止时刻
        long pollMillis = Math.max(1, pollInterval.toMillis());//轮询间隔，至少1毫秒
        long startedAt = System.nanoTime();//开始时刻，用于统计已等待多久
        long lastLoggedMillis = -1;//上次打日志的时间，初始-1表示还没打过
        while (System.nanoTime() < deadline) {//没到截止时间就继续试
            if (Thread.currentThread().isInterrupted()) throw unavailable(interruptedMessage);//线程被中断，抛中断异常
            Connection connection = null;//这次尝试用的连接
            try {
                connection = dataSource.getConnection();//从池里拿一条连接
                try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?,?)")) {//执行MySQL的GET_LOCK
                    statement.setString(1, name);//锁名
                    statement.setInt(2, Math.max(0, waitSeconds));//单次阻塞秒数
                    statement.setQueryTimeout(5);//查询超时5秒
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next() && !result.wasNull() && result.getInt(1) == 1) {
                            return new LockHandle(connection, name);//GET_LOCK返回1=拿到锁，返回句柄
                        }
                    }
                }
                connection.close();//没拿到锁，关闭连接
            } catch (SQLException exception) {
                closeQuietly(connection);
                throw unavailable("数据库连接异常，无法获取命名锁：" + name);
            }
            // 一次没拿到锁才打日志：瞬时成功的调用不产生噪音，真正卡住时每5秒报一次进度。
            long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000;//已经等了多久（毫秒）
            if (lastLoggedMillis < 0) {
                lastLoggedMillis = waitedMillis;
                log.info("命名锁被占用，开始等待：lock={}, 上限{}秒", name, timeout.toSeconds());//第一次没拿到，打开始等待日志
            } else if (waitedMillis - lastLoggedMillis >= WAIT_LOG_INTERVAL_MILLIS) {
                lastLoggedMillis = waitedMillis;
                log.info("命名锁仍在等待：lock={}, 已等待{}秒，上限{}秒",
                        name, waitedMillis / 1000, timeout.toSeconds());//每5秒打一次进度
            }
            try {
                Thread.sleep(pollMillis);//歇一会儿再试
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw unavailable(interruptedMessage);
            }
        }
        log.info("命名锁等待超时：lock={}, waitedMs={}, 上限{}秒",
                name, (System.nanoTime() - startedAt) / 1_000_000, timeout.toSeconds());
        throw unavailable(timeoutMessage);//等到截止时间还没拿到，抛超时异常
    }

    /** 锁持有句柄：close 时释放锁并归还连接，异常也保证连接回到池里。 */
    public static final class LockHandle implements AutoCloseable {
        private final Connection connection;//持有GET_LOCK的那条独立连接
        private final String name;//锁名，close时用它RELEASE_LOCK
        private boolean released;//是否已释放，防重复释放

        private LockHandle(Connection connection, String name) {
            this.connection = connection;
            this.name = name;
        }

        /** 释放锁并归还连接：先RELEASE_LOCK，再关闭连接；已释放则直接返回，异常也保证连接回到池里。 */
        @Override
        public void close() {
            if (released) return;//已经释放过了，直接返回
            released = true;//标记已释放，防重复
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, name);//指定要释放的锁名
                statement.executeQuery().close();//执行RELEASE_LOCK释放命名锁
            } catch (SQLException ignored) {
                // 连接关闭时 MySQL 也会释放命名锁，不因释放语句失败吞掉业务结果。
            } finally {
                closeQuietly(connection);//无论如何都归还连接
            }
        }
    }

    /** 安静地关闭连接：为null直接返回，关闭失败忽略（连接池会自行淘汰失效连接）。 */
    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 连接池会自行淘汰失效连接。
        }
    }

    /** 构造"锁不可用"的业务异常，统一用 SOP_PREVIEW_UNAVAILABLE 错误码。 */
    private static BusinessException unavailable(String message) {
        return new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, message);
    }
}
