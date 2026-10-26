package com.scione.scm.bill.infrastructure.task;

import com.scione.scm.bill.application.SopReminderService;
import com.scione.scm.bill.application.SopReminderSendService;
import com.scione.scm.bill.config.SopReminderProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import java.time.ZoneId;

/** 统一定时入口。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SopReminderSchedule {
    private final SopReminderProperties properties;
    private final SopReminderService ruleService;
    private final SopReminderSendService sendService;

    // 等待同步可能很久，催办使用独立线程池，不占用合同等模块的默认调度线程。
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @PostConstruct
    public void registerTasks() {
        scheduler.setPoolSize(Math.max(2, properties.getNodes().size() + 1));//线程数=节点数+1，给重试留一个线程
        scheduler.setThreadNamePrefix("reminder-");//线程名加个前缀
        scheduler.initialize();//创建底层线程池
        // 使用现有调度器，不再新增补偿任务类；普通与装箱分别使用各自的发送锁。
        //登记补偿任务：每分钟（上次跑完后计时）扫一次数据库中到了重试时间的失败消息并重发，出错记录ERROR。
        for (boolean packing : new boolean[]{false, true}) {
            scheduler.scheduleWithFixedDelay(() -> {
                try { sendService.retryDue(packing); }
                catch (Exception exception) { log.error("催办补偿未完成：packing={}, {}", packing, exception.getMessage()); }
            }, java.time.Duration.ofMinutes(1));
        }
        // 每个节点可有不同的执行时点，全部使用北京时间；修改cron/增加节点后重启SCM。
        java.util.Map<String, String> crons = new java.util.LinkedHashMap<>();
        properties.getNodes().forEach((nodeCode, node) -> {
            if (!ruleService.supportsNode(nodeCode)) {
                throw new IllegalStateException("配置了尚未实现的催办节点：" + nodeCode);
            }
            CronExpression.parse(node.getCron());
            crons.put(nodeCode, node.getCron());
        });
        // 装箱有独立调度入口：普通节点等待同步时，不阻塞下一轮装箱触发。
        String packingCron = crons.remove("PACKING_RELATE");
        if (packingCron != null) registerLane(java.util.Map.of("PACKING_RELATE", packingCron));
        if (!crons.isEmpty()) registerLane(crons);
    }

    private void registerLane(java.util.Map<String, String> crons) {
        // 普通节点仍按同一时点汇总；装箱单独计算下次执行时间。
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> nextSlot = new java.util.concurrent.atomic.AtomicReference<>();
        scheduler.schedule(() -> runSlot(nextSlot.get(), crons), context -> {
            java.time.Instant next = crons.values().stream().distinct()
                    .<java.time.Instant>map(cron -> new CronTrigger(cron, ZoneId.of("Asia/Shanghai")).nextExecution(context))
                    .filter(java.util.Objects::nonNull).min(java.time.Instant::compareTo).orElse(null);
            nextSlot.set(next);
            return next;
        });
    }

    //应用关闭时停掉线程池，避免消息发到一半被强杀。
    @PreDestroy
    public void stop() {
        scheduler.shutdown();
    }

    //每次到点就执行
    private void runSlot(java.time.Instant instant, java.util.Map<String, String> crons) {
        if (instant == null) return;
        java.time.LocalDateTime slot = java.time.LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));
        java.util.List<String> codes = crons.entrySet().stream().filter(entry -> {
            SopReminderProperties.Node node = properties.getNodes().get(entry.getKey());
            return node != null && Boolean.TRUE.equals(node.getEnabled())
                    && slot.equals(CronExpression.parse(entry.getValue()).next(slot.minusSeconds(1)));
        }).map(java.util.Map.Entry::getKey).toList();
        if (codes.isEmpty()) return;
        long started = System.nanoTime();
        log.info("定时催办开始：nodes={}, scheduledAt={}", codes, slot);
        try {
            SopReminderSendService.SendResult sent = sendService.sendNodes(codes, slot, null);
            log.info("定时催办执行完成：node={}, todos={}, pushes={}, skipped={}",
                    codes, sent.todos(), sent.pushes(), sent.skipped().size());
        } catch (Exception exception) {
            log.error("定时催办执行失败：nodes={}", codes, exception);
        } finally {
            log.info("定时催办结束：nodes={}, elapsedMs={}", codes,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }
}
