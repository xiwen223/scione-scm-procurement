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

    /**
     * 根据消息ID领取本地消息，解析快照并按目标系统同步，记录成功或失败结果。
     *
     * @param record Kafka收到的消息记录，消息值为本地业务消息ID
     */
    @KafkaListener(topics = "${scm.goods-sync.topic:scm-company-goods-create}",
            groupId = "${scm.goods-sync.group-id:scm-company-goods-create-consumer}",
            containerFactory = "goodsSyncKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        log.info("收到货品同步消息，messageId={}, topic={}, partition={}, offset={}",
                record.value(), record.topic(), record.partition(), record.offset());
        GoodsLocalMessagePO message = messageMapper.findByMessageId(record.value());
        if (message == null) throw new IllegalStateException("货品同步消息记录不存在");
        if (!message.getTopic().equals(record.topic()) || !message.getMessageKey().equals(record.key())) {
            throw new IllegalStateException("货品同步消息Topic或Key不一致");
        }
        // 同一messageId只有一个消费者能取得处理权，已成功或失败的重复消息直接确认。
        if (messageMapper.claimConsume(message.getMessageId()) != 1) {
            log.info("跳过未取得处理权的货品同步消息，messageId={}, goodsId={}, target={}, observedConsumeStatus={}",
                    message.getMessageId(), message.getGoodsId(), message.getTargetSystem(), message.getConsumeStatus());
            return;
        }
        try {
            log.info("开始处理货品同步消息，messageId={}, goodsId={}, sku={}, target={}",
                    message.getMessageId(), message.getGoodsId(), message.getSku(), message.getTargetSystem());
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
            JsonNode checkBlacklakeProductExists = snapshot.remove("checkBlacklakeProductExists");
            if (checkBlacklakeProductExists != null && !checkBlacklakeProductExists.isBoolean()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "黑湖产品存在性检查标记无效");
            }
            JsonNode dictionaryNode = snapshot.remove("syncDictionaries");
            GoodsSyncDictionarySnapshot dictionaries = null;
            if (dictionaryNode != null) {
                if (!dictionaryNode.isObject()) throw new BusinessException(ResultCode.PARAM_ERROR, "货品字典快照必须是JSON对象");
                dictionaries = objectMapper.treeToValue(dictionaryNode, GoodsSyncDictionarySnapshot.class);
            }
            CompanyGoodsPO goods = objectMapper.treeToValue(snapshot, CompanyGoodsPO.class);
            if (message.getTargetSystem() == GoodsSyncTargetEnum.LINGXING.getCode()) {
                log.info("执行领星货品同步，messageId={}, sku={}, operation={}",
                        message.getMessageId(), goods.getSku(), syncOperation);
                if (syncOperation == GoodsSyncOperationEnum.CREATE.getCode()) {
                    externalCreateService.createInLingxing(goods, dictionaries);
                } else {
                    externalCreateService.updateInLingxing(goods, dictionaries);
                }
            } else if (message.getTargetSystem() == GoodsSyncTargetEnum.BLACKLAKE.getCode()) {
                log.info("执行黑湖货品同步，messageId={}, sku={}", message.getMessageId(), goods.getSku());
                // 黑湖始终按远端实际存在情况选择新增或修改，不依赖本地货品状态。
                externalCreateService.syncInBlacklake(goods, dictionaries);
            } else throw new IllegalStateException("货品同步目标系统无效");
        } catch (BusinessException exception) {
            String failureMessage = exception.getMessage();
            if (exception instanceof ExternalApiResponseException responseException) {
                failureMessage = responseException.getResponseBody();
            }
            log.error("货品外部同步失败，messageId={}, goodsId={}, sku={}, target={}",
                    message.getMessageId(), message.getGoodsId(), message.getSku(), message.getTargetSystem(), exception);
            messageService.fail(message, failureMessage);
            return;
        } catch (JsonProcessingException exception) {
            log.error("货品同步消息解析失败，messageId={}, goodsId={}, target={}",
                    message.getMessageId(), message.getGoodsId(), message.getTargetSystem(), exception);
            messageService.fail(message, "货品消息快照无法解析，请核对本地消息记录");
            return;
        } catch (RuntimeException exception) {
            log.error("货品同步执行异常，messageId={}, goodsId={}, sku={}, target={}",
                    message.getMessageId(), message.getGoodsId(), message.getSku(), message.getTargetSystem(), exception);
            throw exception;
        }
        // 结果写库异常必须向外抛出，不能伪装消费成功；处理中记录不自动重复调用外部写入。
        messageService.succeed(message);
        log.info("货品外部同步成功，messageId={}, goodsId={}, target={}", message.getMessageId(), message.getGoodsId(), message.getTargetSystem());
    }
}
