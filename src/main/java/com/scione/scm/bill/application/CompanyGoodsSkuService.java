package com.scione.scm.bill.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.CompanyGoodsMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.apache.commons.lang3.StringUtils;
import java.util.*;

/** 改码时保留数量及其他关联属性，在保存货品和同步消息前维护引用。 */
@Service
public class CompanyGoodsSkuService {
    @Resource
    private CompanyGoodsMapper mapper;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 校验待保存SKU并维护主货品关联引用，按名称回填空编码且保留原比例数量。
     *
     * @param goods 本次待保存的货品集合，方法会更新其中的SKU及关联JSON
     * @param originals 以货品数据库主键为键的修改前记录
     */
    public void prepareChanges(List<CompanyGoodsPO> goods, Map<Long, CompanyGoodsPO> originals) {
        Map<String, String> replacements = new LinkedHashMap<>();
        Map<String, Map<String, String>> childSkusByProductId = new LinkedHashMap<>();
        Set<String> submittedSkus = new HashSet<>();
        Map<Long, CompanyGoodsPO> submitted = new LinkedHashMap<>();
        Set<Long> parentIds = new TreeSet<>();
        for (CompanyGoodsPO updated : goods) {
            CompanyGoodsPO original = originals.get(updated.getId());
            String sku = updated.getSku() == null ? original.getSku() : updated.getSku().trim();
            if (StringUtils.isBlank(sku) || sku.length() > 128) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "SKU编码不能为空且不能超过128字符");
            }
            if (!submittedSkus.add(sku.toLowerCase(Locale.ROOT))) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "SKU编码不允许重复");
            }
            updated.setSku(sku);
            submitted.put(updated.getId(), updated);
            if (Integer.valueOf(0).equals(updated.getProductType()) && StringUtils.isNotBlank(updated.getProductId())) {
                String productId = updated.getProductId().trim().toLowerCase(Locale.ROOT);
                Map<String, String> childSkus = childSkusByProductId.get(productId);
                if (childSkus == null) {
                    childSkus = new LinkedHashMap<>();
                    childSkusByProductId.put(productId, childSkus);
                }
                // 同时支持本次修改名称和历史关联仍保留旧名称的情况。
                for (String name : Arrays.asList(original.getGoodsName(), updated.getGoodsName())) {
                    if (StringUtils.isBlank(name)) continue;
                    String key = name.trim().toLowerCase(Locale.ROOT);
                    String existingSku = childSkus.put(key, sku);
                    if (existingSku != null && !existingSku.equals(sku)) {
                        throw new BusinessException(ResultCode.PARAM_ERROR, "同一产品ID下子货品名称重复，无法回填SKU：" + name);
                    }
                }
                parentIds.addAll(mapper.findProductParentIds(updated.getProductId(), updated.getId()));
            }
            if (Objects.equals(sku, original.getSku())) continue;
            for (CompanyGoodsPO conflict : mapper.lockImportConflicts(sku, updated.getGoodsName())) {
                if (!conflict.getId().equals(updated.getId()) && sku.equalsIgnoreCase(conflict.getSku())) {
                    throw new BusinessException(ResultCode.DATABASE_DUPLICATE_KEY, "SKU编码已存在：" + sku);
                }
            }
            if (StringUtils.isNotBlank(original.getSku())) {
                replacements.put(original.getSku(), sku);
                parentIds.addAll(mapper.findParentIds(original.getSku(), original.getId()));
            }
        }
        if (replacements.isEmpty() && childSkusByProductId.isEmpty()) return;
        for (CompanyGoodsPO updated : goods) {
            updated.setGroupList(replaceReferences(updated, replacements, childSkusByProductId));
        }
        for (Long parentId : parentIds) {
            if (submitted.containsKey(parentId)) continue;
            CompanyGoodsPO parent = mapper.lockById(parentId);
            if (parent == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "关联主货品不存在");
            String relations = replaceReferences(parent, replacements, childSkusByProductId);
            if (Objects.equals(relations, parent.getGroupList())) continue;
            parent.setGroupList(relations);
            if (mapper.updateSkuRelations(parent, parent.getVersion()) != 1) {
                throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
            }
        }
    }

    /**
     * 按同产品ID下子货品名称或旧SKU替换关联编码，保留数量及其他关联属性。
     *
     * @param parent 维护关联或展示数量的主货品记录
     * @param replacements 旧SKU到新SKU的替换映射
     * @param childSkusByProductId 按归一化产品ID及子货品名称索引的新SKU映射
     * @return 更新后的关联JSON；没有变化时返回原字符串
     */
    private String replaceReferences(CompanyGoodsPO parent, Map<String, String> replacements,
                                     Map<String, Map<String, String>> childSkusByProductId) {
        String relations = parent.getGroupList();
        if (StringUtils.isBlank(relations)) return relations;
        Map<String, String> childSkus = null;
        if (Integer.valueOf(1).equals(parent.getProductType()) && StringUtils.isNotBlank(parent.getProductId())) {
            childSkus = childSkusByProductId.get(parent.getProductId().trim().toLowerCase(Locale.ROOT));
        }
        try {
            JsonNode items = objectMapper.readTree(relations);
            if (!items.isArray()) throw new BusinessException(ResultCode.DATABASE_ERROR, "货品关联数据格式错误");
            boolean changed = false;
            for (JsonNode item : items) {
                if (!item.isObject()) continue;
                String replacement = null;
                String name = item.path("goods_name").asText();
                if (childSkus != null && StringUtils.isNotBlank(name)) {
                    replacement = childSkus.get(name.trim().toLowerCase(Locale.ROOT));
                }
                if (replacement == null) replacement = replacements.get(item.path("sku").asText());
                if (replacement != null && !replacement.equals(item.path("sku").asText())) {
                    ((ObjectNode) item).put("sku", replacement);
                    changed = true;
                }
            }
            return changed ? objectMapper.writeValueAsString(items) : relations;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new BusinessException(ResultCode.DATABASE_ERROR, "货品关联数据格式错误");
        }
    }
}
