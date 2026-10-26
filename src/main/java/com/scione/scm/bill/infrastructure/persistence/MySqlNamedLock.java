package com.scione.scm.bill.infrastructure.persistence;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import lombok.RequiredArgsConstructor;
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
@Component
@RequiredArgsConstructor
public class MySqlNamedLock {

    private final DataSource dataSource;

    /**
     * 在超时时间内轮询获取锁；等待期间不持有事务，便于应用关闭时中断。
     *
     * @param waitSeconds 单次 GET_LOCK 的阻塞秒数，0 表示立即返回
     * @param pollInterval 未取得锁时的重试间隔
     */
    public LockHandle acquire(String name, Duration timeout, int waitSeconds, Duration pollInterval,
                              String timeoutMessage, String interruptedMessage) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw unavailable(timeoutMessage);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        long pollMillis = Math.max(1, pollInterval.toMillis());
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw unavailable(interruptedMessage);
            Connection connection = null;
            try {
                connection = dataSource.getConnection();
                try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?,?)")) {
                    statement.setString(1, name);
                    statement.setInt(2, Math.max(0, waitSeconds));
                    statement.setQueryTimeout(5);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next() && !result.wasNull() && result.getInt(1) == 1) {
                            return new LockHandle(connection, name);
                        }
                    }
                }
                connection.close();
            } catch (SQLException exception) {
                closeQuietly(connection);
                throw unavailable("数据库连接异常，无法获取命名锁：" + name);
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw unavailable(interruptedMessage);
            }
        }
        throw unavailable(timeoutMessage);
    }

    /** 锁持有句柄：close 时释放锁并归还连接，异常也保证连接回到池里。 */
    public static final class LockHandle implements AutoCloseable {
        private final Connection connection;
        private final String name;
        private boolean released;

        private LockHandle(Connection connection, String name) {
            this.connection = connection;
            this.name = name;
        }

        @Override
        public void close() {
            if (released) return;
            released = true;
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, name);
                statement.executeQuery().close();
            } catch (SQLException ignored) {
                // 连接关闭时 MySQL 也会释放命名锁，不因释放语句失败吞掉业务结果。
            } finally {
                closeQuietly(connection);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 连接池会自行淘汰失效连接。
        }
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, message);
    }
}
