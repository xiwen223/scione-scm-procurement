package com.scione.scm.bill.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncOperationEnum;
import com.scione.scm.bill.domain.goods.enums.GoodsMessageTriggerEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncTargetEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.GoodsLocalMessageMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.CompanyGoodsMapper;
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
    @Resource
    private CompanyGoodsMapper goodsMapper;
    @Value("${scm.goods-sync.topic:scm-company-goods-create}")
    private String topic;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createImportMessages(CompanyGoodsPO goods) {
        saveMessages(goods, GoodsSyncOperationEnum.CREATE.getCode(), GoodsMessageTriggerEnum.IMPORT_CREATE, null);
    }

    /** 操作取修改前状态：0新增、1修改；不能在消费时读取已被置1的当前状态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createSyncMessages(CompanyGoodsPO goods, Integer previousStatus) {
        saveMessages(goods, previousStatus, GoodsMessageTriggerEnum.GOODS_MODIFY, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createSyncMessages(CompanyGoodsPO goods, Integer previousStatus, GoodsSyncDictionarySnapshot dictionaries) {
        saveMessages(goods, previousStatus, GoodsMessageTriggerEnum.GOODS_MODIFY, dictionaries);
    }

    private void saveMessages(CompanyGoodsPO goods, Integer previousStatus, GoodsMessageTriggerEnum trigger,
                              GoodsSyncDictionarySnapshot dictionaries) {
        if (previousStatus == null || (previousStatus != GoodsSyncOperationEnum.CREATE.getCode() && previousStatus != GoodsSyncOperationEnum.UPDATE.getCode())) {
            throw new IllegalStateException("货品同步前状态无效");
        }
        if (goods.getSyncScope() == null || (goods.getSyncScope() != 0 && goods.getSyncScope() != 1)) {
            throw new IllegalStateException("货品同步范围无效");
        }
        String snapshot;
        try {
            ObjectNode snapshotNode = objectMapper.valueToTree(goods);
            // 在货品写入的同一事务中固定组合关系，消费历史版本时不能读取最新关系。
            if (Integer.valueOf(1).equals(goods.getProductType())) {
                CompanyGoodsPO storedGoods = goodsMapper.findById(goods.getId());
                if (storedGoods == null) throw new IllegalStateException("组合货品不存在：" + goods.getSku());
                snapshotNode.put("groupList", storedGoods.getGroupList());
            }
            snapshotNode.put("syncOperation", previousStatus);
            if (dictionaries != null) snapshotNode.set("syncDictionaries", objectMapper.valueToTree(dictionarySnapshotForGoods(goods, dictionaries)));
            snapshot = objectMapper.writeValueAsString(snapshotNode);
        }
        catch (JsonProcessingException exception) { throw new IllegalStateException("货品同步快照序列化失败", exception); }
        for (GoodsSyncTargetEnum target : GoodsSyncTargetEnum.values()) {
            if (target == GoodsSyncTargetEnum.BLACKLAKE && goods.getSyncScope() == 0) continue;
            GoodsLocalMessagePO message = new GoodsLocalMessagePO();
            message.setMessageId(UUID.randomUUID().toString());
            message.setGoodsId(goods.getId());
            message.setSku(goods.getSku());
            message.setGoodsVersion(goods.getVersion());
            message.setTargetSystem(target.getCode());
            message.setTriggerType(trigger.getCode());
            message.setTopic(topic);
            message.setMessageKey(goods.getId().toString());
            message.setMessageBody(snapshot);
            if (messageMapper.insert(message) != 1) throw new IllegalStateException("货品同步消息保存失败");
        }
    }

    /** 每条消息只存该货品需要的字典项，避免完整供应商列表被重复写入数千次。 */
    private GoodsSyncDictionarySnapshot dictionarySnapshotForGoods(CompanyGoodsPO goods, GoodsSyncDictionarySnapshot dictionaries) {
        GoodsSyncDictionarySnapshot snapshot = new GoodsSyncDictionarySnapshot();
        for (var supplier : dictionaries.getSuppliers()) {
            if (java.util.Objects.equals(goods.getSupplierName(), supplier.supplierName())) {
                snapshot.getSuppliers().add(supplier);
                break;
            }
        }
        for (var route : dictionaries.getProcessRoutings()) {
            if (java.util.Objects.equals(goods.getProcessRoute(), route.processRoutingName())) {
                snapshot.getProcessRoutings().add(route);
                break;
            }
        }
        ArrayNode templates = objectMapper.createArrayNode();
        if (dictionaries.getQcTemplates() != null) {
            for (JsonNode template : dictionaries.getQcTemplates()) {
                if (java.util.Objects.equals(goods.getQualityTemplate(), template.path("name").asText())) {
                    templates.add(template);
                    break;
                }
            }
        }
        snapshot.setQcTemplates(templates);
        String warehouseCode = dictionaries.getWarehouseCodes().get(goods.getDefaultWarehouse());
        if (warehouseCode != null) snapshot.getWarehouseCodes().put(goods.getDefaultWarehouse(), warehouseCode);
        return snapshot;
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
