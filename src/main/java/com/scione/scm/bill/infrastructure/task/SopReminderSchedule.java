package com.scione.scm.bill.infrastructure.task;

import com.scione.scm.bill.application.SopReminderService;
import com.scione.scm.bill.application.SopReminderSendService;
import com.scione.scm.bill.config.SopReminderProperties;
import com.scione.scm.bill.domain.sop.SopReminderDispatchAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import java.time.ZoneId;
import java.util.Arrays;

/** 统一定时入口。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SopReminderSchedule {

    /** 首次执行 + 2 次补发：足够覆盖45分钟内的瞬时故障，再多说明是系统性问题，转人工。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 补发间隔。不设太短：同步失败要等下一轮，太短会反复打领星；太长会撞下一个时点。 */
    private static final java.time.Duration RETRY_DELAY = java.time.Duration.ofMinutes(15);

    private final SopReminderProperties properties;
    private final SopReminderService ruleService;
    private final SopReminderSendService sendService;
    private final SopReminderDispatchAttemptRepository attempts;

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
        // 轮次补偿：扫「该跑但没跑成」的时点。与上面消息级补偿互补——
        // retryDue 只补已经入队却发送失败的，候选查询就失败的那种一个批次都没建，只能靠这里。
        scheduler.scheduleWithFixedDelay(() -> {
            try { retryDueSlots(); }
            catch (Exception exception) { log.error("催办轮次补偿未完成：{}", exception.getMessage()); }
        }, java.time.Duration.ofMinutes(1));
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
        execute("定时催办", codes, slot, 1);
    }

    /**
     * 补发到期轮次。重跑必须沿用原来的 plannedAt，不能换成当前时间：
     * 发送去重按「同一 scheduledAt 已成功」判断，换了时点会把上一轮发过的人再发一遍。
     */
    private void retryDueSlots() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        for (SopReminderDispatchAttemptRepository.RetryAttempt attempt : attempts.dueRetries(now, MAX_ATTEMPTS)) {
            java.util.List<String> codes = Arrays.asList(attempt.nodeCodes().split(","));
            // 补发期间这一组节点可能已经跑过新的一轮（装箱每5分钟一次），新轮次会覆盖同样的待办，
            // 旧时点再补一遍就是重复打扰。这里再确认一次，避免和新轮次抢锁白跑一次全量查询。
            if (attempts.superseded(attempt.nodeCodes(), attempt.scheduledAt())) {
                log.info("定时催办补发跳过：nodes={}, scheduledAt={} 之后已有新轮次接手", codes, attempt.scheduledAt());
                attempts.abandon(attempt.nodeCodes(), attempt.scheduledAt(), "后续轮次已接管，无需补发");
                continue;
            }
            execute("定时催办补发", codes, attempt.scheduledAt(), attempt.attempts() + 1);
        }
    }

    /** 首轮与补发共用：记录轮次状态，失败时按次数决定是排下一次补发还是转人工。 */
    private void execute(String scene, java.util.List<String> codes, java.time.LocalDateTime slot, int attemptNo) {
        String nodeCodes = String.join(",", codes);
        java.time.LocalDateTime startedAt = java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        attempts.begin(nodeCodes, slot, startedAt);
        long started = System.nanoTime();
        log.info("{}开始：nodes={}, scheduledAt={}, attempt={}", scene, codes, slot, attemptNo);
        try {
            SopReminderSendService.SendResult sent = sendService.sendNodes(codes, slot, null);
            // 11 个节点一视同仁：跑完但仍有 UNKNOWN 就排下一次补发，直到全部确认为止。
            boolean needRetry = sent.unresolvedCount() > 0 && attemptNo < MAX_ATTEMPTS;
            // 用满次数仍有未决消息：不能再标 SUCCESS，否则「成功」这个状态就失去了「已全部确认」的含义，
            // 事后巡检也容易漏掉。标 GIVE_UP 转人工，让它出现在待核对清单里。
            boolean exhausted = sent.unresolvedCount() > 0 && attemptNo >= MAX_ATTEMPTS;
            if (exhausted) {
                log.error("{}仍有{}条发送结果未知，已达补发上限{}次，转人工核对", scene, sent.unresolvedCount(), MAX_ATTEMPTS);
                attempts.fail(nodeCodes, slot,
                        "补发" + MAX_ATTEMPTS + "次后仍有" + sent.unresolvedCount() + "条发送结果未知，需人工核对", null, true);
            } else {
                attempts.succeed(nodeCodes, slot, sent.todos(), sent.pushes(), sent.unresolvedCount(),
                        needRetry ? java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plus(RETRY_DELAY) : null);
            }
            log.info("{}执行完成：node={}, todos={}, pushes={}, skipped={}, unresolved={}",
                    scene, codes, sent.todos(), sent.pushes(), sent.skipped().size(), sent.unresolvedCount());
        } catch (Exception exception) {
            boolean giveUp = attemptNo >= MAX_ATTEMPTS;
            log.error("{}执行失败：nodes={}, attempt={}, giveUp={}", scene, codes, attemptNo, giveUp, exception);
            attempts.fail(nodeCodes, slot, exception.getMessage(),
                    java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plus(RETRY_DELAY), giveUp);
        } finally {
            log.info("{}结束：nodes={}, elapsedMs={}", scene, codes,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

}
