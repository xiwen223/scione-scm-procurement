package com.scione.scm.bill.domain.sop;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import com.scione.scm.bill.domain.sop.SopReminderPreview.PlanRow;
import com.scione.scm.bill.domain.sop.SopReminderPreview.Snapshot;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 催办来源数据仓储端口：只读小时同步发布的快照，不写任何业务表。
 * 快照校验（同步锁、任务状态、数据时效）属于实现层职责，调用方只描述要读什么。
 */
public interface SopReminderSourceRepository {

    /** 由统一入口校验后的来源标识，各节点都可使用。 */
    record PublishedSource(long taskId, LocalDateTime syncedAt) { }

    record Event(LocalDateTime time, String type, String detail, Long uid, String actor) { }

    record ActiveContract(long id, String poSn, LocalDateTime createdAt) { }

    record NodeData(PublishedSource published, Map<String, JsonNode> documents,
                    Map<String, List<Event>> logs, Map<String, ActiveContract> contracts,
                    Map<String, DeliveryFollowUp> followUps) { }

    record ReceiptState(String poSn, int status) { }

    record CompletionFacts(Map<String, ActiveContract> contracts, Map<String, DeliveryFollowUp> followUps,
                           Map<String, ReceiptState> receipts, LocalDateTime collectedAt) { }

    /**
     * shopIds：Service从领星店铺接口筛出的杭州sid集合，不是前端随意传进来的店铺范围。
     * now：Service固定的本次北京时间，用于判断同步结果是否过期。
     */
    Snapshot readPoCreate(List<Long> shopIds, LocalDateTime now);

    /** 标准节点共享同一个已发布快照，只读_h来源和现有合同/人工信息表。 */
    NodeData readNodeData(LocalDateTime now);

    /** 小范围读取合同/人工信息和CR基线，不等同步锁、不重读全部单据及日志。 */
    CompletionFacts readCompletionFacts(List<String> poSns);

    /** 仅供后端网页接口客户端使用，不返回给前端，不记录Cookie内容。 */
    String readSellerCookies();
}
