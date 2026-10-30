package com.scione.scm.bill.config;

import com.scione.scm.bill.domain.sop.enums.NodeCode;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一催办配置：全部数值来自Nacos，代码不提供业务默认值。
 * 必填项缺失时启动直接失败，避免用代码里的默认值掩盖漏配。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "sop.reminder")
public class SopReminderProperties {
    /** 必填：所有节点共同的PP创建日期下限，包含当天。 */
    private LocalDate ppStartDate;
    /** 必填：最近成功数据的最大年龄，从开始采集计时，不因当前有新同步运行而重置。 */
    private Duration maxSyncAge;
    /** 必填：撞上同步最多等待多久；超时停止，不用旧成功记录替代正在等待的这一轮。 */
    private Duration syncWaitTimeout;
    /** 必填：仅比较真实创建日志中的人员，不使用PO的opt_uid（采购员）冒充创建人。 */
    private List<String> allowedPoCreators;
    /** 必填：RF审批链按顺序填写3个实际领星UID，缺失时不猜当前人。 */
    private List<Long> financeApprovalUids;
    /** 必填：关联货件批次创建日期下限。 */
    private LocalDate packingStartDate;
    /** 发送配置：群机器人、外链地址、人员映射。 */
    private Send send = new Send();
    /** 必填：key使用业务节点编码；仅配置了的节点会被注册定时任务。 */
    private Map<NodeCode, Node> nodes;
    /** 可选：历史记录清理。不配置时不注册清理任务，库里的记录永不自动删除。 */
    private Purge purge;

    /** 启动即校验所有必填配置，缺一项直接抛异常，不用代码里的默认值兜底。 */
    @PostConstruct
    void validate() {
        require(ppStartDate != null, "sop.reminder.pp-start-date");//PP范围下限必填
        require(positive(maxSyncAge), "sop.reminder.max-sync-age");//数据最大年龄必填且>0
        require(positive(syncWaitTimeout), "sop.reminder.sync-wait-timeout");//同步等待超时必填且>0
        require(packingStartDate != null, "sop.reminder.packing-start-date");//装箱批次日期下限必填
        require(allowedPoCreators != null && !allowedPoCreators.isEmpty(), "sop.reminder.allowed-po-creators");//创建人白名单不能为空
        require(financeApprovalUids != null && financeApprovalUids.size() == 3
                        && new HashSet<>(financeApprovalUids).size() == 3
                        && financeApprovalUids.stream().allMatch(uid -> uid != null && uid > 0),
                "sop.reminder.finance-approval-uids（需3个不重复的正整数领星UID）");//财务审批链必须是3个不重复的正整数UID
        require(send != null, "sop.reminder.send");//发送配置整体必填
        require(hasText(send.getWebhookKey()), "sop.reminder.send.webhook-key");//群机器人key必填
        require(hasText(send.getFrontBaseUrl()), "sop.reminder.send.front-base-url");//外链根地址必填
        require(send.getRetryEnabled() != null, "sop.reminder.send.retry-enabled");//补偿开关必填
        require(send.getMaxAttempts() != null && send.getMaxAttempts() >= 1 && send.getMaxAttempts() <= 5,
                "sop.reminder.send.max-attempts（1~5）");//重试总次数1~5
        require(positive(send.getRetryDelay()), "sop.reminder.send.retry-delay");//重试间隔必填且>0
        require(positive(send.getRetryMaxAge()) && send.getRetryMaxAge().compareTo(send.getRetryDelay()) > 0,
                "sop.reminder.send.retry-max-age（需大于retry-delay）");//重试有效期必填且大于间隔
        require(nodes != null && !nodes.isEmpty(), "sop.reminder.nodes");//节点配置不能为空
        nodes.forEach((code, node) -> {
            require(node != null, "sop.reminder.nodes." + code);//每个节点配置对象不能为空
            require(node.getEnabled() != null, "sop.reminder.nodes." + code + ".enabled");//节点开关必填
            require(hasText(node.getCron()), "sop.reminder.nodes." + code + ".cron");//节点cron必填
            if (code == NodeCode.PO_CREATE) {
                require(hasText(node.getShopNamePrefix()), "sop.reminder.nodes." + code + ".shop-name-prefix");//PO_CREATE必须配店铺前缀
            }
            if (Set.of(NodeCode.PO_APPROVAL, NodeCode.RF_PAYMENT, NodeCode.CR_RECEIPT).contains(code)) {
                require(hasText(node.getReceiverKey()), "sop.reminder.nodes." + code + ".receiver-key");//固定角色节点必须配receiver-key
            }
            if (code == NodeCode.GOODS_READY) {
                require(node.getFirstDueDays() != null, "sop.reminder.nodes." + code + ".first-due-days");//货物备齐第一阶段天数必填
                require(node.getSecondDueDays() != null, "sop.reminder.nodes." + code + ".second-due-days");//货物备齐第二阶段天数必填
            }
            if (code == NodeCode.DELIVERY_CONFIRM) {
                require(node.getDeliveryDueDays() != null, "sop.reminder.nodes." + code + ".delivery-due-days");//交期确认天数必填
            }
        });
        // 不配置就不注册清理任务；一旦配置，每一项都必须写全，避免用代码默认值掩盖漏配。
        if (purge != null) {
            require(purge.getEnabled() != null, "sop.reminder.purge.enabled");//清理开关必填
            require(purge.getDryRun() != null, "sop.reminder.purge.dry-run");//预演开关必填
            require(purge.getClosedTodoDays() != null && purge.getClosedTodoDays() >= 0,
                    "sop.reminder.purge.closed-todo-days（≥0）");//已关闭待办明细保留天数≥0
            require(purge.getSkippedDays() != null && purge.getSkippedDays() >= 1,
                    "sop.reminder.purge.skipped-days（≥1）");//跳过记录保留天数≥1
            require(purge.getAttemptDays() != null && purge.getAttemptDays() >= 1,
                    "sop.reminder.purge.attempt-days（≥1）");//轮次记录保留天数≥1
            require(purge.getBatchSize() != null && purge.getBatchSize() >= 1 && purge.getBatchSize() <= 10_000,
                    "sop.reminder.purge.batch-size（1~10000）");//单批删除行数1~10000
            require(purge.getMaxRowsPerRun() != null && purge.getMaxRowsPerRun() >= purge.getBatchSize(),
                    "sop.reminder.purge.max-rows-per-run（需≥batch-size）");//单次总上限≥单批
        }
    }

    /** 判断Duration是否为正（非null、非负、非零），用于校验那些必须大于0的时长配置。 */
    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    /** 判断字符串是否非空（非null且去掉首尾空白后仍有内容）。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 配置校验的兜底：条件不满足就抛异常并带上配置项key，方便定位是哪一项配错了。 */
    private static void require(boolean condition, String key) {
        if (!condition) throw new IllegalStateException("催办配置缺失或非法：" + key);
    }

    @Data
    public static class Node {
        /** 必填：节点开关；false时定时入口跳过，补偿任务也不发送该节点的消息。 */
        private Boolean enabled;
        /** 必填：执行时点，Quartz表达式。 */
        private String cron;
        /** 仅PO_CREATE必填；其他节点不使用此字段。 */
        private String shopNamePrefix;
        /** PO_APPROVAL、RF_PAYMENT、CR_RECEIPT必填：固定责任人在send.people里的键。 */
        private String receiverKey;
        /** 可选：日志里没有“状态变更为X”文案时，靠这些操作名判定进入节点；默认空=不启用兜底判定。 */
        private List<String> entryLogTypes = List.of();
        /** DELIVERY_CONFIRM必填：PO进入待到货满N天才提醒。 */
        private Integer deliveryDueDays;
        /** GOODS_READY必填：距预计到货≤N天推第一次。 */
        private Integer firstDueDays;
        /** GOODS_READY必填：距预计到货≤N天且未建CR推第二次，须小于first-due-days。 */
        private Integer secondDueDays;
    }

    @Data
    public static class Send {
        /** 必填：失败补偿开关；只处理已确认可重试的批次。 */
        private Boolean retryEnabled;
        /** 必填：含首次发送的总次数，1~5。 */
        private Integer maxAttempts;
        /** 必填：失败后多久重试。 */
        private Duration retryDelay;
        /** 必填：失败消息最晚在多长时间内补发，需大于retry-delay。 */
        private Duration retryMaxAge;
        /** 必填：企微群机器人 webhook key，整条 URL 由客户端拼接；普通节点共用同一个群，关联货件使用SMTP。 */
        private String webhookKey;
        /** 必填：外链页面根地址，例如 http://52.5.112.152:3000。 */
        private String frontBaseUrl;
        /** 可选：key 是领星采购员UID（字符串）。 */
        private Map<String, Person> purchasers = new LinkedHashMap<>();
        /** 可选：role:PO_APPROVER、role:FINANCE_PAYER、role:RECEIVER、name:姓名、uid:领星UID。 */
        private Map<String, Person> people = new LinkedHashMap<>();
        /** 关联货件专用SMTP，不依赖企微自建应用。 */
        private PackingMail packingMail = new PackingMail();
    }

    /** 历史记录清理配置：按业务生命周期删除，避免推送记录表随时间无限增长。 */
    @Data
    public static class Purge {
        /** 必填：清理开关；false 时定时任务不注册，库里的记录永不自动删除。 */
        private Boolean enabled;
        /** 必填：预演开关；true 时只统计待删行数并打日志，不执行删除。 */
        private Boolean dryRun;
        /** 必填：已关闭待办的推送明细保留天数；待办关闭后再无催办价值，只留短期追溯窗口。 */
        private Integer closedTodoDays;
        /** 必填：未发送异常记录（record_type=3）保留天数；只用于排查，不参与去重和额度判断。 */
        private Integer skippedDays;
        /** 必填：轮次执行记录保留天数；补发只扫描当天，历史仅用于事后巡检。 */
        private Integer attemptDays;
        /** 必填：单批删除行数，控制单事务锁范围。 */
        private Integer batchSize;
        /** 必填：单次执行删除总上限；达到后停止，剩余部分下次继续。 */
        private Integer maxRowsPerRun;
    }

    @Data
    public static class PackingMail {
        private String host;//SMTP服务器地址
        private Integer port;//SMTP端口（SSL通常465）
        private String username;//发件邮箱账号
        private String password;//发件邮箱授权码
        private String from;//发件人地址（与username通常一致）
        private Boolean ssl;//是否启用SSL
        private Boolean starttls;//是否启用STARTTLS
    }

    /** 统一人员解析顺序：业务映射键、领星UID；查不到返回null（发送层跳过该责任人，不兜底）。 */
    public Person resolvePerson(String recipientKey,long recipientUid) {
        Person person=send.getPeople().get(recipientKey);
        if(person==null && recipientUid>0) person=send.getPurchasers().get(Long.toString(recipientUid));
        return person;
    }

    /** 领星UID与企微账号的对应关系；缺映射时跳过该责任人，不用UID伪造userid。 */
    @Data
    public static class Person {
        private String userid;//企微userid，实际发送对象
        private String name;//中文姓名，页面展示用
        private String mobile;//手机号，群机器人@人用
        /** 关联货件邮件收件地址，不能用手机号或企微userid猜测。 */
        private String email;
    }
}
