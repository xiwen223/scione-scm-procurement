package com.scione.scm.bill.kafka.goods;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.kafka.producer.ScioneKafkaProducer;
import com.scione.scm.bill.application.CompanyGoodsMessageService;
import com.scione.scm.bill.domain.goods.enums.GoodsMessageStatusEnum;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncTargetEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.GoodsLocalMessageMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.GoodsLocalMessagePO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 只发送已提交的本地消息；Kafka只携带messageId，消费时读取原始快照。 */
@Component
@Slf4j
public class CompanyGoodsMessagePublisher {
    @Resource
    private GoodsLocalMessageMapper messageMapper;
    @Resource
    private CompanyGoodsMessageService messageService;
    @Resource
    private ScioneKafkaProducer producer;
    @Resource
    private ObjectMapper objectMapper;
    @Value("${scm.goods-sync.topic:scm-company-goods-create}")
    private String topic;

    @Scheduled(fixedDelay = 3000, initialDelay = 10000, scheduler = "goodsMessageScheduler")
    public void publishPending() {
        for (GoodsLocalMessagePO stale : messageMapper.findStaleConsumers(topic)) {
            messageService.fail(stale, "消费处理超过30分钟，远端结果可能未知，请核对后人工处理");
        }
        for (GoodsLocalMessagePO message : messageMapper.findPublishCandidates(topic)) {
            if (!childrenReady(message)) continue;
            if (messageMapper.claimPublish(message.getMessageId()) != 1) continue;
            try {
                producer.send(message.getTopic(), message.getMessageKey(), message.getMessageId()).get(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                messageMapper.publishFailed(message.getMessageId());
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException exception) {
                messageMapper.publishFailed(message.getMessageId());
                log.warn("货品消息发送失败或确认超时，messageId={}", message.getMessageId());
                continue;
            }
            messageMapper.publishSucceeded(message.getMessageId());
        }
    }

    private boolean childrenReady(GoodsLocalMessagePO message) {
        if (message.getTargetSystem() != GoodsSyncTargetEnum.LINGXING.getCode()) return true;
        List<String> childSkus = new ArrayList<>();
        try {
            JsonNode snapshot = objectMapper.readTree(message.getMessageBody());
            JsonNode relations = objectMapper.readTree(snapshot.path("groupList").asText());
            if (!relations.isArray()) throw new IllegalStateException("子SKU快照不是数组");
            for (JsonNode relation : relations) childSkus.add(relation.path("sku").asText());
        } catch (JsonProcessingException exception) {
            messageService.fail(message, "子SKU快照无法解析");
            return false;
        }
        if (childSkus.isEmpty()) return true;
        for (GoodsLocalMessagePO child : messageMapper.findChildMessages(childSkus, message.getId())) {
            if (child.getConsumeStatus() == GoodsMessageStatusEnum.FAILED.getCode()) {
                messageService.fail(message, "关联子SKU同步领星失败：" + child.getSku());
                return false;
            }
            if (child.getConsumeStatus() != GoodsMessageStatusEnum.SUCCEEDED.getCode()) return false;
        }
        return true;
    }
}
