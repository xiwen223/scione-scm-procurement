package com.scione.scm.bill.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncOperationEnum;
import com.scione.scm.bill.domain.goods.enums.GoodsMessageStatusEnum;
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
import java.util.List;
import java.util.ArrayList;
import com.scione.scm.bill.infrastructure.lingxing.LingxingOpenApiClient;

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

    /**
     * 在货品保存事务中创建以新增操作触发的目标系统同步消息。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createImportMessages(CompanyGoodsPO goods) {
        saveMessages(goods, GoodsSyncOperationEnum.CREATE.getCode(), GoodsMessageTriggerEnum.IMPORT_CREATE, null, null);
    }

    /**
     *  操作取修改前状态：0新增、1修改；不能在消费时读取已被置1的当前状态。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param previousStatus 修改前货品状态，0表示新增同步，1表示修改同步
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createSyncMessages(CompanyGoodsPO goods, Integer previousStatus) {
        saveMessages(goods, previousStatus, GoodsMessageTriggerEnum.GOODS_MODIFY, null, null);
    }

    /**
     * 根据修改前状态和保存后的同步范围，在当前事务内保存目标系统同步消息。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param previousStatus 修改前货品状态，0表示新增同步，1表示修改同步
     * @param dictionaries 本次操作共享字典快照；为null时由同步适配层按需实时查询
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createSyncMessages(CompanyGoodsPO goods, Integer previousStatus, GoodsSyncDictionarySnapshot dictionaries) {
        saveMessages(goods, previousStatus, GoodsMessageTriggerEnum.GOODS_MODIFY, dictionaries, null);
    }

    /**
     *  页面编辑及导入扩大同步范围时，消费端先确认黑湖是否已存在该SKU。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param previousStatus 修改前货品状态，0表示新增同步，1表示修改同步
     * @param previousScope 修改前同步范围；0领星、1领星黑湖，未提供时不记录范围变更标识
     * @param dictionaries 本次操作共享字典快照；为null时由同步适配层按需实时查询
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createSyncMessages(CompanyGoodsPO goods, Integer previousStatus, Integer previousScope,
                                   GoodsSyncDictionarySnapshot dictionaries) {
        saveMessages(goods, previousStatus, GoodsMessageTriggerEnum.GOODS_MODIFY, dictionaries, previousScope);
    }

    /**
     * 固定货品版本、组合数量及字典快照，按同步范围为每个目标系统插入一条消息。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param previousStatus 修改前货品状态，0表示新增同步，1表示修改同步
     * @param trigger 本地消息的业务触发来源
     * @param dictionaries 本次操作共享字典快照；为null时由同步适配层按需实时查询
     * @param previousScope 修改前同步范围；0领星、1领星黑湖，未提供时不记录范围变更标识
     */
    private void saveMessages(CompanyGoodsPO goods, Integer previousStatus, GoodsMessageTriggerEnum trigger,
                              GoodsSyncDictionarySnapshot dictionaries, Integer previousScope) {
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
            if (Integer.valueOf(0).equals(previousScope) && Integer.valueOf(1).equals(goods.getSyncScope())) {
                snapshotNode.put("checkBlacklakeProductExists", true);
            }
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

    /**
     *  每条消息只存该货品需要的字典项，避免完整供应商列表被重复写入数千次。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param dictionaries 本次操作共享字典快照；为null时由同步适配层按需实时查询
     * @return 仅保留当前货品所需匹配项的字典快照
     */
    private GoodsSyncDictionarySnapshot dictionarySnapshotForGoods(CompanyGoodsPO goods, GoodsSyncDictionarySnapshot dictionaries) {
        GoodsSyncDictionarySnapshot snapshot = new GoodsSyncDictionarySnapshot();
        if (dictionaries.getAccounts() != null) {
            List<LingxingOpenApiClient.AccountVO> accounts = new ArrayList<>();
            if (goods.getProductOwnerNames() != null) {
                for (String owner : goods.getProductOwnerNames().split(",")) {
                    for (var account : dictionaries.getAccounts()) {
                        if (owner.trim().equals(account.realname())) {
                            accounts.add(account);
                            break;
                        }
                    }
                }
            }
            snapshot.setAccounts(accounts);
        }
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

    /**
     * 记录消息消费失败，仅在版本及目标系统仍有效时设置货品异常标记。
     *
     * @param message 当前需要投递或更新处理结果的本地同步消息
     * @param reason 待保存或展示的失败原因
     */
    @Transactional
    public void fail(GoodsLocalMessagePO message, String reason) {
        CompanyGoodsPO goods = goodsMapper.lockById(message.getGoodsId());
        if (goods == null) throw new IllegalStateException("同步结果对应货品不存在");
        if (messageMapper.consumeFailed(message.getMessageId(), reason) == 1) {
            // 历史版本或已移出同步范围的目标失败，只记录消息，不污染当前货品状态。
            boolean requiredTarget = Integer.valueOf(0).equals(message.getTargetSystem())
                    || (Integer.valueOf(1).equals(goods.getSyncScope()) && Integer.valueOf(1).equals(message.getTargetSystem()));
            if (java.util.Objects.equals(goods.getVersion(), message.getGoodsVersion()) && requiredTarget) {
                for (GoodsLocalMessagePO current : messageMapper.lockVersionMessages(goods.getId(), goods.getVersion())) {
                    if (!java.util.Objects.equals(current.getTargetSystem(), message.getTargetSystem())) continue;
                    if (current.getMessageId().equals(message.getMessageId()) && !Integer.valueOf(1).equals(goods.getExceptionStatus())
                            && messageMapper.markGoodsException(message.getGoodsId()) != 1) {
                        throw new IllegalStateException("同步失败后更新货品异常标记失败");
                    }
                    break;
                }
            }
        }
    }

    /**
     * 记录消息消费成功，当前版本所需目标均成功后清除货品同步异常。
     *
     * @param message 当前需要投递或更新处理结果的本地同步消息
     */
    @Transactional
    public void succeed(GoodsLocalMessagePO message) {
        // 两个系统完成时锁定同一货品，保证后完成的一方能看到另一方已提交的结果。
        CompanyGoodsPO goods = goodsMapper.lockById(message.getGoodsId());
        if (goods == null) throw new IllegalStateException("同步结果对应货品不存在");
        if (messageMapper.consumeSucceeded(message.getMessageId()) != 1) {
            throw new IllegalStateException("货品同步消费结果保存失败");
        }
        if (!java.util.Objects.equals(goods.getVersion(), message.getGoodsVersion())) return;
        if (!Integer.valueOf(0).equals(goods.getSyncScope()) && !Integer.valueOf(1).equals(goods.getSyncScope())) return;
        List<GoodsLocalMessagePO> currentMessages = messageMapper.lockVersionMessages(goods.getId(), goods.getVersion());
        boolean lingxingSucceeded = false;
        boolean blacklakeSucceeded = false;
        java.util.Set<Integer> checkedTargets = new java.util.HashSet<>();
        // 按消息ID倒序，只检查当前版本每个目标系统的最新任务。
        for (GoodsLocalMessagePO current : currentMessages) {
            int target = current.getTargetSystem();
            if (target != GoodsSyncTargetEnum.LINGXING.getCode() && target != GoodsSyncTargetEnum.BLACKLAKE.getCode()) continue;
            if (target == GoodsSyncTargetEnum.BLACKLAKE.getCode() && goods.getSyncScope() == 0) continue;
            if (!checkedTargets.add(target)) continue;
            if (!Integer.valueOf(GoodsMessageStatusEnum.SUCCEEDED.getCode()).equals(current.getConsumeStatus())) return;
            if (target == GoodsSyncTargetEnum.LINGXING.getCode()) lingxingSucceeded = true;
            else blacklakeSucceeded = true;
        }
        if (lingxingSucceeded && (goods.getSyncScope() == 0 || blacklakeSucceeded)) {
            messageMapper.clearGoodsException(goods.getId(), goods.getVersion());
        }
    }
}
