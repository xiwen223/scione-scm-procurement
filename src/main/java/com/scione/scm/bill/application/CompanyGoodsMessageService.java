package com.scione.scm.bill.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncTargetEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.GoodsLocalMessageMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.GoodsLocalMessagePO;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 货品与消息同事务落库；消费结果和异常标记同事务更新。 */
@Service
public class CompanyGoodsMessageService {
    @Resource
    private GoodsLocalMessageMapper messageMapper;
    @Resource
    private ObjectMapper objectMapper;
    @Value("${scm.goods-sync.topic:scm-company-goods-create}")
    private String topic;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createImportMessages(CompanyGoodsPO goods) {
        if (goods.getSyncScope() == null || (goods.getSyncScope() != 0 && goods.getSyncScope() != 1)) {
            throw new IllegalStateException("货品同步范围无效");
        }
        String snapshot;
        try { snapshot = objectMapper.writeValueAsString(goods); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("货品同步快照序列化失败", exception); }
        for (GoodsSyncTargetEnum target : GoodsSyncTargetEnum.values()) {
            if (target == GoodsSyncTargetEnum.BLACKLAKE && goods.getSyncScope() == 0) continue;
            GoodsLocalMessagePO message = new GoodsLocalMessagePO();
            message.setMessageId(UUID.randomUUID().toString());
            message.setGoodsId(goods.getId());
            message.setSku(goods.getSku());
            message.setGoodsVersion(goods.getVersion());
            message.setTargetSystem(target.getCode());
            message.setTopic(topic);
            message.setMessageKey(goods.getId().toString());
            message.setMessageBody(snapshot);
            if (messageMapper.insert(message) != 1) throw new IllegalStateException("货品同步消息保存失败");
        }
    }

    @Transactional
    public void fail(GoodsLocalMessagePO message, String reason) {
        if (messageMapper.consumeFailed(message.getMessageId(), reason) == 1) {
            if (messageMapper.markGoodsException(message.getGoodsId()) != 1) {
                throw new IllegalStateException("同步失败后更新货品异常标记失败");
            }
        }
    }
}
