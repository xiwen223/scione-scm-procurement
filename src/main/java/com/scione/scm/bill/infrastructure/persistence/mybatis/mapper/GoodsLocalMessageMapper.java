package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.GoodsLocalMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface GoodsLocalMessageMapper {
    int insert(@Param("message") GoodsLocalMessagePO message);
    GoodsLocalMessagePO findByMessageId(@Param("messageId") String messageId);
    List<GoodsLocalMessagePO> findPublishCandidates(@Param("topic") String topic);
    int claimPublish(@Param("messageId") String messageId);
    int publishSucceeded(@Param("messageId") String messageId);
    int publishFailed(@Param("messageId") String messageId);
    int claimConsume(@Param("messageId") String messageId);
    int consumeSucceeded(@Param("messageId") String messageId);
    int consumeFailed(@Param("messageId") String messageId, @Param("reason") String reason);
    int markGoodsException(@Param("goodsId") Long goodsId);
    int clearGoodsException(@Param("goodsId") Long goodsId, @Param("goodsVersion") Long goodsVersion);
    List<GoodsLocalMessagePO> lockVersionMessages(@Param("goodsId") Long goodsId, @Param("goodsVersion") Long goodsVersion);
    List<GoodsLocalMessagePO> findChildMessages(@Param("skus") List<String> skus, @Param("beforeId") Long beforeId);
    List<GoodsLocalMessagePO> findStaleConsumers(@Param("topic") String topic);
}
