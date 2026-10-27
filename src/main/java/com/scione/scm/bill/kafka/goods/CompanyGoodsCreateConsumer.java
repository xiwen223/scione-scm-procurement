package com.scione.scm.bill.kafka.goods;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncOperationEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scione.scm.bill.application.CompanyGoodsExternalCreateService;
import com.scione.scm.bill.application.CompanyGoodsMessageService;
import com.scione.scm.bill.application.GoodsSyncDictionarySnapshot;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.common.ExternalApiResponseException;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncTargetEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.GoodsLocalMessageMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.GoodsLocalMessagePO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CompanyGoodsCreateConsumer {
    @Resource
    private GoodsLocalMessageMapper messageMapper;
    @Resource
    private CompanyGoodsMessageService messageService;
    @Resource
    private CompanyGoodsExternalCreateService externalCreateService;
    @Resource
    private ObjectMapper objectMapper;

    @KafkaListener(topics = "${scm.goods-sync.topic:scm-company-goods-create}",
            groupId = "${scm.goods-sync.group-id:scm-company-goods-create-consumer}",
            containerFactory = "goodsSyncKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        GoodsLocalMessagePO message = messageMapper.findByMessageId(record.value());
        if (message == null) throw new IllegalStateException("货品同步消息记录不存在");
        if (!message.getTopic().equals(record.topic()) || !message.getMessageKey().equals(record.key())) {
            throw new IllegalStateException("货品同步消息Topic或Key不一致");
        }
        // 同一messageId只有一个消费者能取得处理权，已成功或失败的重复消息直接确认。
        if (messageMapper.claimConsume(message.getMessageId()) != 1) return;
        try {
            JsonNode body = objectMapper.readTree(message.getMessageBody());
            if (!(body instanceof ObjectNode snapshot)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "货品消息快照必须是JSON对象");
            }
            JsonNode operation = snapshot.remove("syncOperation");
            // 历史消息没有操作字段，保留原来的新增语义。
            int syncOperation = GoodsSyncOperationEnum.CREATE.getCode();
            if (operation != null) {
                if (!operation.isIntegralNumber() || !operation.canConvertToInt()) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "货品消息同步操作无效");
                }
                syncOperation = operation.intValue();
            }
            if (syncOperation != GoodsSyncOperationEnum.CREATE.getCode() && syncOperation != GoodsSyncOperationEnum.UPDATE.getCode()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "货品消息同步操作无效");
            }
            JsonNode dictionaryNode = snapshot.remove("syncDictionaries");
            GoodsSyncDictionarySnapshot dictionaries = null;
            if (dictionaryNode != null) {
                if (!dictionaryNode.isObject()) throw new BusinessException(ResultCode.PARAM_ERROR, "货品字典快照必须是JSON对象");
                dictionaries = objectMapper.treeToValue(dictionaryNode, GoodsSyncDictionarySnapshot.class);
            }
            CompanyGoodsPO goods = objectMapper.treeToValue(snapshot, CompanyGoodsPO.class);
            if (message.getTargetSystem() == GoodsSyncTargetEnum.LINGXING.getCode()) {
                if (syncOperation == GoodsSyncOperationEnum.CREATE.getCode()) externalCreateService.createInLingxing(goods, dictionaries);
                else externalCreateService.updateInLingxing(goods, dictionaries);
            } else if (message.getTargetSystem() == GoodsSyncTargetEnum.BLACKLAKE.getCode()) {
                if (syncOperation == GoodsSyncOperationEnum.CREATE.getCode()) externalCreateService.createInBlacklake(goods, dictionaries);
                else externalCreateService.updateInBlacklake(goods, dictionaries);
            } else throw new IllegalStateException("货品同步目标系统无效");
        } catch (BusinessException exception) {
            String failureMessage = exception.getMessage();
            if (exception instanceof ExternalApiResponseException responseException) {
                failureMessage = responseException.getResponseBody();
            }
            messageService.fail(message, failureMessage);
            log.warn("货品外部同步失败，messageId={}, goodsId={}, target={}", message.getMessageId(), message.getGoodsId(), message.getTargetSystem());
            return;
        } catch (JsonProcessingException exception) {
            messageService.fail(message, "货品消息快照无法解析，请核对本地消息记录");
            return;
        }
        // 结果写库异常必须向外抛出，不能伪装消费成功；处理中记录不自动重复调用外部写入。
        if (messageMapper.consumeSucceeded(message.getMessageId()) != 1) throw new IllegalStateException("货品同步消费结果保存失败");
        log.info("货品外部同步成功，messageId={}, goodsId={}, target={}", message.getMessageId(), message.getGoodsId(), message.getTargetSystem());
    }
}
