package com.scione.scm.bill.infrastructure.fadada;

import org.springframework.stereotype.Component;

/**
 * 保存当前业务线程的法大大告警信息，支持嵌套调用后恢复外层上下文。
 * <p>异步发送前应将信息复制到告警请求中；异步线程不读取此 ThreadLocal。</p>
 */
@Component
public class FadadaAlertContextHolder {
    private final ThreadLocal<Context> current = new ThreadLocal<>();

    /** 获取当前上下文；未建立上下文时返回 null。 */
    public Context get() {
        return current.get();
    }

    /**
     * 建立本次调用的上下文，返回用于恢复原上下文的作用域。
     *
     * @param context 本次业务信息，可为 null，表示本次不携带业务信息
     * @return 必须在 try-with-resources 中关闭的作用域，异常退出时也会清理
     */
    public Scope open(Context context) {
        Context previous = current.get();
        current.set(context);
        return () -> {
            if (previous == null) current.remove();
            else current.set(previous);
        };
    }

    /**
     * 告警使用的不可变业务信息。
     * @param companyName 公司名称；合同场景取需方公司名称，可为空
     * @param contractNo 合同编号；印章场景为空
     */
    public record Context(String companyName, String contractNo) { }

    /** 管理上下文生命周期，避免线程复用时残留上一次业务的数据。 */
    public interface Scope extends AutoCloseable {
        /** 恢复外层上下文；没有外层上下文时移除 ThreadLocal 数据。 */
        @Override
        void close();
    }
}
