package com.scione.scm.bill.application;

import cn.hutool.core.collection.CollectionUtil;

import com.scione.scm.bill.domain.goods.enums.GoodsSyncOperationEnum;
import com.scione.scm.bill.application.vo.CompanyGoodsImportVO;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.infrastructure.excel.CompanyGoodsExcelParser;
import com.scione.scm.bill.infrastructure.blacklake.BlacklakeOpenApiClient;
import com.scione.scm.bill.infrastructure.excel.CompanyGoodsExcelParser.ImportRow;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.CompanyGoodsMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/** 模板校验后，按企业商品ID分组，第一行为主货品；按企业商品ID及品名修改已有记录，每组独立事务。 */
@Slf4j
@Service
public class CompanyGoodsImportService {
    @Resource
    private CompanyGoodsExcelParser excelParser;
    @Resource
    private CompanyGoodsMapper mapper;
    @Resource
    private CompanyGoodsSkuService skuService;
    @Resource
    private PlatformTransactionManager transactionManager;
    @Resource
    private CompanyGoodsMessageService messageService;
    @Resource
    private CompanyGoodsDictionaryService dictionaryService;
    @Resource
    private LingxingSupplierClient lingxingSupplierClient;
    @Resource
    private BlacklakeOpenApiClient blacklakeOpenApiClient;

    public CompanyGoodsImportVO importFile(MultipartFile file) {
        List<ImportRow> rows = excelParser.parse(file);
        // 暂停领星供应商、黑湖工艺路线系统校验；恢复时一并启用列表查询和下方逐行校验。
        /*
        // 每次导入只拉取一轮完整列表；远程查询失败时尚未开始任何数据库写入。
        List<LingxingSupplierClient.SupplierSummary> suppliers = lingxingSupplierClient.findAllSuppliers();
        Set<String> supplierNames = new HashSet<>();
        for (LingxingSupplierClient.SupplierSummary supplier : suppliers) {
            supplierNames.add(supplier.supplierName());
        }
        List<BlacklakeOpenApiClient.ProcessRoutingVO> processRoutings = blacklakeOpenApiClient.queryProcessRoutings();
        Set<String> processRoutingNames = new HashSet<>();
        for (BlacklakeOpenApiClient.ProcessRoutingVO routing : processRoutings) {
            processRoutingNames.add(routing.processRoutingName());
        }
        */
        Map<Integer, String> failures = new LinkedHashMap<>();
        Map<String, List<ImportRow>> skuRows = new LinkedHashMap<>();
        Map<String, List<ImportRow>> nameRows = new LinkedHashMap<>();
        for (ImportRow row : rows) {
            // 无效行也参与SKU去重，避免同一文件中的重复记录被遗漏。
            String sku = normalize(row.cells().get(1));
            if (!skuRows.containsKey(sku)) skuRows.put(sku, new ArrayList<>());
            skuRows.get(sku).add(row);
            String name = normalize(row.cells().get(2));
            if (!nameRows.containsKey(name)) nameRows.put(name, new ArrayList<>());
            nameRows.get(name).add(row);
            if (row.error() != null) failures.put(row.rowNumber(), row.error());
            /* 暂停外部系统名称存在性校验，保留供后续恢复。
            else if (!supplierNames.contains(row.goods().getSupplierName())) {
                failures.put(row.rowNumber(), "供应商在领星供应商列表中不存在：" + row.goods().getSupplierName());
            } else if (row.goods().getProcessRoute() != null && !processRoutingNames.contains(row.goods().getProcessRoute())) {
                failures.put(row.rowNumber(), "工艺路线不在黑湖第一页100条查询结果中：" + row.goods().getProcessRoute());
            }
            */
        }
        for (List<ImportRow> duplicates : skuRows.values()) {
            if (duplicates.size() > 1) for (ImportRow row : duplicates) failures.put(row.rowNumber(), "同一文件中的SKU编码重复");
        }
        for (List<ImportRow> duplicates : nameRows.values()) {
            if (duplicates.size() > 1) for (ImportRow row : duplicates) failures.put(row.rowNumber(), "同一文件中的SKU名称重复");
        }
        // 按文件首次出现的顺序分组，第一行始终是主SKU，不能因校验失败换用后续行。
        Map<String, List<ImportRow>> groups = new LinkedHashMap<>();
        for (ImportRow row : rows) {
            String productId = normalize(row.cells().get(0));
            if (!groups.containsKey(productId)) groups.put(productId, new ArrayList<>());
            groups.get(productId).add(row);
        }
        List<CompanyGoodsPO> dictionaryGoods = new ArrayList<>();
        for (List<ImportRow> group : groups.values()) {
            boolean validGroup = true;
            for (ImportRow row : group) {
                if (failures.containsKey(row.rowNumber())) { validGroup = false; break; }
            }
            if (validGroup) for (ImportRow row : group) dictionaryGoods.add(row.goods());
        }
        // 字典接口失败时尚未执行任何货品写入；本次导入共用一份持久化快照。
        GoodsSyncDictionarySnapshot dictionaries = dictionaryService.loadForImport(dictionaryGoods);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        for (List<ImportRow> group : groups.values()) {
            String groupError = null;
            for (ImportRow row : group) {
                if (failures.containsKey(row.rowNumber())) {
                    groupError = "同组第" + row.rowNumber() + "行校验失败：" + failures.get(row.rowNumber());
                    break;
                }
            }
            if (groupError == null) {
                try {
                    transaction.executeWithoutResult(status -> saveGroup(group, dictionaries));
                } catch (BusinessException exception) {
                    groupError = exception.getMessage();
                } catch (DataAccessException exception) {
                    log.error("货品导入保存失败，productId={}", group.get(0).goods().getProductId(), exception);
                    groupError = "数据库保存失败，本组已回滚，请联系管理员查看日志";
                }
            }
            if (groupError != null) {
                for (ImportRow row : group) failures.put(row.rowNumber(), groupError);
            }
        }

        CompanyGoodsImportVO result = new CompanyGoodsImportVO();
        result.setFailureCount(failures.size());
        result.setSuccessCount(rows.size() - failures.size());
        for (ImportRow row : rows) {
            CompanyGoodsImportVO.RowResultVO record = new CompanyGoodsImportVO.RowResultVO();
            record.setRowNumber(row.rowNumber());
            record.setSku(row.cells().get(1));
            record.setSuccess(!failures.containsKey(row.rowNumber()));
            record.setMessage(failures.getOrDefault(row.rowNumber(), "本地修改成功，外部同步已进入异步队列"));
            result.getRecords().add(record);
        }
        result.setFailedFileBase64(excelParser.failureFile(rows, failures));
        return result;
    }

    private void saveGroup(List<ImportRow> group, GoodsSyncDictionarySnapshot dictionaries) {
        List<CompanyGoodsPO> updatedGoods = new ArrayList<>();
        Map<Long, CompanyGoodsPO> originals = new LinkedHashMap<>();
        List<Integer> previousStatuses = new ArrayList<>();
        List<Integer> previousScopes = new ArrayList<>();
        for (ImportRow row : group) {
            CompanyGoodsPO incoming = row.goods();
            List<CompanyGoodsPO> matches = mapper.lockByProductIdAndGoodsName(incoming.getProductId(), incoming.getGoodsName());
            if (CollectionUtil.isEmpty(matches)) throw invalid("第" + row.rowNumber() + "行企业商品ID和SKU名称对应的货品不存在，导入仅支持修改");
            if (matches.size() != 1) throw invalid("第" + row.rowNumber() + "行企业商品ID和SKU名称对应多条货品，无法确定修改记录");
            CompanyGoodsPO stored = matches.get(0);
            originals.put(stored.getId(), stored);
            if (stored.getGoodsStatus() == null || (stored.getGoodsStatus() != GoodsSyncOperationEnum.CREATE.getCode() && stored.getGoodsStatus() != GoodsSyncOperationEnum.UPDATE.getCode())) {
                throw invalid("第" + row.rowNumber() + "行货品状态无效，不能确定外部新增或修改操作");
            }
            CompanyGoodsPO updated = new CompanyGoodsPO();
            BeanUtils.copyProperties(stored, updated);
            BeanWrapperImpl source = new BeanWrapperImpl(incoming);
            BeanWrapperImpl target = new BeanWrapperImpl(updated);
            for (java.beans.PropertyDescriptor property : source.getPropertyDescriptors()) {
                String name = property.getName();
                if (!target.isWritableProperty(name) || "groupList".equals(name) || "productType".equals(name)) continue;
                Object value = source.getPropertyValue(name);
                if (value != null) target.setPropertyValue(name, value);
            }
            previousStatuses.add(stored.getGoodsStatus());
            previousScopes.add(stored.getSyncScope());
            updatedGoods.add(updated);
        }
        skuService.prepareChanges(updatedGoods, originals);
        CompanyGoodsPO parent = updatedGoods.get(0);
        // 导入不重建主子关系和产品类型；已有引用中的编码随改码维护。
        // 子货品先写入，主货品最后写入；修改、状态和消息在同一事务中保存。
        for (int index = 1; index < updatedGoods.size(); index++) {
            saveRow(updatedGoods.get(index), previousStatuses.get(index), previousScopes.get(index), dictionaries);
        }
        saveRow(parent, previousStatuses.get(0), previousScopes.get(0), dictionaries);
    }

    private void saveRow(CompanyGoodsPO goods, Integer previousStatus, Integer previousScope, GoodsSyncDictionarySnapshot dictionaries) {
        Long expectedVersion = goods.getVersion();
        goods.setGoodsStatus(1);
        if (mapper.updateImport(goods, expectedVersion) != 1) {
            throw new BusinessException(ResultCode.COMPANY_GOODS_VERSION_CONFLICT);
        }
        goods.setVersion(expectedVersion + 1);
        messageService.createSyncMessages(goods, previousStatus, previousScope, dictionaries);
    }

    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
}
