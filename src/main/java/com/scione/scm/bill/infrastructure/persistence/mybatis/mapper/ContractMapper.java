package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.application.dto.ContractListQueryRequest;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.math.BigDecimal;

/**
 * 合同主表 Mapper。
 */
public interface ContractMapper {
    /** 只回写模板关联；条件更新避免覆盖并发修改的合同类型或模板。 */
    @org.apache.ibatis.annotations.Update("UPDATE contract c JOIN contract_template t ON t.id=#{newTemplateId} SET c.template_id=t.id,c.update_time=NOW() WHERE c.id=#{id} AND c.is_deleted=0 AND c.contract_type=#{contractType} AND c.template_id <=> #{oldTemplateId} AND t.contract_type=c.contract_type AND t.is_default=1 AND t.is_active=1 AND t.is_deleted=0")
    int rebindDefaultTemplate(@Param("id") Long id, @Param("contractType") Integer contractType,
                             @Param("oldTemplateId") Long oldTemplateId, @Param("newTemplateId") Long newTemplateId);
    @org.apache.ibatis.annotations.Update("UPDATE contract SET status=2,sign_launch_state='QUEUED', sign_launch_error=NULL, sign_launch_operator=#{operator}, update_time=NOW() WHERE id=#{id} AND status=1 AND is_deleted=0 AND COALESCE(sign_launch_state,'') NOT IN ('QUEUED','RUNNING','WAIT_CALLBACK','UNKNOWN')")
    // 受理成功即 status=2、QUEUED；原子条件避免重复点击把同一合同排队两次。
    int enqueueSign(@Param("id") Long id, @Param("operator") String operator);

    @org.apache.ibatis.annotations.Select("SELECT id FROM contract WHERE sign_launch_state='QUEUED' AND status=2 AND is_deleted=0 ORDER BY update_time,id LIMIT 4")
    // 每轮只取少量待发起 ID，外部调用由 Worker 执行，不在调度线程里逐个上传。
    List<Long> queuedSigns();

    @org.apache.ibatis.annotations.Select("SELECT id FROM contract WHERE sign_launch_state='WAIT_CALLBACK' AND status=2 AND is_deleted=0 AND fadada_task_id IS NOT NULL ORDER BY update_time,id LIMIT 4")
    // 只查询已取得任务 ID 的等待回调记录；UNKNOWN 不在这个兜底查询集合中。
    List<Long> waitingSignCallbacks();

    @org.apache.ibatis.annotations.Update("UPDATE contract SET update_time=NOW() WHERE id=#{id} AND status=2 AND sign_launch_state='WAIT_CALLBACK'")
    // 更新时间让下一轮优先处理较久未查询的合同；这里只排序，不表示签署已成功。
    int touchWaitingSign(@Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE contract SET sign_launch_state='RUNNING',update_time=NOW() WHERE id=#{id} AND status=2 AND is_deleted=0 AND sign_launch_state='QUEUED'")
    // 只有从 QUEUED 改为 RUNNING 的执行者才获得处理权；返回 0 表示已被领取或状态变化。
    int claimSign(@Param("id") Long id);

    @org.apache.ibatis.annotations.Select("SELECT sign_launch_operator FROM contract WHERE id=#{id}")
    String signOperator(@Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE contract SET status=CASE WHEN #{state}='FAILED' THEN 1 ELSE status END,sign_launch_state=#{state},sign_launch_error=#{error},update_time=NOW() WHERE id=#{id} AND status=2 AND sign_launch_state='RUNNING'")
    // FAILED 恢复创建以便修改重试；UNKNOWN 保持签署中以防重复建任务，且仅更新仍在 RUNNING 的记录。
    int failSign(@Param("id") Long id, @Param("state") String state, @Param("error") String error);

    @org.apache.ibatis.annotations.Update("UPDATE contract SET fadada_task_id=#{taskId},sign_launch_state='DONE',sign_launch_error=NULL,sign_start_time=COALESCE(sign_start_time,NOW()),update_time=NOW() WHERE id=#{id} AND status=2 AND is_deleted=0 AND sign_launch_state IN ('RUNNING','WAIT_CALLBACK','UNKNOWN') AND (fadada_task_id IS NULL OR fadada_task_id=#{taskId})")
    // 我方盖章确认后清掉发起过程标记，仍等待供方签署；限制 taskId，不能用旧任务回调覆盖新任务。
    int confirmSignLaunch(@Param("id") Long id, @Param("taskId") String taskId);

    /**
     * 插入合同（回填主键 id）。
     */
    int insert(ContractPO contract);

    /**
     * 唯一性校验：查询指定采购单号是否已有非取消状态的合同。
     *
     * @param purchaseOrderNo 采购单号
     * @return 存在返回 1，不存在返回 0
     */
    int existsActiveByPurchaseOrderNo(@Param("purchaseOrderNo") String purchaseOrderNo);

    /**
     * 查询指定采购单号名下最新的有效合同（只取 id / contract_no / status 三列）。
     *
     * @param purchaseOrderNo 采购单号
     * @return 合同 PO（只填三列）；没有则 null
     */
    ContractPO findActiveByPurchaseOrderNo(@Param("purchaseOrderNo") String purchaseOrderNo);

    /**
     * 按合同编号查询。
     *
     * @param contractNo 合同编号
     * @return 合同 PO
     */
    ContractPO findByContractNo(@Param("contractNo") String contractNo);

    String findContractNoByFadadaTaskId(@Param("taskId") String taskId);

    String findFadadaAbolishedTaskId(@Param("id") Long id);

    /**
     * 合同编号唯一性校验（排除自身）。不过滤 is_deleted —— 逻辑删除的行仍占用唯一索引。
     *
     * @param contractNo 待校验的合同编号
     * @param excludeId  排除的合同 ID（自身）
     * @return 被其它合同占用返回 1，否则 0
     */
    int countByContractNoExcludingId(@Param("contractNo") String contractNo, @Param("excludeId") Long excludeId);

    /**
     * 更新合同的 PDF URL。
     *
     * @param id 合同 ID
     * @param pdfUrl PDF 文件 URL
     * @return 影响行数
     */
    int updatePdfUrl(@Param("id") Long id, @Param("pdfUrl") String pdfUrl);

    int saveCancellationSnapshot(@Param("id") Long id, @Param("expectedStatus") int expectedStatus,
                                 @Param("taskId") String taskId, @Param("originalPdfUrl") String originalPdfUrl,
                                 @Param("snapshotUrl") String snapshotUrl);

    int markSigning(@Param("id") Long id, @Param("fadadaTaskId") String fadadaTaskId);

    int cancel(@Param("id") Long id, @Param("cancelReason") String cancelReason);

    // 发起解除协议仅保存协议任务 ID 和原因，主状态暂不改为取消。
    int markFadadaAbolishPending(@Param("id") Long id, @Param("abolishedTaskId") String abolishedTaskId,
                                 @Param("cancelReason") String cancelReason);

    // 解除协议完成才将履行中改为取消；不能在我方刚签完时提前结束合同。
    int markFadadaAbolished(@Param("id") Long id, @Param("cancelReason") String cancelReason);

    int markExecuting(@Param("id") Long id);
    int markCompleted(@Param("id") Long id);
    List<ContractPO> selectExecutingContracts();


    /**
     * 分页查询合同列表（不含明细）。
     *
     * @param request 查询条件
     * @return 合同 PO 列表
     */
    List<ContractPO> selectByPage(@Param("req") ContractListQueryRequest request);

    /**
     * 统计查询条件下的合同总数。
     *
     * @param request 查询条件
     * @return 总数
     */
    long countByCondition(@Param("req") ContractListQueryRequest request);

    /**
     * 按 ID 查询合同。
     *
     * @param id 合同 ID
     * @return 合同 PO
     */
    ContractPO selectById(@Param("id") Long id);

    /**
     * 按 ID 更新合同。
     *
     * @param contract 合同 PO
     * @return 影响行数
     */
    int updateById(ContractPO contract);

    int updateAmounts(@Param("id") Long id,
                      @Param("originalAmount") BigDecimal originalAmount,
                      @Param("discountedAmount") BigDecimal discountedAmount,
                      @Param("contractAmount") BigDecimal contractAmount);
}
