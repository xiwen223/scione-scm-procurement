package com.scione.scm.bill.domain.sop;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 接口返回的只读候选清单；Java record用于保存一组返回值，字段由构造时赋值。
 * 这些是内存中的返回对象，不是一组新的数据库表；不包含虚构的推送时间或发送次数。
 *
 * @param nodeCode 节点编码，参见NodeCode
 * @param nodeName 节点中文名称
 * @param calculatedAt 统一计算卡滞时长的本次北京时间
 * @param syncTaskId 本次PP数据对应的lx_sync_task_h.id，便于追溯来源
 * @param sourceSyncedAt 对应来源任务成功结束的时间，不是PP创建时间
 * @param shopIds 本次匹配到的杭州店铺sid
 * @param candidateCount 有效候选待办数量，不包含excluded中的问题单据
 * @param groups 按实际责任人汇总后的候选清单
 * @param excluded 本次排除的单据及原因，方便人工核查
 */
public record SopReminderPreview(
        String nodeCode, String nodeName, LocalDateTime calculatedAt,
        long syncTaskId, LocalDateTime sourceSyncedAt, List<Long> shopIds,
        int candidateCount, List<RecipientGroup> groups, List<Excluded> excluded) {

    /** 候选待办：businessSn是节点对应的业务单号（PP/PO/RF/CR/发货批次），ppCreatedAt是本轮节点起算时间。 */
    public record Candidate(String businessSn, long sid, String shopName,
                            LocalDateTime ppCreatedAt, BigDecimal stalledHours,
                            String businessType, String itemKey, String roundKey, int stage) {
        /** 保持节点1原有返回字段兼容；后续节点同样返回业务单号、进入时间及小时数。 */
        public Candidate(String businessSn, long sid, String shop, LocalDateTime entered, BigDecimal hours) {
            this(businessSn, sid, shop, entered, hours, "PP", "", "CREATE:" + entered, 1);
        }
    }

    /** 同一责任人的候选；recipientKey可以是uid、name或role，不能直接作为企微userid发送。 */
    public record RecipientGroup(long recipientUid, String recipientName, List<Candidate> candidates, String recipientKey) {
        public RecipientGroup(long uid, String name, List<Candidate> candidates) {
            this(uid, name, candidates, "uid:" + uid);
        }
    }

    /** 统一节点目录已迁移至独立枚举类：com.scione.scm.bill.domain.sop.enums.NodeCode。 */
    /** 问题单据，例如“创建时间缺失”；记录排除原因，不表示单据已经处理完成。 */
    public record Excluded(String businessSn, String reason) { }

    /** 从来源表读取的原始PP行；这里的purchaserUid确实是采购员，不把采购员UID误写为企微userid。 */
    public record PlanRow(String planSn, long sid, Long purchaserUid,
                          String purchaserName, LocalDateTime createdAt) { }

    /** 一次查询的数据包：来源任务ID、来源完成时间、原始PP行；仅供Service使用。 */
    public record Snapshot(long taskId, LocalDateTime syncedAt, List<PlanRow> plans) { }
}
