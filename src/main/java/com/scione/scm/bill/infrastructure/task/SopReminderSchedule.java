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

    /** 首次执行 + 2 次补发，总共尝试三次。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 两次尝试之间隔15分钟。 */
    private static final java.time.Duration RETRY_DELAY = java.time.Duration.ofMinutes(15);

    private final SopReminderProperties properties;
    private final SopReminderService ruleService;
    private final SopReminderSendService sendService;
    private final SopReminderDispatchAttemptRepository attempts;
    private final com.scione.scm.bill.domain.sop.SopReminderRepository repository;

    // 等待同步可能很久，催办使用独立线程池，不占用合同等模块的默认调度线程。
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @PostConstruct
    public void registerTasks() {
        scheduler.setPoolSize(Math.max(2, properties.getNodes().size() + 1));//线程数=节点数+1，给重试留一个线程
        scheduler.setThreadNamePrefix("reminder-");//线程名加个前缀
        scheduler.initialize();//创建底层线程池
        //注册3个每分钟跑一次的补偿任务。1.补普通节点的失败消息，2.补装箱的失败消息
        for (boolean packing : new boolean[]{false, true}) {
            scheduler.scheduleWithFixedDelay(() -> {//retryDue(false)和retryDue(true)都是一轮跑完歇1分钟再跑，重跑普通节点和装箱节点中明确失败的单条消息，普通和装箱分别有一把琐
                try { sendService.retryDue(packing); }
                catch (Exception exception) { log.error("催办补偿未完成：packing={}, {}", packing, exception.getMessage()); }
            }, java.time.Duration.ofMinutes(1));
        }
        //3.补整轮没跑起来的时点
        scheduler.scheduleWithFixedDelay(() -> {
            try { retryDueSlots(); }//retryDueSlots()用什么锁取决于这次补的是哪一组节点，补普通组就使用普通锁，补装箱就使用装箱锁
            catch (Exception exception) { log.error("催办轮次补偿未完成：{}", exception.getMessage()); }
        }, java.time.Duration.ofMinutes(1));

        //每天清理历史记录。23:15 是同步的确定空档：小时同步22:40已停，全量同步0:00才开始同步。
        SopReminderProperties.Purge purge = properties.getPurge();
        if (purge != null && Boolean.TRUE.equals(purge.getEnabled())) {
            log.info("催办历史清理已启用：每日23:15，预演={}，关闭待办明细保留{}天，跳过记录{}天，轮次{}天",
                    purge.getDryRun(), purge.getClosedTodoDays(), purge.getSkippedDays(), purge.getAttemptDays());
            scheduler.schedule(() -> {
                try { purgeExpired(); }
                catch (Exception exception) { log.error("催办历史清理未完成：{}", exception.getMessage(), exception); }
            }, new CronTrigger("0 15 23 * * ?", ZoneId.of("Asia/Shanghai")));
        }

        // 每个节点可有不同的执行时点，全部使用北京时间；修改cron/增加节点后重启SCM。
        java.util.Map<String, String> crons = new java.util.LinkedHashMap<>();
        properties.getNodes().forEach((nodeCode, node) -> {
            if (!ruleService.supportsNode(nodeCode)) {//遍历nocas中配的所有节点，如果配置了没实现的节点就启动失败
                throw new IllegalStateException("配置了尚未实现的催办节点：" + nodeCode);
            }
            CronExpression.parse(node.getCron());//cron写错就启动失败
            crons.put(nodeCode, node.getCron());
        });
        // 装箱有独立调度入口：普通节点等待同步时，不阻塞下一轮装箱触发。
        String packingCron = crons.remove("PACKING_RELATE");//把装箱那条从map中摘出来，摘到了则单独注册
        if (packingCron != null) registerLane(java.util.Map.of("PACKING_RELATE", packingCron));
        if (!crons.isEmpty()) registerLane(crons);//剩下的普通节点不为空的话再注册一组
    }

    private void registerLane(java.util.Map<String, String> crons) {//crons就是节点代码->cron表达式的映射
        // 普通节点仍按同一时点汇总；装箱单独计算下次执行时间。
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> nextSlot = new java.util.concurrent.atomic.AtomicReference<>();
        scheduler.schedule(() -> runSlot(nextSlot.get(), crons), context -> {//scheduler.schedule(task, trigger)，task为到时间了要做的任务，trigger回答下次几点，task跑完之后去调用第二次trigger
            java.time.Instant next = crons.values().stream().distinct()//() -> runSlot(nextSlot.get(), crons) 是一个 Runnable，到点被调用一次。传进去的是整组 crons，不是单个节点。runSlot 拿它去逐个筛选。
                    .<java.time.Instant>map(cron -> new CronTrigger(cron, ZoneId.of("Asia/Shanghai")).nextExecution(context))
                    .filter(java.util.Objects::nonNull).min(java.time.Instant::compareTo).orElse(null);//取出每个cron并计算下次时间，取时间最近的那个去排下次唤醒，醒来后再重新算一遍，取下下一个。
            nextSlot.set(next);
            return next;
        });
    }

    //应用关闭时停掉线程池，避免消息发到一半被强杀。
    @PreDestroy
    public void stop() {
        scheduler.shutdown();
    }

    //触发器唤醒，回答：这一刻到底该发哪几个节点
    private void runSlot(java.time.Instant instant, java.util.Map<String, String> crons) {
        if (instant == null) return;
        java.time.LocalDateTime slot = java.time.LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));//这里不能用now()，线程池刚好忙，9:00的任务9:00:03才轮到的话，会直接计算到下一个时间节点发
        java.util.List<String> codes = crons.entrySet().stream().filter(entry -> {
            SopReminderProperties.Node node = properties.getNodes().get(entry.getKey());
            return node != null && Boolean.TRUE.equals(node.getEnabled())//要满足三个条件才发：1.这个节点还配在nacos中。2.节点开关是开着的。3.这个节点的cron正好指向此刻。
                    && slot.equals(CronExpression.parse(entry.getValue()).next(slot.minusSeconds(1)));
        }).map(java.util.Map.Entry::getKey).toList();
        if (codes.isEmpty()) return;
        execute("定时催办", codes, slot, 1);//没有节点就退出，有就发。
    }

    /**
     * 查出一整轮没跑成功的去进行重跑，一轮=一个时点+一组节点=reminder_dispatch_attempt 表里的一行记录、
     * 这边会查这个表，表中会记录node_codes代表这次推送的节点，scheduled_at代表正常推送的时间，status代表推送的状态如failed，success等，attempts代表已经试了几次，next_retry_at代表重试的时间，不用重试的话这个字段就是空
     */
    private void retryDueSlots() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        for (SopReminderDispatchAttemptRepository.RetryAttempt attempt : attempts.dueRetries(now, MAX_ATTEMPTS)) {//dueRetries去查表，查出next_retry_at已经到了、并且attempts还没到 3 次的行。、
            //循环里做三件事。1.知道要补哪些节点：node_codes 列里存的是 "GOODS_READY,RF_CREATE,..." 这种逗号拼起来的字符串，拆开变成列表，才知道要补这 10 个节点。
            //2.检查有没有更新的轮次已经干过了，如果已经干过了，补偿任务就标记abondon跳过。3.execute真正去跑
            java.util.List<String> codes = Arrays.asList(attempt.nodeCodes().split(","));
            if (attempts.superseded(attempt.nodeCodes(), attempt.scheduledAt())) {
                log.info("定时催办补发跳过：nodes={}, scheduledAt={} 之后已有新轮次接手", codes, attempt.scheduledAt());
                attempts.abandon(attempt.nodeCodes(), attempt.scheduledAt(), "后续轮次已接管，无需补发");
                continue;
            }
            execute("定时催办补发", codes, attempt.scheduledAt(), attempt.attempts() + 1);
        }
    }

    /**
     * 按业务生命周期清理历史记录。
     * 待办一旦关闭就不会再被催办，它的推送明细对去重和节点额度判断都已无用，只保留短期追溯窗口；
     * 未发送异常记录和轮次记录只用于事后排查，超过保留期即可删除。
     * 待办本身和卡滞原因历史永不在这里删除。
     */
    private void purgeExpired() {
        SopReminderProperties.Purge purge = properties.getPurge();
        if (purge == null || !Boolean.TRUE.equals(purge.getEnabled())) return;
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        java.time.LocalDateTime closedBefore = now.minusDays(purge.getClosedTodoDays());
        java.time.LocalDateTime skippedBefore = now.minusDays(purge.getSkippedDays());
        java.time.LocalDateTime attemptBefore = now.minusDays(purge.getAttemptDays());
        int batchSize = purge.getBatchSize();
        int maxRows = purge.getMaxRowsPerRun();

        if (Boolean.TRUE.equals(purge.getDryRun())) {
            // 预演只统计，不删：先确认数量级符合预期，再关掉 dry-run 正式执行。
            log.info("[预演模式，未删除任何数据] 催办历史清理待删：关闭待办明细={}, 跳过记录={}, 轮次={}",
                    repository.countClosedTodoDetails(closedBefore),
                    repository.countSkippedRecords(skippedBefore),
                    attempts.countBefore(attemptBefore));
            return;
        }

        long started = System.nanoTime();
        long details = purgeInBatches("关闭待办明细", batchSize, maxRows,
                limit -> repository.purgeClosedTodoDetails(closedBefore, limit));
        long skipped = purgeInBatches("跳过记录", batchSize, maxRows,
                limit -> repository.purgeSkippedRecords(skippedBefore, limit));
        long rounds = purgeInBatches("轮次", batchSize, maxRows,
                limit -> attempts.purgeBefore(attemptBefore, limit));
        log.info("催办历史清理完成：关闭待办明细={}, 跳过记录={}, 轮次={}, elapsedMs={}",
                details, skipped, rounds,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    /** 分批删除：单批事务小，批间让出锁，避免一次删几万行长时间占用行锁并拖慢主从同步。 */
    private long purgeInBatches(String label, int batchSize, int maxRows,
                                java.util.function.IntUnaryOperator deleteBatch) {
        long total = 0;
        while (total < maxRows) {
            int affected = deleteBatch.applyAsInt(batchSize);
            total += affected;
            // 不足一批说明已经删干净；等于一批则可能还有，继续下一批。
            if (affected < batchSize) return total;
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.info("催办历史清理被中断：{}={}", label, total);
                return total;
            }
        }
        log.warn("催办历史清理达到单次上限：{}={}，剩余部分下次继续", label, total);
        return total;
    }

    /** 真正执行推送，首轮与补发共用。
     * scene：区分是首轮还是补发。
     * codes：这一轮要补发哪几个节点。
     * slot：这一轮的业务时点。
     * attemptNo：这是第几次重试。
     * */
    private void execute(String scene, java.util.List<String> codes, java.time.LocalDateTime slot, int attemptNo) {
        String nodeCodes = String.join(",", codes); // 把 ["GOODS_READY","RF_CREATE"] 拼成 "GOODS_READY,RF_CREATE"，作为 attempt 表的主键一部分
        java.time.LocalDateTime startedAt = java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai"));//真正开始跑的时刻
        attempts.begin(nodeCodes, slot, startedAt);// 往 attempt 表插一行，状态=running。这轮的身份是 (node_codes, scheduled_at)
        long started = System.nanoTime();
        log.info("{}开始：nodes={}, scheduledAt={}, attempt={}", scene, codes, slot, attemptNo);
        try {
            //返回的 SendResult 里有几个数：todos（建了几条待办）、pushes（发了几条消息）、skipped（跳过几条）、unresolvedCount（发出去但结果未知的消息条数）。
            SopReminderSendService.SendResult sent = sendService.sendNodes(codes, slot, null);
            // 如果跑完但仍有 UNKNOWN 且尝试次数还没到3次的，就需要补发，重试时间设置在15分钟后。
            boolean needRetry = sent.unresolvedCount() > 0 && attemptNo < MAX_ATTEMPTS;
            // 如果跑了3次还有UNKNOWN状态的，也不能标记成功，就会把状态置为GIVE_UP。
            boolean exhausted = sent.unresolvedCount() > 0 && attemptNo >= MAX_ATTEMPTS;
            if (exhausted) {
                log.error("{}仍有{}条发送结果未知，已达补发上限{}次，转人工核对", scene, sent.unresolvedCount(), MAX_ATTEMPTS);
                attempts.fail(nodeCodes, slot,//3次还失败就置为fail，不安排重试了
                        "补发" + MAX_ATTEMPTS + "次后仍有" + sent.unresolvedCount() + "条发送结果未知，需人工核对", null, true);
            } else {
                attempts.succeed(nodeCodes, slot, sent.todos(), sent.pushes(), sent.unresolvedCount(),//否则的话就继续重试
                        needRetry ? java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plus(RETRY_DELAY) : null);
            }
            log.info("{}执行完成：node={}, todos={}, pushes={}, skipped={}, unresolved={}",
                    scene, codes, sent.todos(), sent.pushes(), sent.skipped().size(), sent.unresolvedCount());
        } catch (Exception exception) {//这里是一整轮都失败了，比如库连不上等，也是最多重试三次，没到 3 次：状态=failed，next_retry_at=15 分钟后，等 retryDueSlots 补；已到 3 次：giveUp=true，不再排下次，转人工。
            boolean giveUp = attemptNo >= MAX_ATTEMPTS;
            log.error("{}执行失败：nodes={}, attempt={}, giveUp={}", scene, codes, attemptNo, giveUp, exception);
            attempts.fail(nodeCodes, slot, exception.getMessage(),
                    java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plus(RETRY_DELAY), giveUp);
        } finally {//记录耗时日志
            log.info("{}结束：nodes={}, elapsedMs={}", scene, codes,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

}
