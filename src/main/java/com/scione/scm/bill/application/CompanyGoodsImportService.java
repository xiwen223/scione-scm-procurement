package com.scione.scm.bill.application;

import cn.hutool.core.collection.CollectionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.application.vo.CompanyGoodsImportVO;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.goods.GoodsSkuRelation;
import com.scione.scm.bill.infrastructure.excel.CompanyGoodsExcelParser;
import com.scione.scm.bill.infrastructure.blacklake.BlacklakeOpenApiClient;
import com.scione.scm.bill.infrastructure.excel.CompanyGoodsExcelParser.ImportRow;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.CompanyGoodsMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.math.BigDecimal;

/** 模板校验后，先保存无子项货品，再保存关联主货品；仅新增，每行独立事务，已有SKU拒绝导入。 */
@Slf4j
@Service
public class CompanyGoodsImportService {
    private static final BigDecimal DEFAULT_CHILD_QUANTITY = BigDecimal.valueOf(2);
    @Resource
    private CompanyGoodsExcelParser excelParser;
    @Resource
    private CompanyGoodsMapper mapper;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private PlatformTransactionManager transactionManager;
    @Resource
    private CompanyGoodsMessageService messageService;
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
        // 仅支持一层关系。预先拒绝多层与循环关系，不受文件行顺序影响。
        for (ImportRow row : rows) {
            if (failures.containsKey(row.rowNumber()) || row.childSku() == null) continue;
            List<ImportRow> children = skuRows.get(normalize(row.childSku()));
            if (children != null) {
                if (children.size() != 1 || failures.containsKey(children.get(0).rowNumber())) {
                    failures.put(row.rowNumber(), "关联子SKU在本次文件中校验失败");
                } else if (children.get(0).childSku() != null) {
                    failures.put(row.rowNumber(), "仅支持一层子SKU，不允许多层或循环关联");
                }
            }
        }

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // 先导入叶子，再导入主货品，允许主货品在文件中排在子货品前面。
        List<ImportRow> ordered = new ArrayList<>();
        for (ImportRow row : rows) if (row.childSku() == null) ordered.add(row);
        for (ImportRow row : rows) if (row.childSku() != null) ordered.add(row);
        for (ImportRow row : ordered) {
            if (failures.containsKey(row.rowNumber())) continue;
            List<ImportRow> children = row.childSku() == null ? null : skuRows.get(normalize(row.childSku()));
            if (children != null && failures.containsKey(children.get(0).rowNumber())) {
                failures.put(row.rowNumber(), "关联子SKU在本次文件中导入失败");
                continue;
            }
            try {
                transaction.executeWithoutResult(status -> saveRow(row));
            } catch (BusinessException exception) {
                failures.put(row.rowNumber(), exception.getMessage());
            } catch (DataAccessException exception) {
                log.error("货品导入保存失败，rowNumber={}, sku={}", row.rowNumber(), row.goods().getSku(), exception);
                failures.put(row.rowNumber(), "数据库保存失败，本行已回滚，请联系管理员查看日志");
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
            record.setMessage(failures.getOrDefault(row.rowNumber(), "本地保存成功，外部创建已进入异步队列"));
            result.getRecords().add(record);
        }
        result.setFailedFileBase64(excelParser.failureFile(rows, failures));
        return result;
    }

    private void saveRow(ImportRow row) {
        CompanyGoodsPO goods = row.goods();
        // 当前表没有SKU、名称唯一索引，通过现有RR事务中的锁定读串行化冲突写入。
        List<CompanyGoodsPO> conflicts = mapper.lockImportConflicts(goods.getSku(), goods.getGoodsName());
        for (CompanyGoodsPO conflict : conflicts) {
            if (normalize(goods.getSku()).equals(normalize(conflict.getSku()))) {
                throw invalid("SKU已存在，导入仅支持新增，请通过编辑功能修改已有货品");
            }
        }
        if (CollectionUtil.isNotEmpty(conflicts)) throw invalid("SKU名称已被其他货品使用");
        goods.setProductType(row.childSku() == null ? 0 : 1);
        if (row.childSku() == null) goods.setGroupList("[]");
        if (row.childSku() != null) {
            List<CompanyGoodsPO> children = mapper.findBySkus(List.of(row.childSku()));
            if (CollectionUtil.isEmpty(children)) throw invalid("关联子SKU不存在或尚未成功导入");
            if (children.size() != 1) throw invalid("关联子SKU在数据库中重复");
            CompanyGoodsPO child = children.get(0);
            if (Objects.equals(child.getProductType(), 1) || CollectionUtil.isNotEmpty(relations(child.getGroupList()))) {
                throw invalid("仅支持关联普通产品，不允许多层或循环关联");
            }
            GoodsSkuRelation relation = new GoodsSkuRelation();
            relation.setSku(child.getSku());
            relation.setQuantity(DEFAULT_CHILD_QUANTITY);
            try { goods.setGroupList(objectMapper.writeValueAsString(List.of(relation))); }
            catch (JsonProcessingException exception) { throw new IllegalStateException("子SKU关系序列化失败", exception); }
        }
        goods.setUnit("个");
        goods.setVersion(0L);
        goods.setGoodsStatus(1);
        int affected = mapper.insertImport(goods);
        if (affected != 1) throw invalid("货品保存失败，本行未生效");
        messageService.createImportMessages(goods);
    }

    private List<GoodsSkuRelation> relations(String json) {
        if (json == null) return Collections.emptyList();
        try {
            List<GoodsSkuRelation> relations = objectMapper.readValue(json, new TypeReference<List<GoodsSkuRelation>>() { });
            if (relations == null) throw invalid("已有货品关联数据异常，请先修复");
            for (GoodsSkuRelation relation : relations) {
                if (relation == null || relation.getSku() == null || relation.getSku().isBlank()) throw invalid("已有货品关联数据异常，请先修复");
            }
            return relations;
        } catch (JsonProcessingException exception) { throw invalid("已有货品关联数据异常，请先修复"); }
    }

    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
}
