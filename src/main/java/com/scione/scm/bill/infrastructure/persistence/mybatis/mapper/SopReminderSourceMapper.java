package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import com.scione.scm.bill.domain.sop.SopReminderPreview.PlanRow;
import com.scione.scm.bill.domain.sop.SopReminderSourceRepository.ActiveContract;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 催办来源快照的只读 Mapper：只查小时同步的 _h 表和现有合同/人工信息表，不写任何表。
 * 发布校验（同步锁、任务成功、进度一致、数据时效）由仓储实现编排，SQL 全部在这里。
 */
public interface SopReminderSourceMapper {

    record SyncTaskRow(long id, String taskType, String status, String errorMsg,
                       LocalDateTime startTime, LocalDateTime endTime) { }

    record SyncStateRow(long sourceCount, LocalDateTime minUpdatedUntil, LocalDateTime maxUpdatedUntil) { }

    record DocumentRow(String businessId, String rawPayload) { }

    record BusinessLogRow(String businessId, LocalDateTime olTime, String olTypeText,
                          String olDetail, Long olUid, String realname) { }

    record ReceiptRow(String orderSn, String poSn, int status) { }

    /** 最新一轮小时同步，不看历史成功记录：失败或仍在running都必须停止。 */
    SyncTaskRow selectLatestIncrementalTask();

    /** 五个来源进度必须一致，且落在本轮任务开始和结束之间。 */
    SyncStateRow selectSyncStateSummary();

    LocalDateTime selectLatestSuccessCollectedAt();

    /** 节点1SQL：待采购/部分采购PP、杭州店铺及全部SKU。 */
    List<PlanRow> selectPoCreatePlans(@Param("shopIds") List<Long> shopIds, @Param("createdFrom") LocalDateTime createdFrom);

    List<DocumentRow> selectDocuments();

    List<BusinessLogRow> selectBusinessLogs();

    /** poSns为null表示读取全部；空列表由调用方短路，不执行SQL。 */
    List<ActiveContract> selectContracts(@Param("poSns") List<String> poSns);

    List<DeliveryFollowUp> selectFollowUps(@Param("poSns") List<String> poSns);

    List<ReceiptRow> selectReceipts(@Param("poSns") List<String> poSns);

    String selectLatestSellerCookies();
}
