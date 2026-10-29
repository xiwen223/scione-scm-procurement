package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.GoodsLocalMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface GoodsLocalMessageMapper {
    /**
     * 插入单个目标系统的货品同步消息，并回填消息表主键。
     *
     * @param message 当前需要投递或更新处理结果的本地同步消息
     * @return 插入行数
     */
    int insert(@Param("message") GoodsLocalMessagePO message);
    /**
     * 按业务消息ID读取同步消息。
     *
     * @param messageId 本地同步消息的业务唯一ID
     * @return 消息记录；不存在时返回null
     */
    GoodsLocalMessagePO findByMessageId(@Param("messageId") String messageId);
    /**
     * 查询可投递消息，按同货品及同目标的历史未完成任务阻挡并限制每轮100条。
     *
     * @param topic 货品同步Kafka Topic名称
     * @return 按消息表主键升序排列的候选消息
     */
    List<GoodsLocalMessagePO> findPublishCandidates(@Param("topic") String topic);
    /**
     * 原子领取符合投递条件的消息并增加投递次数。
     *
     * @param messageId 本地同步消息的业务唯一ID
     * @return 领取成功为1，否则为0
     */
    int claimPublish(@Param("messageId") String messageId);
    /**
     * 将正在发送的消息标记为投递成功并记录发送时间。
     *
     * @param messageId 本地同步消息的业务唯一ID
     * @return 受影响行数
     */
    int publishSucceeded(@Param("messageId") String messageId);
    /**
     * 将仍未消费的发送中消息标记失败，供后续重新投递。
     *
     * @param messageId 本地同步消息的业务唯一ID
     * @return 受影响行数
     */
    int publishFailed(@Param("messageId") String messageId);
    /**
     * 原子将待消费消息改为处理中，并增加消费次数。
     *
     * @param messageId 本地同步消息的业务唯一ID
     * @return 领取成功为1，已被领取或不符合条件时为0
     */
    int claimConsume(@Param("messageId") String messageId);
    /**
     * 将处理中消息标记为消费成功并清除历史错误信息。
     *
     * @param messageId 本地同步消息的业务唯一ID
     * @return 受影响行数
     */
    int consumeSucceeded(@Param("messageId") String messageId);
    /**
     * 将待消费或处理中消息标记失败并保存错误详情。
     *
     * @param messageId 本地同步消息的业务唯一ID
     * @param reason 待保存或展示的失败原因
     * @return 受影响行数
     */
    int consumeFailed(@Param("messageId") String messageId, @Param("reason") String reason);
    /**
     * 将指定货品的外部同步异常标记置为1。
     *
     * @param goodsId 货品数据库主键
     * @return 受影响行数
     */
    int markGoodsException(@Param("goodsId") Long goodsId);
    /**
     * 仅在货品版本一致时清除同步异常标记，不修改冲突标记。
     *
     * @param goodsId 货品数据库主键
     * @param goodsVersion 消息对应的货品版本号
     * @return 受影响行数
     */
    int clearGoodsException(@Param("goodsId") Long goodsId, @Param("goodsVersion") Long goodsVersion);
    /**
     * 锁定货品指定版本的消息并倒序返回，供最新目标状态判断。
     *
     * @param goodsId 货品数据库主键
     * @param goodsVersion 消息对应的货品版本号
     * @return 按消息表主键倒序排列的同步消息
     */
    List<GoodsLocalMessagePO> lockVersionMessages(@Param("goodsId") Long goodsId, @Param("goodsVersion") Long goodsVersion);
    /**
     * 查询指定SKU在父消息之前最近的一条领星同步消息。
     *
     * @param skus 待匹配的SKU编码集合
     * @param beforeId 作为查询上界的父消息表主键，不包含该消息
     * @return 每个匹配子SKU的最近领星消息列表
     */
    List<GoodsLocalMessagePO> findChildMessages(@Param("skus") List<String> skus, @Param("beforeId") Long beforeId);
    /**
     * 查找消费超过30分钟仍在处理的消息，供超时转人工核对。
     *
     * @param topic 货品同步Kafka Topic名称
     * @return 本轮最多100条超时消费消息
     */
    List<GoodsLocalMessagePO> findStaleConsumers(@Param("topic") String topic);
}
