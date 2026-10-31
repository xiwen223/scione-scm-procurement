package com.scione.scm.bill.application;

import cn.hutool.core.collection.CollectionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.application.dto.*;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.goods.enums.BlacklakeProductCategoryEnum;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncOperationEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.CompanyGoodsMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 企业商品库创建及修改主子货品；修改已创货品时通过本地消息异步同步外部系统。 */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
@Slf4j
public class CompanyGoodsLocalSaveService {
    @Resource
    private CompanyGoodsMapper goodsMapper;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private CompanyGoodsImageService imageService;
    @Resource
    private CompanyGoodsMessageService messageService;
    @Resource
    private CompanyGoodsDictionaryService dictionaryService;

    /**
     * 在同一事务内保存主货品、完整子货品及关联关系，仅写入本地货品库。
     *
     * @param request 当前操作的请求参数
     * @return 创建的主货品数据库主键
     */
    public Long create(CompanyGoodsCreateDTO request) {
        log.info("企业货品库开始同步新增");
        if (request.getProductId() != null) request.setProductId(request.getProductId().trim());
        List<CompanyGoodsPO> children = new ArrayList<>();
        List<Long> childVersions = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        Set<String> names = new HashSet<>();
        checkRequestDuplicates(request.getSku(), request.getGoodsName(), skus, names);
        validateParentIdentity(request.getProductId(), request.getProductType(), null, CollectionUtil.isNotEmpty(request.getGroupList()));
        if (CollectionUtil.isNotEmpty(request.getGroupList())) {
            for (CompanyGoodsChildCreateDTO child : request.getGroupList()) {
                child.setProductId(request.getProductId().trim());
                child.setProductType(0);
                CompanyGoodsPO childStored = null;
                Long childVersion = null;
                // 创建参数允许为空；业务定位字段完整时，复用并更新已有子货品。
                if (child.getProductId() != null && !child.getProductId().isBlank()
                        && child.getGoodsName() != null && !child.getGoodsName().isBlank()) {
                    List<CompanyGoodsPO> matches = goodsMapper.lockByProductIdAndGoodsName(
                            child.getProductId().trim(), child.getGoodsName().trim());
                    if (matches.size() > 1) throw invalid("子货品productId和goodsName对应多条记录");
                    if (CollectionUtil.isNotEmpty(matches)) {
                        childStored = matches.get(0);
                        if (Objects.equals(childStored.getProductType(), 1)) throw invalid("仅支持关联普通子货品");
                        childVersion = validateModification(child, null, childStored);
                    }
                }
                CompanyGoodsPO childGoods = prepare(child, childStored, List.of());
                checkRequestDuplicates(childGoods.getSku(), childGoods.getGoodsName(), skus, names);
                children.add(childGoods);
                childVersions.add(childVersion);
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
        for (int index = 0; index < children.size(); index++) {
            CompanyGoodsPO child = children.get(index);
            Long childVersion = childVersions.get(index);
            if (childVersion == null) {
                if (goodsMapper.insertLocal(child) != 1) throw invalid("子货品创建失败");
            } else if (goodsMapper.modifyLocal(child, childVersion) != 1) {
                throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
            }
        }
        if (goodsMapper.insertLocal(goods) != 1) throw invalid("货品创建失败");
        return goods.getId();
    }

    /**
     * 按产品ID及名称修改主、子货品，未匹配子项则新增，仅为修改前已创货品保存同步消息。
     *
     * @param request 当前操作的请求参数
     */
    public void modify(CompanyGoodsModifyDTO request) {
        log.info("企业货品库开始同步更新");
        if (request.getProductId() == null || request.getProductId().isBlank()
                || request.getGoodsName() == null || request.getGoodsName().isBlank()) {
            throw invalid("修改货品时productId和goodsName不能为空");
        }
        List<CompanyGoodsPO> matches = goodsMapper.lockByProductIdAndGoodsName(
                request.getProductId().trim(), request.getGoodsName().trim());
        if (matches.size() > 1) throw invalid("productId和goodsName对应多条货品，无法确定修改记录");
        CompanyGoodsPO stored = CollectionUtil.isEmpty(matches) ? null : matches.get(0);
        if (stored == null) {
            log.info("货品不存在，修改失败");
            return;
        }
        request.setProductId(request.getProductId().trim());
        Long expectedVersion = validateModification(request, request.getVersion(), stored);
        CompanyGoodsPO goods = prepare(request, stored, null);
        validateParentIdentity(goods.getProductId(), goods.getProductType(), stored.getId(), CollectionUtil.isNotEmpty(request.getGroupList()));
        List<CompanyGoodsPO> children = new ArrayList<>();
        List<Long> childVersions = new ArrayList<>();
        List<CompanyGoodsPO> goodsToSync = new ArrayList<>();
        List<CompanyGoodsRelationDTO> relations = new ArrayList<>();
        Set<Long> editedIds = new HashSet<>();
        editedIds.add(stored.getId());
        Set<String> skus = new HashSet<>();
        Set<String> names = new HashSet<>();
        checkRequestDuplicates(goods.getSku(), goods.getGoodsName(), skus, names);
        if (request.getGroupList() != null) {
            if (CollectionUtil.isNotEmpty(request.getGroupList())
                    && CollectionUtil.isNotEmpty(goodsMapper.findProductParentIds(stored.getProductId(), stored.getId()))) {
                throw invalid("已被关联的子货品不能再关联其他子货品");
            }
            for (CompanyGoodsChildModifyDTO childRequest : request.getGroupList()) {
                childRequest.setProductId(goods.getProductId().trim());
                childRequest.setProductType(0);
                CompanyGoodsPO childStored = null;
                Long childVersion = null;
                if (childRequest.getProductId() == null || childRequest.getProductId().isBlank()
                        || childRequest.getGoodsName() == null || childRequest.getGoodsName().isBlank()) {
                    throw invalid("子货品productId和goodsName不能为空");
                }
                List<CompanyGoodsPO> childMatches = goodsMapper.lockByProductIdAndGoodsName(
                        childRequest.getProductId().trim(), childRequest.getGoodsName().trim());
                if (childMatches.size() > 1) throw invalid("子货品productId和goodsName对应多条记录");
                if (CollectionUtil.isNotEmpty(childMatches)) {
                    childStored = childMatches.get(0);
                    if (!editedIds.add(childStored.getId())) throw invalid("主、子货品不能重复");
                    if (Objects.equals(childStored.getProductType(), 1)) throw invalid("仅支持关联普通子货品");
                    childVersion = validateModification(childRequest, childRequest.getVersion(), childStored);
                }
                CompanyGoodsPO child = prepare(childRequest, childStored, List.of());
                if (childStored != null && Objects.equals(childStored.getGoodsStatus(), GoodsSyncOperationEnum.UPDATE.getCode())) {
                    goodsToSync.add(child);
                }
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
        }
        // 所有主、子项校验完成后统一写入；任何写入或版本检查失败均由事务回滚。
        for (int index = 0; index < children.size(); index++) {
            CompanyGoodsPO child = children.get(index);
            Long childVersion = childVersions.get(index);
            if (childVersion == null) {
                if (goodsMapper.insertLocal(child) != 1) throw invalid("子货品创建失败");
            } else if (goodsMapper.modifyLocal(child, childVersion) != 1) {
                throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
            } else {
                child.setVersion(childVersion + 1);
            }
        }
        if (goodsMapper.modifyLocal(goods, expectedVersion) != 1) {
            throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
        }
        goods.setVersion(expectedVersion + 1);
        if (Objects.equals(stored.getGoodsStatus(), GoodsSyncOperationEnum.UPDATE.getCode())) {
            goodsToSync.add(goods);
        }
        // 消息与货品同事务保存，发布任务只能读取已提交消息；先保存子货品消息再保存主货品消息。
        if (CollectionUtil.isEmpty(goodsToSync)) return;
        GoodsSyncDictionarySnapshot dictionaries = dictionaryService.loadForSync(goodsToSync);
        for (CompanyGoodsPO updatedGoods : goodsToSync) {
            messageService.createSyncMessages(updatedGoods, GoodsSyncOperationEnum.UPDATE.getCode(), dictionaries);
        }
    }

    /**
     *  相同产品ID用类型区分主子，防止产生无法唯一展示的多个主货品。
     *
     * @param productId 企业商品ID，对应数据库product_id
     * @param productType 产品类型，0普通货品、1组合主货品
     * @param id 当前主货品主键，用于排除自身；新增时为null
     * @param hasChildren 当前请求是否包含子货品
     */
    private void validateParentIdentity(String productId, Integer productType, Long id, boolean hasChildren) {
        if (hasChildren && (productId == null || productId.isBlank())) throw invalid("有子货品时主货品productId不能为空");
        if (hasChildren && !Integer.valueOf(1).equals(productType)) throw invalid("有子货品时主货品productType必须为1");
        if (!Integer.valueOf(1).equals(productType) || productId == null || productId.isBlank()) return;
        for (CompanyGoodsPO existing : goodsMapper.lockProductFamily(productId.trim())) {
            if (Integer.valueOf(1).equals(existing.getProductType()) && !Objects.equals(existing.getId(), id)) {
                throw invalid("同一产品ID只允许一个组合主货品");
            }
        }
    }

    /**
     * 校验修改版本及SKU不可变规则，未传版本时使用数据库当前版本。
     *
     * @param request 当前操作的请求参数
     * @param version 调用方提供的原版本号，null时使用数据库当前版本
     * @param stored 修改前数据库记录；新增时为null
     * @return 本次更新需要匹配的原版本号
     */
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

    /**
     * 按产品ID及名称组合校验唯一性，合并非空修改字段，并序列化关联、检验类型及公共桶图片。
     *
     * @param request 当前操作的请求参数
     * @param stored 修改前数据库记录；新增时为null
     * @param relations 待保存的子货品关联；null表示保留原关联
     * @return 待保存的货品记录；修改时保留请求中未提供的字段
     */
    private CompanyGoodsPO prepare(CompanyGoodsSaveDTO request, CompanyGoodsPO stored, List<CompanyGoodsRelationDTO> relations) {
        if (request.getSku() != null) {
            request.setSku(request.getSku().trim());
        }
        if (request.getProductId() != null) {
            request.setProductId(request.getProductId().trim());
        }
        if (request.getGoodsName() != null) {
            request.setGoodsName(request.getGoodsName().trim());
        }
        List<CompanyGoodsPO> conflicts = goodsMapper.lockByProductIdAndGoodsName(request.getProductId(), request.getGoodsName());
        for (CompanyGoodsPO conflict : conflicts) {
            if (stored == null || !stored.getId().equals(conflict.getId())) {
                throw invalid("产品ID和货品名称对应的货品已存在");
            }
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
        List<String> ignoredProperties = new ArrayList<>(List.of("groupList", "inspectionType", "pictureList", "version", "id"));
        BeanWrapperImpl properties = new BeanWrapperImpl(request);
        for (java.beans.PropertyDescriptor property : properties.getPropertyDescriptors()) {
            if (properties.isReadableProperty(property.getName()) && properties.getPropertyValue(property.getName()) == null) {
                ignoredProperties.add(property.getName());
            }
        }
        BeanUtils.copyProperties(request, goods, ignoredProperties.toArray(new String[0]));
        try {
            if (relations != null || stored == null) {
                goods.setGroupList(objectMapper.writeValueAsString(requestedRelations));
            }
            if (request.getInspectionType() != null || stored == null) {
                goods.setInspectionType(objectMapper.writeValueAsString(request.getInspectionType() == null ? List.of() : request.getInspectionType()));
            }
            if (request.getPictureList() != null || stored == null) {
                List<CompanyGoodsImageDTO> copiedPictures = List.of();
                if (CollectionUtil.isNotEmpty(request.getPictureList())) {
                    copiedPictures = imageService.copyToPublic(request.getPictureList());
                }
                goods.setPictureList(objectMapper.writeValueAsString(copiedPictures));
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("货品数据序列化失败", exception);
        }
        return goods;
    }

    /**
     * 校验同一次请求的主、子货品SKU及名称不重复，比较时忽略首尾空白和大小写。
     *
     * @param sku 货品SKU编码
     * @param goodsName 货品名称，对应数据库goods_name
     * @param skus 本次请求已出现的归一化SKU集合，校验时写入当前SKU
     * @param names 本次请求已出现的归一化货品名称集合，校验时写入当前名称
     */
    private void checkRequestDuplicates(String sku, String goodsName, Set<String> skus, Set<String> names) {
        if (sku != null && !skus.add(sku.trim().toLowerCase(Locale.ROOT))) {
            throw invalid("主、子货品SKU不能重复");
        }
        if (goodsName != null && !names.add(goodsName.trim().toLowerCase(Locale.ROOT))) {
            throw invalid("主、子货品名称不能重复");
        }
    }

    /**
     * 创建货品参数校验失败的业务异常。
     *
     * @param message 向调用方说明的业务失败原因
     * @return 参数错误业务异常
     */
    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.PARAM_ERROR, message);
    }
}
