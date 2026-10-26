package com.scione.scm.bill.application;

import cn.hutool.core.collection.CollectionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.application.dto.*;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.goods.enums.BlacklakeProductCategoryEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.CompanyGoodsMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 企业商品库创建及修改主子货品，仅保存本地，不创建外部同步消息。 */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
public class CompanyGoodsLocalSaveService {
    @Resource
    private CompanyGoodsMapper goodsMapper;
    @Resource
    private ObjectMapper objectMapper;

    public Long create(CompanyGoodsCreateDTO request) {
        List<CompanyGoodsPO> children = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        Set<String> names = new HashSet<>();
        checkRequestDuplicates(request.getSku(), request.getGoodsName(), skus, names);
        if (CollectionUtil.isNotEmpty(request.getGroupList())) {
            for (CompanyGoodsChildCreateDTO child : request.getGroupList()) {
                checkRequestDuplicates(child.getSku(), child.getGoodsName(), skus, names);
                children.add(prepare(child, null, List.of()));
            }
        }
        List<CompanyGoodsRelationDTO> relations = new ArrayList<>();
        for (int index = 0; index < children.size(); index++) {
            CompanyGoodsPO child = children.get(index);
            CompanyGoodsRelationDTO relation = new CompanyGoodsRelationDTO();
            relation.setSku(child.getSku());
            relation.setGoodsName(child.getGoodsName());
            relation.setQuantity(request.getGroupList().get(index).getQuantity());
            relations.add(relation);
        }
        // 先校验所有货品，再在同一事务内写入；创建关系不要求子货品预先存在。
        CompanyGoodsPO goods = prepare(request, null, List.of());
        try {
            goods.setGroupList(objectMapper.writeValueAsString(relations));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("子货品关联序列化失败", exception);
        }
        goods.setProductType(CollectionUtil.isEmpty(children) ? 0 : 1);
        for (CompanyGoodsPO child : children) {
            if (goodsMapper.insertLocal(child) != 1) throw invalid("子货品创建失败");
        }
        if (goodsMapper.insertLocal(goods) != 1) throw invalid("货品创建失败");
        return goods.getId();
    }

    public void modify(CompanyGoodsModifyDTO request) {
        if (request.getId() == null) throw invalid("修改货品时id不能为空");
        CompanyGoodsPO stored = goodsMapper.findById(request.getId());
        if (stored == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "货品不存在");
        Long expectedVersion = validateModification(request, request.getVersion(), stored);
        CompanyGoodsPO goods = prepare(request, stored, null);
        List<CompanyGoodsPO> children = new ArrayList<>();
        List<Long> childVersions = new ArrayList<>();
        List<CompanyGoodsRelationDTO> relations = new ArrayList<>();
        Set<Long> editedIds = new HashSet<>();
        editedIds.add(stored.getId());
        Set<String> skus = new HashSet<>();
        Set<String> names = new HashSet<>();
        checkRequestDuplicates(goods.getSku(), goods.getGoodsName(), skus, names);
        if (request.getGroupList() != null) {
            if (CollectionUtil.isNotEmpty(request.getGroupList())
                    && CollectionUtil.isNotEmpty(goodsMapper.findParentIds(stored.getSku(), stored.getId()))) {
                throw invalid("已被关联的子货品不能再关联其他子货品");
            }
            for (CompanyGoodsChildModifyDTO childRequest : request.getGroupList()) {
                CompanyGoodsPO childStored = null;
                Long childVersion = null;
                if (childRequest.getId() != null) {
                    if (!editedIds.add(childRequest.getId())) throw invalid("主、子货品ID不能重复");
                    childStored = goodsMapper.findById(childRequest.getId());
                    if (childStored == null) throw invalid("子货品不存在：" + childRequest.getId());
                    if (Objects.equals(childStored.getProductType(), 1)) throw invalid("仅支持关联普通子货品");
                    childVersion = validateModification(childRequest, childRequest.getVersion(), childStored);
                }
                CompanyGoodsPO child = prepare(childRequest, childStored, List.of());
                checkRequestDuplicates(child.getSku(), child.getGoodsName(), skus, names);
                children.add(child);
                childVersions.add(childVersion);
                CompanyGoodsRelationDTO relation = new CompanyGoodsRelationDTO();
                relation.setSku(child.getSku());
                relation.setGoodsName(child.getGoodsName());
                java.math.BigDecimal quantity = childRequest.getQuantity();
                if (quantity == null && stored.getGroupList() != null) {
                    try {
                        for (com.fasterxml.jackson.databind.JsonNode previous : objectMapper.readTree(stored.getGroupList())) {
                            if (Objects.equals(child.getSku(), previous.path("sku").asText(null))
                                    && previous.path("quantity").isNumber()) {
                                quantity = previous.path("quantity").decimalValue();
                                break;
                            }
                        }
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("原子货品关联解析失败", exception);
                    }
                }
                relation.setQuantity(quantity);
                relations.add(relation);
            }
            try {
                goods.setGroupList(objectMapper.writeValueAsString(relations));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("子货品关联序列化失败", exception);
            }
            goods.setProductType(CollectionUtil.isEmpty(relations) ? 0 : 1);
        }
        // 所有主、子项校验完成后统一写入；任何写入或版本检查失败均由事务回滚。
        for (int index = 0; index < children.size(); index++) {
            CompanyGoodsPO child = children.get(index);
            Long childVersion = childVersions.get(index);
            if (childVersion == null) {
                if (goodsMapper.insertLocal(child) != 1) throw invalid("子货品创建失败");
            } else if (goodsMapper.modifyLocal(child, childVersion) != 1) {
                throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
            }
        }
        if (goodsMapper.modifyLocal(goods, expectedVersion) != 1) {
            throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
        }
    }

    private Long validateModification(CompanyGoodsSaveDTO request, Long version, CompanyGoodsPO stored) {
        Long expectedVersion = version == null ? stored.getVersion() : version;
        if (!Objects.equals(expectedVersion, stored.getVersion())) {
            throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
        }
        if (request.getSku() != null && !Objects.equals(stored.getSku(), request.getSku().trim())) {
            throw invalid("SKU编码不允许修改");
        }
        return expectedVersion;
    }

    private CompanyGoodsPO prepare(CompanyGoodsSaveDTO request, CompanyGoodsPO stored, List<CompanyGoodsRelationDTO> relations) {
        if (request.getSku() != null) request.setSku(request.getSku().trim());
        if (request.getGoodsName() != null) request.setGoodsName(request.getGoodsName().trim());
        List<CompanyGoodsPO> conflicts = goodsMapper.lockImportConflicts(request.getSku(), request.getGoodsName());
        for (CompanyGoodsPO conflict : conflicts) {
            if (stored == null || !stored.getId().equals(conflict.getId())) throw invalid("SKU编码或名称已存在");
        }
        if (request.getUnit() != null && !List.of("套", "张", "个", "KG", "次").contains(request.getUnit())) throw invalid("单位不在可选范围内");
        boolean categoryExists = false;
        for (BlacklakeProductCategoryEnum category : BlacklakeProductCategoryEnum.values()) {
            if (category.getName().equals(request.getProductCategory())) categoryExists = true;
        }
        if (request.getProductCategory() != null && !categoryExists) throw invalid("产品分类不在黑湖产品分类范围内");
        Set<String> inspectionTypes = new HashSet<>();
        if (CollectionUtil.isNotEmpty(request.getInspectionType())) {
            for (String type : request.getInspectionType()) {
                if (!List.of("来料检验", "成品入库检验", "销售发货检验", "其他检验").contains(type)
                        || !inspectionTypes.add(type)) throw invalid("检验类型无效或重复");
            }
        }
        List<CompanyGoodsRelationDTO> requestedRelations = relations == null ? List.of() : relations;
        CompanyGoodsPO goods = new CompanyGoodsPO();
        if (stored != null) BeanUtils.copyProperties(stored, goods);
        // 修改只覆盖非空字段；集合单独序列化，空数组表示明确清空。
        List<String> ignoredProperties = new ArrayList<>(List.of("groupList", "inspectionType", "pictureList", "version"));
        BeanWrapperImpl properties = new BeanWrapperImpl(request);
        for (java.beans.PropertyDescriptor property : properties.getPropertyDescriptors()) {
            if (properties.isReadableProperty(property.getName()) && properties.getPropertyValue(property.getName()) == null) {
                ignoredProperties.add(property.getName());
            }
        }
        BeanUtils.copyProperties(request, goods, ignoredProperties.toArray(new String[0]));
        try {
            if (relations != null || stored == null) {
                goods.setProductType(CollectionUtil.isEmpty(requestedRelations) ? 0 : 1);
                goods.setGroupList(objectMapper.writeValueAsString(requestedRelations));
            }
            if (request.getInspectionType() != null || stored == null) {
                goods.setInspectionType(objectMapper.writeValueAsString(request.getInspectionType() == null ? List.of() : request.getInspectionType()));
            }
            if (request.getPictureList() != null || stored == null) {
                goods.setPictureList(objectMapper.writeValueAsString(request.getPictureList() == null ? List.of() : request.getPictureList()));
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("货品数据序列化失败", exception);
        }
        return goods;
    }

    private void checkRequestDuplicates(String sku, String goodsName, Set<String> skus, Set<String> names) {
        if (sku != null && !skus.add(sku.trim().toLowerCase(Locale.ROOT))) {
            throw invalid("主、子货品SKU不能重复");
        }
        if (goodsName != null && !names.add(goodsName.trim().toLowerCase(Locale.ROOT))) {
            throw invalid("主、子货品名称不能重复");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.PARAM_ERROR, message);
    }
}
