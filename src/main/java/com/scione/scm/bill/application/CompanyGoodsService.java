package com.scione.scm.bill.application;

import cn.hutool.core.collection.CollectionUtil;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncOperationEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.CompanyGoodsQueryDTO;
import com.scione.scm.bill.application.dto.CompanyGoodsEditDTO;
import com.scione.scm.bill.application.dto.CompanyGoodsUpdateDTO;
import com.scione.scm.bill.application.vo.CompanyGoodsDetailVO;
import com.scione.scm.bill.application.vo.CompanyGoodsListItemVO;
import com.scione.scm.bill.application.vo.CompanyGoodsPictureVO;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.goods.GoodsSkuRelation;
import com.scione.scm.bill.domain.goods.enums.BlacklakeProductCategoryEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.CompanyGoodsMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.math.BigDecimal;

/** 企业货品查询与编辑服务，关联只展开一层，主、子 SKU 表单整体保存。 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class CompanyGoodsService {
    @Resource
    private CompanyGoodsMapper goodsMapper;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private CompanyGoodsImageService imageService;
    @Resource
    private CompanyGoodsMessageService messageService;

    /** 只更新表单开放字段；主、子 SKU 任一校验或版本检查失败，整单回滚。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public void update(CompanyGoodsUpdateDTO request) {
        CompanyGoodsPO parent = goodsMapper.findById(request.getId());
        if (parent == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "货品不存在");
        }
        if (CollectionUtil.isNotEmpty(goodsMapper.findParentIds(parent.getSku(), parent.getId()))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请通过关联主 SKU 编辑子货品");
        }
        List<CompanyGoodsEditDTO> edits = new ArrayList<>();
        edits.add(request);
        edits.addAll(request.getGroupList());
        List<GoodsSkuRelation> relations = readRelations(parent.getGroupList());
        Set<String> childSkus = new LinkedHashSet<>();
        for (GoodsSkuRelation relation : relations) {
            childSkus.add(relation.getSku());
        }
        Map<String, CompanyGoodsPO> children = findChildren(childSkus);
        Map<Long, CompanyGoodsPO> storedGoods = new LinkedHashMap<>();
        storedGoods.put(parent.getId(), parent);
        for (GoodsSkuRelation relation : relations) {
            CompanyGoodsPO child = requireChild(children, relation.getSku());
            storedGoods.put(child.getId(), child);
        }
        if (edits.size() != storedGoods.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "主、子 SKU 关联不可变更，请提交完整的原有关联货品");
        }
        Set<Long> editedIds = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        boolean imagesChanged = false;
        for (CompanyGoodsEditDTO edit : edits) {
            CompanyGoodsPO stored = storedGoods.get(edit.getId());
            if (stored == null || !editedIds.add(edit.getId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "子 SKU 不属于当前主货品，或重复提交了同一货品");
            }
            if (!edit.getVersion().equals(stored.getVersion())) {
                throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
            }
            if (!edit.getId().equals(parent.getId()) && !edit.getSyncScope().equals(stored.getSyncScope())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "子 SKU 同步范围仅展示，不支持编辑");
            }
            edit.setGoodsName(edit.getGoodsName().trim());
            for (String name : names) {
                if (name.equalsIgnoreCase(edit.getGoodsName())) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "相同名称不允许重复");
                }
            }
            names.add(edit.getGoodsName());
            validateEditableFields(edit);
            List<CompanyGoodsPictureVO> storedPictures = readArray(stored.getPictureList(), new TypeReference<List<CompanyGoodsPictureVO>>() {});
            String currentImage = primaryImage(storedPictures);
            String nextImage = StringUtils.trimToNull(edit.getImageUrl());
            if (!java.util.Objects.equals(currentImage, nextImage)) {
                if (nextImage != null) {
                    imageService.validate(nextImage);
                    imagesChanged = true;
                }
                for (CompanyGoodsPictureVO picture : storedPictures) {
                    if (picture != null && StringUtils.isNotBlank(picture.getObjectKey())) imagesChanged = true;
                }
            }
        }
        if (CollectionUtil.isNotEmpty(goodsMapper.lockNameConflicts(new ArrayList<>(names), new ArrayList<>(editedIds)))) {
            throw new BusinessException(ResultCode.DATABASE_DUPLICATE_KEY, "相同名称不允许重复");
        }
        List<String> uploadedImageKeys = new ArrayList<>();
        Set<String> removedImageKeys = new LinkedHashSet<>();
        if (imagesChanged) registerImageCleanup(uploadedImageKeys, removedImageKeys);
        // 先保存子货品及消息，再保存主货品，保证主消息能定位本次子消息依赖。
        List<CompanyGoodsEditDTO> saveOrder = new ArrayList<>(request.getGroupList());
        saveOrder.add(request);
        for (CompanyGoodsEditDTO edit : saveOrder) {
            CompanyGoodsPO stored = storedGoods.get(edit.getId());
            CompanyGoodsPO updated = new CompanyGoodsPO();
            BeanUtils.copyProperties(stored, updated);
            BeanUtils.copyProperties(edit, updated, "inspectionType", "imageUrl");
            updated.setInspectionType(writeJson(edit.getInspectionType()));
            List<CompanyGoodsPictureVO> storedPictures = readArray(stored.getPictureList(), new TypeReference<List<CompanyGoodsPictureVO>>() {});
            String currentImage = primaryImage(storedPictures);
            String nextImage = StringUtils.trimToNull(edit.getImageUrl());
            if (java.util.Objects.equals(currentImage, nextImage)) {
                updated.setPictureList(stored.getPictureList());
            } else {
                List<CompanyGoodsPictureVO> pictures = new ArrayList<>();
                if (nextImage != null) {
                    String objectKey = imageService.upload(edit.getId(), nextImage);
                    uploadedImageKeys.add(objectKey);
                    CompanyGoodsPictureVO picture = new CompanyGoodsPictureVO();
                    picture.setObjectKey(objectKey);
                    picture.setPicUrl(imageService.publicUrl(objectKey));
                    picture.setIsPrimary(1);
                    pictures.add(picture);
                }
                for (CompanyGoodsPictureVO picture : storedPictures) {
                    if (picture != null && StringUtils.isNotBlank(picture.getObjectKey())) removedImageKeys.add(picture.getObjectKey());
                }
                updated.setPictureList(writeJson(pictures));
            }
            Integer previousStatus = stored.getGoodsStatus();
            if (previousStatus == null || (previousStatus != GoodsSyncOperationEnum.CREATE.getCode() && previousStatus != GoodsSyncOperationEnum.UPDATE.getCode())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "货品状态无效，不能确定外部新增或修改操作");
            }
            if (goodsMapper.update(updated, edit.getVersion()) != 1) {
                throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
            }
            updated.setGoodsStatus(1);
            updated.setVersion(edit.getVersion() + 1);
            messageService.createSyncMessages(updated, previousStatus);
        }
    }

    /** 数据库回滚只清理本次新文件；旧文件必须等提交成功后才能删除。 */
    private void registerImageCleanup(List<String> uploadedKeys, Set<String> removedKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("货品图片修改必须在事务中执行");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupImages(removedKeys, true);
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) cleanupImages(uploadedKeys, false);
            }
        });
    }

    private void cleanupImages(Collection<String> objectKeys, boolean saved) {
        RuntimeException firstFailure = null;
        for (String objectKey : objectKeys) {
            try {
                imageService.delete(objectKey);
            } catch (RuntimeException exception) {
                log.error("货品图片清理失败，需人工清理：objectKey={}, goodsSaved={}", objectKey, saved, exception);
                if (firstFailure == null) firstFailure = exception;
                else firstFailure.addSuppressed(exception);
            }
        }
        if (firstFailure != null && saved) {
            throw new BusinessException(ResultCode.STORAGE_API_ERROR, "货品已保存，但旧图片删除失败，请联系管理员清理后重新打开货品");
        }
    }

    private void validateEditableFields(CompanyGoodsEditDTO edit) {
        if (!List.of("套", "张", "个", "KG", "次").contains(edit.getUnit())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "单位不在可选范围内");
        }
        boolean validCategory = false;
        for (BlacklakeProductCategoryEnum category : BlacklakeProductCategoryEnum.values()) {
            if (category.getName().equals(edit.getProductCategory())) {
                validCategory = true;
                break;
            }
        }
        if (!validCategory) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "产品分类不在黑湖产品分类范围内");
        }
        validateDimensions(edit.getCgBoxLength(), edit.getCgBoxWidth(), edit.getCgBoxHeight(), "外箱规格");
        validateDimensions(edit.getCgPackageLength(), edit.getCgPackageWidth(), edit.getCgPackageHeight(), "包装规格");
        Set<String> inspectionTypes = new LinkedHashSet<>();
        if (CollectionUtil.isNotEmpty(edit.getInspectionType())) {
            for (String inspectionType : edit.getInspectionType()) {
                if (!List.of("来料检验", "成品入库检验", "销售发货检验", "其他检验").contains(inspectionType)
                        || !inspectionTypes.add(inspectionType)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "检验类型必须为有效选项，且不能重复");
                }
            }
        }
        String owners = StringUtils.trimToNull(edit.getProductOwnerNames());
        if (owners != null) {
            Set<String> ownerNames = new LinkedHashSet<>();
            for (String owner : owners.split(",", -1)) {
                if (StringUtils.isBlank(owner) || owner.matches(".*[，;；].*") || !ownerNames.add(owner.trim())) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "产品负责人请使用英文逗号分隔，且人员不允许重复");
                }
            }
            edit.setProductOwnerNames(String.join(",", ownerNames));
        }
        String supplier = StringUtils.trimToNull(edit.getSupplierName());
        if (supplier != null && supplier.matches(".*[;；,，、].*")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅支持维护一个供应商");
        }
    }

    private void validateDimensions(BigDecimal length, BigDecimal width, BigDecimal height, String label) {
        if ((length != null || width != null || height != null) && (length == null || width == null || height == null)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, label + "请完整填写长、宽、高");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "货品数据无法保存为 JSON");
        }
    }

    /** 主货品按更新时间、主键倒序分页；整个页面的子货品只执行一次批量查询。 */
    public PageResult<CompanyGoodsListItemVO> findPage(CompanyGoodsQueryDTO query) {
        query.setSku(likePattern(query.getSku()));
        query.setGoodsName(likePattern(query.getGoodsName()));
        query.setPersonnelName(likePattern(query.getPersonnelName()));
        query.setProductCategory(StringUtils.trimToNull(query.getProductCategory()));
        long total = goodsMapper.count(query);
        List<CompanyGoodsListItemVO> records = new ArrayList<>();
        if (total == 0) {
            return PageResult.of(query.getPageNum(), query.getPageSize(), total, records);
        }
        long offset = ((long) query.getPageNum() - 1) * query.getPageSize();
        List<CompanyGoodsPO> goods = goodsMapper.findPage(query, offset);
        Map<Long, List<GoodsSkuRelation>> relations = new LinkedHashMap<>();
        Set<String> childSkus = new LinkedHashSet<>();
        for (CompanyGoodsPO item : goods) {
            List<GoodsSkuRelation> children = readRelations(item.getGroupList());
            relations.put(item.getId(), children);
            for (GoodsSkuRelation child : children) {
                childSkus.add(child.getSku());
            }
        }
        Map<String, CompanyGoodsPO> childGoods = findChildren(childSkus);
        for (CompanyGoodsPO item : goods) {
            CompanyGoodsListItemVO record = toListItem(item);
            for (GoodsSkuRelation relation : relations.get(item.getId())) {
                CompanyGoodsListItemVO child = toListItem(requireChild(childGoods, relation.getSku()));
                child.setQuantity(relation.getQuantity());
                record.getGroupList().add(child);
            }
            records.add(record);
        }
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, records);
    }

    /** 按主键读取任意货品；子 SKU 完整字段只展开一层，查询不会修改存储的比例关系。 */
    public CompanyGoodsDetailVO getDetail(Long id) {
        CompanyGoodsPO goods = goodsMapper.findById(id);
        if (goods == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "货品不存在");
        }
        CompanyGoodsDetailVO detail = toDetail(goods);
        List<GoodsSkuRelation> relations = readRelations(goods.getGroupList());
        Set<String> childSkus = new LinkedHashSet<>();
        for (GoodsSkuRelation relation : relations) {
            childSkus.add(relation.getSku());
        }
        Map<String, CompanyGoodsPO> children = findChildren(childSkus);
        for (GoodsSkuRelation relation : relations) {
            CompanyGoodsDetailVO child = toDetail(requireChild(children, relation.getSku()));
            child.setQuantity(relation.getQuantity());
            detail.getGroupList().add(child);
        }
        return detail;
    }

    private Map<String, CompanyGoodsPO> findChildren(Set<String> skus) {
        Map<String, CompanyGoodsPO> children = new LinkedHashMap<>();
        if (CollectionUtil.isEmpty(skus)) {
            return children;
        }
        for (CompanyGoodsPO child : goodsMapper.findBySkus(new ArrayList<>(skus))) {
            if (children.put(child.getSku(), child) != null) {
                throw new BusinessException(ResultCode.DATABASE_ERROR, "关联子 SKU 存在重复记录，无法确定货品");
            }
        }
        return children;
    }

    private CompanyGoodsPO requireChild(Map<String, CompanyGoodsPO> children, String sku) {
        CompanyGoodsPO child = children.get(sku);
        if (child == null) {
            throw new BusinessException(ResultCode.DATABASE_ERROR, "关联子货品不存在：" + sku);
        }
        return child;
    }

    private CompanyGoodsListItemVO toListItem(CompanyGoodsPO goods) {
        CompanyGoodsListItemVO item = new CompanyGoodsListItemVO();
        BeanUtils.copyProperties(goods, item, "groupList");
        item.setGroupList(new ArrayList<>());
        item.setImageUrl(imageAccessUrl(readArray(goods.getPictureList(), new TypeReference<List<CompanyGoodsPictureVO>>() {})));
        return item;
    }

    private CompanyGoodsDetailVO toDetail(CompanyGoodsPO goods) {
        CompanyGoodsDetailVO detail = new CompanyGoodsDetailVO();
        BeanUtils.copyProperties(goods, detail, "groupList", "sourceList", "inspectionType", "pictureList");
        detail.setGroupList(new ArrayList<>());
        detail.setSourceList(readNullableArray(goods.getSourceList(), new TypeReference<List<String>>() {}));
        detail.setInspectionType(readNullableArray(goods.getInspectionType(), new TypeReference<List<String>>() {}));
        detail.setPictureList(readNullableArray(goods.getPictureList(), new TypeReference<List<CompanyGoodsPictureVO>>() {}));
        detail.setImageObjectKey(primaryImageObjectKey(detail.getPictureList()));
        detail.setImageUrl(imageAccessUrl(detail.getPictureList()));
        return detail;
    }

    private String primaryImage(List<CompanyGoodsPictureVO> pictures) {
        if (CollectionUtil.isEmpty(pictures)) {
            return null;
        }
        String firstImage = null;
        for (CompanyGoodsPictureVO picture : pictures) {
            if (picture == null || (StringUtils.isBlank(picture.getPicUrl()) && StringUtils.isBlank(picture.getObjectKey()))) {
                continue;
            }
            String reference = StringUtils.isNotBlank(picture.getObjectKey()) ? picture.getObjectKey() : picture.getPicUrl();
            if (Integer.valueOf(1).equals(picture.getIsPrimary())) {
                return reference;
            }
            if (firstImage == null) {
                firstImage = reference;
            }
        }
        return firstImage;
    }

    private String primaryImageObjectKey(List<CompanyGoodsPictureVO> pictures) {
        String reference = primaryImage(pictures);
        if (CollectionUtil.isNotEmpty(pictures)) {
            for (CompanyGoodsPictureVO picture : pictures) {
                if (picture != null && StringUtils.isNotBlank(picture.getObjectKey()) && picture.getObjectKey().equals(reference)) {
                    return picture.getObjectKey();
                }
            }
        }
        return null;
    }

    private String imageAccessUrl(List<CompanyGoodsPictureVO> pictures) {
        String objectKey = primaryImageObjectKey(pictures);
        return objectKey == null ? primaryImage(pictures) : imageService.accessUrl(objectKey);
    }

    private List<GoodsSkuRelation> readRelations(String json) {
        List<GoodsSkuRelation> relations = readArray(json, new TypeReference<List<GoodsSkuRelation>>() {});
        for (GoodsSkuRelation relation : relations) {
            if (relation == null || StringUtils.isBlank(relation.getSku())) {
                throw new BusinessException(ResultCode.DATABASE_ERROR, "货品关联数据缺少子 SKU");
            }
        }
        return relations;
    }

    private <T> List<T> readArray(String json, TypeReference<List<T>> type) {
        List<T> values = readNullableArray(json, type);
        return values == null ? new ArrayList<>() : values;
    }

    /** 使用抛错的 JSON 解析，避免异常数据被转成空集合后伪装成正常货品。 */
    private <T> List<T> readNullableArray(String json, TypeReference<List<T>> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.DATABASE_ERROR, "货品 JSON 数据格式异常");
        }
    }

    private String likePattern(String value) {
        String keyword = StringUtils.trimToNull(value);
        if (keyword == null) {
            return null;
        }
        // 将输入的通配符作为普通字符处理，防止输入 % 或 _ 时意外扩大查询范围。
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
