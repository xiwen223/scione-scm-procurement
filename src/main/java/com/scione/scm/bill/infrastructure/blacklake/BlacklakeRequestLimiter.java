package com.scione.scm.bill.infrastructure.blacklake;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 单实例按接口平滑控制发送节奏，多个消费线程共享，不限制已发出请求的执行并发。 */
@Component
public class BlacklakeRequestLimiter {
    private final RequestSlot productQuerySlot = new RequestSlot(63);
    private final RequestSlot productCreateSlot = new RequestSlot(125);
    private final RequestSlot productUpdateSlot = new RequestSlot(125);

    public void acquire(String path) {
        switch (path) {
            case "/api/dytin/external/product/queryList2":
                productQuerySlot.acquire();
                break;
            case "/api/dytin/external/product/create":
                productCreateSlot.acquire();
                break;
            case "/api/dytin/external/product/update":
                productUpdateSlot.acquire();
                break;
            default:
                break;
        }
    }

    private static class RequestSlot {
        private final long intervalNanos;
        private long nextRequestNanos;
        private boolean initialized;

        private RequestSlot(long intervalMillis) {
            intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
        }

        private synchronized void acquire() {
            if (Thread.currentThread().isInterrupted()) throw interrupted();
            if (initialized) {
                long remainingNanos = nextRequestNanos - System.nanoTime();
                while (remainingNanos > 0) {
                    try {
                        // 仅持有当前接口的发送节奏锁，HTTP 调用在释放锁之后执行。
                        TimeUnit.NANOSECONDS.sleep(remainingNanos);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw interrupted();
                    }
                    remainingNanos = nextRequestNanos - System.nanoTime();
                }
            }
            nextRequestNanos = System.nanoTime() + intervalNanos;
            initialized = true;
        }

        private BusinessException interrupted() {
            return new BusinessException(ResultCode.SYSTEM_ERROR, "等待黑湖接口调用额度被中断，未发送请求");
        }
    }
}
