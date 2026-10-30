package com.scione.scm.bill.application;

import cn.hutool.core.collection.CollectionUtil;
import com.scione.scm.bill.domain.goods.enums.GoodsSyncOperationEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.CompanyGoodsQueryDTO;
import com.scione.scm.bill.application.dto.CompanyGoodsEditDTO;
import com.scione.scm.bill.application.dto.CompanyGoodsUpdateDTO;
import com.scione.scm.bill.application.dto.CompanyGoodsSyncSelectionDTO;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.infrastructure.lingxing.LingxingOpenApiClient;
import com.scione.scm.bill.infrastructure.blacklake.BlacklakeOpenApiClient;
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
    private CompanyGoodsSkuService skuService;
    @Resource
    private CompanyGoodsMapper goodsMapper;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private CompanyGoodsImageService imageService;
    @Resource
    private CompanyGoodsMessageService messageService;

    /**
     *  只更新表单开放字段；主、子 SKU 任一校验或版本检查失败，整单回滚。
     *
     * @param request 当前操作的请求参数
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public void update(CompanyGoodsUpdateDTO request) {
        CompanyGoodsPO parent = goodsMapper.findById(request.getId());
        if (parent == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "货品不存在");
        }
        if (CollectionUtil.isNotEmpty(goodsMapper.findDisplayParentIds(parent.getId()))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请通过关联主 SKU 编辑子货品");
        }
        List<CompanyGoodsEditDTO> edits = new ArrayList<>();
        edits.add(request);
        edits.addAll(request.getGroupList());
        List<CompanyGoodsPO> children = findProductChildren(List.of(parent)).get(parent.getId());
        Map<Long, CompanyGoodsPO> storedGoods = new LinkedHashMap<>();
        storedGoods.put(parent.getId(), parent);
        for (CompanyGoodsPO child : children) storedGoods.put(child.getId(), child);
        if (edits.size() != storedGoods.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "主、子 SKU 关联不可变更，请提交完整的原有关联货品");
        }
        Set<Long> editedIds = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        boolean imagesChanged = false;
        Map<Long, GoodsSyncDictionarySnapshot> pageDictionaries = new LinkedHashMap<>();
        for (CompanyGoodsEditDTO edit : edits) {
            CompanyGoodsPO stored = storedGoods.get(edit.getId());
            if (stored == null || !editedIds.add(edit.getId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "子 SKU 不属于当前主货品，或重复提交了同一货品");
            }
            if (!edit.getVersion().equals(stored.getVersion())) {
                throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
            }
            boolean inheritsParentScope = !edit.getId().equals(parent.getId()) && edit.getSyncScope() == null;
            if (inheritsParentScope) edit.setSyncScope(request.getSyncScope());
            if (!edit.getId().equals(parent.getId()) && !inheritsParentScope && !edit.getSyncScope().equals(stored.getSyncScope())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "子 SKU 同步范围仅展示，不支持编辑");
            }
            edit.setGoodsName(edit.getGoodsName().trim());
            if (!java.util.Objects.equals(stored.getGoodsName(), edit.getGoodsName())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "SKU名称不允许修改：" + stored.getGoodsName());
            }
            for (String name : names) {
                if (name.equalsIgnoreCase(edit.getGoodsName())) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "相同名称不允许重复");
                }
            }
            names.add(edit.getGoodsName());
            validateEditableFields(edit);
            pageDictionaries.put(edit.getId(), pageDictionarySnapshot(edit));
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
        List<String> uploadedImageKeys = new ArrayList<>();
        Set<String> removedImageKeys = new LinkedHashSet<>();
        if (imagesChanged) registerImageCleanup(uploadedImageKeys, removedImageKeys);
        // 先保存子货品及消息，再保存主货品，保证主消息能定位本次子消息依赖。
        List<CompanyGoodsEditDTO> saveOrder = new ArrayList<>(request.getGroupList());
        saveOrder.add(request);
        List<CompanyGoodsPO> goodsToSync = new ArrayList<>();
        for (CompanyGoodsEditDTO edit : saveOrder) {
            CompanyGoodsPO goods = new CompanyGoodsPO();
            BeanUtils.copyProperties(storedGoods.get(edit.getId()), goods);
            BeanUtils.copyProperties(edit, goods, "inspectionType", "imageUrl");
            if (edit.getSku() == null) goods.setSku(storedGoods.get(edit.getId()).getSku());
            goodsToSync.add(goods);
        }
        skuService.prepareChanges(goodsToSync, storedGoods);
        Map<Long, CompanyGoodsPO> preparedGoods = new LinkedHashMap<>();
        for (CompanyGoodsPO goods : goodsToSync) preparedGoods.put(goods.getId(), goods);
        for (CompanyGoodsEditDTO edit : saveOrder) {
            CompanyGoodsPO stored = storedGoods.get(edit.getId());
            CompanyGoodsPO updated = new CompanyGoodsPO();
            BeanUtils.copyProperties(stored, updated);
            BeanUtils.copyProperties(edit, updated, "inspectionType", "imageUrl");
            updated.setSku(preparedGoods.get(edit.getId()).getSku());
            updated.setGroupList(preparedGoods.get(edit.getId()).getGroupList());
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
            messageService.createSyncMessages(updated, previousStatus, stored.getSyncScope(), pageDictionaries.get(edit.getId()));
        }
    }

    /**
     * 将页面选择的编号和已校验的名称固定为当前货品的字典快照，不查询第三方。
     * 编号缺失直接拒绝保存，不回退到导入的远程字典查询，避免保存时重复请求。
     *
     * @param edit 当前主货品或子货品的页面参数及外部编号
     * @return 可由现有消息服务和消费者直接复用的单货品字典快照
     */
    private GoodsSyncDictionarySnapshot pageDictionarySnapshot(CompanyGoodsEditDTO edit) {
        CompanyGoodsSyncSelectionDTO selection = edit.getSyncSelection();
        if (selection == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请重新打开编辑页并选择同步字段");
        }
        GoodsSyncDictionarySnapshot snapshot = new GoodsSyncDictionarySnapshot();
        List<LingxingOpenApiClient.AccountVO> accounts = new ArrayList<>();
        snapshot.setAccounts(accounts);
        if (StringUtils.isBlank(edit.getProductOwnerNames()) || CollectionUtil.isEmpty(selection.getProductOwnerIds())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请重新选择产品负责人，缺少人员ID");
        }
        String[] owners = edit.getProductOwnerNames().split(",");
        if (owners.length != selection.getProductOwnerIds().size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "产品负责人姓名与人员ID数量不一致，请重新选择");
        }
        for (int index = 0; index < owners.length; index++) {
            Long ownerId = selection.getProductOwnerIds().get(index);
            if (ownerId == null || ownerId <= 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "产品负责人ID无效，请重新选择");
            }
            accounts.add(new LingxingOpenApiClient.AccountVO(ownerId, owners[index].trim()));
        }
        if (StringUtils.isBlank(edit.getSupplierName()) || selection.getSupplierId() == null || selection.getSupplierId() <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请重新搜索并选择供应商，缺少供应商ID");
        }
        snapshot.getSuppliers().add(new LingxingSupplierClient.SupplierSummary(selection.getSupplierId(), edit.getSupplierName()));
        ArrayNode templates = objectMapper.createArrayNode();
        snapshot.setQcTemplates(templates);
        if (StringUtils.isNotBlank(edit.getQualityTemplate())) {
            if (StringUtils.isBlank(selection.getQcTemplateId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "请重新选择领星质检模板，缺少模板ID");
            }
            templates.addObject().put("name", edit.getQualityTemplate()).put("pqt_id", selection.getQcTemplateId());
        }
        if (Integer.valueOf(1).equals(edit.getSyncScope())) {
            if (StringUtils.isNotBlank(edit.getProcessRoute())) {
                if (StringUtils.isBlank(selection.getProcessRoutingCode())) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "请重新选择工艺路线，缺少工艺路线编号");
                }
                snapshot.getProcessRoutings().add(new BlacklakeOpenApiClient.ProcessRoutingVO(selection.getProcessRoutingCode(), edit.getProcessRoute()));
            }
            if (StringUtils.isNotBlank(edit.getDefaultWarehouse())) {
                if (StringUtils.isBlank(selection.getWarehouseCode())) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "请重新选择默认仓库，缺少仓库编号");
                }
                snapshot.getWarehouseCodes().put(edit.getDefaultWarehouse(), selection.getWarehouseCode());
            }
        }
        return snapshot;
    }

    /**
     *  数据库回滚只清理本次新文件；旧文件必须等提交成功后才能删除。
     *
     * @param uploadedKeys 本次操作新上传的图片对象键
     * @param removedKeys 保存成功后需要删除的旧图片对象键
     */
    private void registerImageCleanup(List<String> uploadedKeys, Set<String> removedKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("货品图片修改必须在事务中执行");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 事务提交成功后删除已从货品中移除的图片。
             */
            @Override
            public void afterCommit() {
                cleanupImages(removedKeys, true);
            }

            /**
             * 事务结束时按提交结果清理不再需要的新上传图片。
             *
             * @param status 事务完成状态，使用Spring事务同步状态常量
             */
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) cleanupImages(uploadedKeys, false);
            }
        });
    }

    /**
     * 逐个清理图片对象，并记录清理失败及原保存操作是否已提交。
     *
     * @param objectKeys 需要删除的公共桶图片对象键
     * @param saved 原货品保存事务是否已经提交
     */
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

    /**
     * 校验页面可编辑字段的编码范围、名称、金额及规格合法性。
     *
     * @param edit 当前主货品或子货品的页面编辑参数
     */
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

    /**
     * 校验一组长宽高必须同时填写或同时留空。
     *
     * @param length 长度，单位沿用对应规格字段
     * @param width 宽度，单位沿用对应规格字段
     * @param height 高度，单位沿用对应规格字段
     * @param label 校验失败时显示的字段或规格组名称
     */
    private void validateDimensions(BigDecimal length, BigDecimal width, BigDecimal height, String label) {
        if ((length != null || width != null || height != null) && (length == null || width == null || height == null)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, label + "请完整填写长、宽、高");
        }
    }

    /**
     * 将货品集合字段序列化为数据库保存的JSON字符串。
     *
     * @param value 待处理字段值
     * @return 序列化后的JSON字符串
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "货品数据无法保存为 JSON");
        }
    }

    /**
     *  主货品按更新时间、主键倒序分页；整个页面的子货品只执行一次批量查询。
     *
     * @param query 货品筛选及分页条件
     * @return 包含一层子货品和数量信息的主货品分页结果
     */
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
        Map<Long, List<CompanyGoodsPO>> childGoods = findProductChildren(goods);
        for (CompanyGoodsPO item : goods) {
            CompanyGoodsListItemVO record = toListItem(item);
            Map<Long, BigDecimal> quantities = relationQuantities(item, childGoods.get(item.getId()));
            for (CompanyGoodsPO childGoodsRecord : childGoods.get(item.getId())) {
                CompanyGoodsListItemVO child = toListItem(childGoodsRecord);
                child.setQuantity(quantities.get(childGoodsRecord.getId()));
                record.getGroupList().add(child);
            }
            records.add(record);
        }
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, records);
    }

    /**
     * 按主键查询完整货品详情，并按产品ID及关系名称加载一层子货品及关联数量。
     *
     * @param id 货品数据库主键
     * @return 货品完整详情；不存在时抛出资源不存在异常
     */
    public CompanyGoodsDetailVO getDetail(Long id) {
        CompanyGoodsPO goods = goodsMapper.findById(id);
        if (goods == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "货品不存在");
        }
        CompanyGoodsDetailVO detail = toDetail(goods);
        List<CompanyGoodsPO> children = findProductChildren(List.of(goods)).get(goods.getId());
        Map<Long, BigDecimal> quantities = relationQuantities(goods, children);
        for (CompanyGoodsPO childGoods : children) {
            CompanyGoodsDetailVO child = toDetail(childGoods);
            child.setQuantity(quantities.get(childGoods.getId()));
            detail.getGroupList().add(child);
        }
        return detail;
    }

    /**
     *  产品ID和主货品关系中的名称共同建立展示关联，批量读取避免逐条查询。
     *
     * @param parents 当前需要加载子货品的主货品记录
     * @return 主货品主键到其一层子货品列表的映射
     */
    private Map<Long, List<CompanyGoodsPO>> findProductChildren(List<CompanyGoodsPO> parents) {
        Map<Long, List<CompanyGoodsPO>> result = new LinkedHashMap<>();
        Map<String, CompanyGoodsPO> parentByProductId = new LinkedHashMap<>();
        Map<Long, Set<String>> relationNames = new LinkedHashMap<>();
        Set<String> productIds = new LinkedHashSet<>();
        for (CompanyGoodsPO parent : parents) {
            result.put(parent.getId(), new ArrayList<>());
            if (!Integer.valueOf(1).equals(parent.getProductType()) || StringUtils.isBlank(parent.getProductId())) {
                continue;
            }
            String key = parent.getProductId().trim().toLowerCase(java.util.Locale.ROOT);
            if (parentByProductId.put(key, parent) != null) {
                throw new BusinessException(ResultCode.DATABASE_ERROR, "同一产品ID存在多个组合主货品");
            }
            Set<String> names = new LinkedHashSet<>();
            for (GoodsSkuRelation relation : readArray(parent.getGroupList(), new TypeReference<List<GoodsSkuRelation>>() {})) {
                if (relation != null && StringUtils.isNotBlank(relation.getGoodsName())) {
                    names.add(relation.getGoodsName());
                }
            }
            relationNames.put(parent.getId(), names);
            productIds.add(parent.getProductId());
        }
        if (CollectionUtil.isEmpty(productIds)) {
            return result;
        }
        Map<Long, Set<String>> matchedNames = new LinkedHashMap<>();
        for (CompanyGoodsPO candidate : goodsMapper.findByProductIds(new ArrayList<>(productIds))) {
            if (StringUtils.isBlank(candidate.getProductId())) {
                continue;
            }
            CompanyGoodsPO parent = parentByProductId.get(candidate.getProductId().trim().toLowerCase(java.util.Locale.ROOT));
            if (parent == null || parent.getId().equals(candidate.getId())) {
                continue;
            }
            if (Integer.valueOf(1).equals(candidate.getProductType())) {
                throw new BusinessException(ResultCode.DATABASE_ERROR, "同一产品ID存在多个组合主货品");
            }
            if (!Integer.valueOf(0).equals(candidate.getProductType())
                    || !relationNames.get(parent.getId()).contains(candidate.getGoodsName())) {
                continue;
            }
            Set<String> names = matchedNames.get(parent.getId());
            if (names == null) {
                names = new LinkedHashSet<>();
                matchedNames.put(parent.getId(), names);
            }
            if (!names.add(candidate.getGoodsName())) {
                throw new BusinessException(ResultCode.DATABASE_ERROR, "同一产品ID存在同名子货品，无法确定关联：" + candidate.getGoodsName());
            }
            result.get(parent.getId()).add(candidate);
        }
        return result;
    }

    /**
     *  数量按已建立关联的货品名称匹配，不受关系中历史SKU影响，不修改原关联数据。
     *
     * @param parent 维护关联或展示数量的主货品记录
     * @param children 同一产品ID下需要匹配比例数量的子货品记录
     * @return 子货品数据库主键到比例数量的映射
     */
    private Map<Long, BigDecimal> relationQuantities(CompanyGoodsPO parent, List<CompanyGoodsPO> children) {
        Map<Long, BigDecimal> quantities = new LinkedHashMap<>();
        if (!Integer.valueOf(1).equals(parent.getProductType())) {
            return quantities;
        }
        Map<String, GoodsSkuRelation> relationsByName = new LinkedHashMap<>();
        for (GoodsSkuRelation relation : readArray(parent.getGroupList(), new TypeReference<List<GoodsSkuRelation>>() {})) {
            if (relation != null && StringUtils.isNotBlank(relation.getGoodsName())) {
                relationsByName.put(relation.getGoodsName(), relation);
            }
        }
        for (CompanyGoodsPO child : children) {
            GoodsSkuRelation relation = relationsByName.get(child.getGoodsName());
            if (relation != null) {
                quantities.put(child.getId(), relation.getQuantity());
            }
        }
        return quantities;
    }

    /**
     * 将数据库货品转换为列表展示对象并补充图片信息。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @return 货品列表展示对象
     */
    private CompanyGoodsListItemVO toListItem(CompanyGoodsPO goods) {
        CompanyGoodsListItemVO item = new CompanyGoodsListItemVO();
        BeanUtils.copyProperties(goods, item, "groupList");
        item.setGroupList(new ArrayList<>());
        item.setImageUrl(imageAccessUrl(readArray(goods.getPictureList(), new TypeReference<List<CompanyGoodsPictureVO>>() {})));
        return item;
    }

    /**
     * 将数据库货品转换为详情对象并解析图片及检验类型。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @return 尚未装配子货品的详情对象
     */
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

    /**
     * 优先选择主图引用，没有主图时取首个有效引用；同一图片优先使用对象键。
     *
     * @param pictures 包含图片地址、对象键及主图标识的图片列表
     * @return 图片对象键或已保存URL；没有可用图片时返回null
     */
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

    /**
     * 判断当前选中图片引用是否对应对象键，供后续生成预签名访问地址。
     *
     * @param pictures 包含图片地址、对象键及主图标识的图片列表
     * @return 图片对象键；未提供时返回null
     */
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

    /**
     * 按图片对象键生成临时访问地址，无法提供时使用已保存图片地址。
     *
     * @param pictures 包含图片地址、对象键及主图标识的图片列表
     * @return 页面访问图片使用的URL
     */
    private String imageAccessUrl(List<CompanyGoodsPictureVO> pictures) {
        String objectKey = primaryImageObjectKey(pictures);
        return objectKey == null ? primaryImage(pictures) : imageService.accessUrl(objectKey);
    }

    /**
     * 解析JSON数组，空字段或JSON空值转换为空列表。
     *
     * @param <T> 业务返回值或JSON数组元素类型
     * @param json 数据库保存的JSON数组文本
     * @param type 保留数组元素类型的Jackson类型信息
     * @return 解析后的列表，未提供数组时为空列表
     */
    private <T> List<T> readArray(String json, TypeReference<List<T>> type) {
        List<T> values = readNullableArray(json, type);
        return values == null ? new ArrayList<>() : values;
    }

    /**
     *  使用抛错的 JSON 解析，避免异常数据被转成空集合后伪装成正常货品。
     *
     * @param <T> 业务返回值或JSON数组元素类型
     * @param json 允许为空的数据库JSON数组文本
     * @param type 保留数组元素类型的Jackson类型信息
     * @return 解析后的列表；字段为null或JSON空值时返回null
     */
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

    /**
     * 转义LIKE通配符并生成包含匹配条件，空白条件不参与查询。
     *
     * @param value 待处理字段值
     * @return 带包含匹配通配符的SQL参数；空白时返回null
     */
    private String likePattern(String value) {
        String keyword = StringUtils.trimToNull(value);
        if (keyword == null) {
            return null;
        }
        // 将输入的通配符作为普通字符处理，防止输入 % 或 _ 时意外扩大查询范围。
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
