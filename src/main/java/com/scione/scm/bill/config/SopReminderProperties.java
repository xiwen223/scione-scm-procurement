package com.scione.scm.bill.config;

import com.scione.scm.bill.domain.sop.SopReminderPreview.NodeCode;
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
    private Source source = new Source();
    /** 必填：仅比较真实创建日志中的人员，不使用PO的opt_uid（采购员）冒充创建人。 */
    private List<String> allowedPoCreators;
    /** 必填：RF审批链按顺序填写3个实际领星UID，缺失时不猜当前人。 */
    private List<Long> financeApprovalUids;
    /** 必填：关联货件批次创建日期下限。 */
    private LocalDate packingStartDate;
    /** 发送配置：群机器人、外链地址、人员映射。 */
    private Send send = new Send();
    /** 必填：key使用业务节点编码；仅配置了的节点会被注册定时任务。 */
    private Map<String, Node> nodes;

    /** 启动即校验，缺一项直接抛异常，不用代码里的默认值兜底。 */
    @PostConstruct
    void validate() {
        require(ppStartDate != null, "sop.reminder.pp-start-date");
        require(positive(maxSyncAge), "sop.reminder.max-sync-age");
        require(positive(syncWaitTimeout), "sop.reminder.sync-wait-timeout");
        require(packingStartDate != null, "sop.reminder.packing-start-date");
        require(allowedPoCreators != null && !allowedPoCreators.isEmpty(), "sop.reminder.allowed-po-creators");
        require(financeApprovalUids != null && financeApprovalUids.size() == 3
                        && new HashSet<>(financeApprovalUids).size() == 3
                        && financeApprovalUids.stream().allMatch(uid -> uid != null && uid > 0),
                "sop.reminder.finance-approval-uids（需3个不重复的正整数领星UID）");
        require(source != null && hasText(source.getExpectedDatabase()), "sop.reminder.source.expected-database");
        require(send != null, "sop.reminder.send");
        require(hasText(send.getWebhookKey()), "sop.reminder.send.webhook-key");
        require(hasText(send.getFrontBaseUrl()), "sop.reminder.send.front-base-url");
        require(send.getTestEnabled() != null, "sop.reminder.send.test-enabled");
        require(send.getRetryEnabled() != null, "sop.reminder.send.retry-enabled");
        require(send.getMaxAttempts() != null && send.getMaxAttempts() >= 1 && send.getMaxAttempts() <= 5,
                "sop.reminder.send.max-attempts（1~5）");
        require(positive(send.getRetryDelay()), "sop.reminder.send.retry-delay");
        require(positive(send.getRetryMaxAge()) && send.getRetryMaxAge().compareTo(send.getRetryDelay()) > 0,
                "sop.reminder.send.retry-max-age（需大于retry-delay）");
        require(nodes != null && !nodes.isEmpty(), "sop.reminder.nodes");
        nodes.forEach((code, node) -> {
            require(node != null, "sop.reminder.nodes." + code);
            require(node.getEnabled() != null, "sop.reminder.nodes." + code + ".enabled");
            require(hasText(node.getCron()), "sop.reminder.nodes." + code + ".cron");
            if (NodeCode.PO_CREATE.name().equals(code)) {
                require(hasText(node.getShopNamePrefix()), "sop.reminder.nodes." + code + ".shop-name-prefix");
            }
            if (Set.of("PO_APPROVAL", "RF_PAYMENT", "CR_RECEIPT").contains(code)) {
                require(hasText(node.getReceiverKey()), "sop.reminder.nodes." + code + ".receiver-key");
            }
            if (NodeCode.GOODS_READY.name().equals(code)) {
                require(node.getFirstDueDays() != null, "sop.reminder.nodes." + code + ".first-due-days");
                require(node.getSecondDueDays() != null, "sop.reminder.nodes." + code + ".second-due-days");
            }
            if (NodeCode.DELIVERY_CONFIRM.name().equals(code)) {
                require(node.getDeliveryDueDays() != null, "sop.reminder.nodes." + code + ".delivery-due-days");
            }
        });
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

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
    public static class Source {
        /** 留空复用SCM数据源；配置独立连接时三项必须同时填写。 */
        private String jdbcUrl;
        private String username;
        private String password;
        /** 必填：防止读错数据库，但仍需由实际数据源配置保证连接测试环境。 */
        private String expectedDatabase;
    }

    @Data
    public static class Send {
        /** 必填：手动测试发送接口开关，上线前置false。 */
        private Boolean testEnabled;
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
        /** 可选：仅测试模式下people与purchasers均未匹配时使用；正式模式缺映射不发送。 */
        private Person fallbackPerson;
        /** 关联货件专用SMTP，不依赖企微自建应用。 */
        private PackingMail packingMail = new PackingMail();
    }

    @Data
    public static class PackingMail {
        private String host;
        private Integer port;
        private String username;
        private String password;
        private String from;
        private Boolean ssl;
        private Boolean starttls;
    }

    /** 统一人员解析顺序：业务映射键、领星UID；仅测试模式允许兜底。发送和实时复核共用。 */
    public Person resolvePerson(String recipientKey,long recipientUid) {
        Person person=send.getPeople().get(recipientKey);
        if(person==null && recipientUid>0) person=send.getPurchasers().get(Long.toString(recipientUid));
        return person!=null?person:Boolean.TRUE.equals(send.getTestEnabled())?send.getFallbackPerson():null;
    }

    /** 领星UID与企微账号的对应关系；缺映射时跳过该责任人，不用UID伪造userid。 */
    @Data
    public static class Person {
        private String userid;
        private String name;
        private String mobile;
        /** 关联货件邮件收件地址，不能用手机号或企微userid猜测。 */
        private String email;
    }
}
