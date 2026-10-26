package com.scione.scm.bill.application;

import cn.hutool.core.collection.CollectionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ExternalApiResponseException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.goods.GoodsSkuRelation;
import com.scione.scm.bill.domain.goods.enums.GoodsInspectionTypeEnum;
import com.scione.scm.bill.infrastructure.blacklake.BlacklakeOpenApiClient;
import com.scione.scm.bill.infrastructure.lingxing.LingxingOpenApiClient;
import com.scione.scm.bill.infrastructure.lingxing.LingxingQcTemplateClient;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.*;

/** 名称只在消费时转换为外部编码；本地快照保留用户填写的名称。 */
@Service
public class CompanyGoodsExternalCreateService {
    private static final int DEFAULT_CHILD_QUANTITY = 2;
    private static final Map<String, String> COUNTRY_CODES = Map.ofEntries(
            Map.entry("美国", "US"), Map.entry("加拿大", "CA"), Map.entry("墨西哥", "MX"), Map.entry("日本", "JP"),
            Map.entry("英国", "UK"), Map.entry("德国", "DE"), Map.entry("法国", "FR"), Map.entry("西班牙", "ES"),
            Map.entry("意大利", "IT"), Map.entry("荷兰", "NL"), Map.entry("澳洲", "AU"), Map.entry("澳大利亚", "AU"),
            Map.entry("新加坡", "SG"), Map.entry("印度", "IN"), Map.entry("阿联酋", "AE"), Map.entry("沙特阿拉伯", "SA"),
            Map.entry("巴西", "BR"), Map.entry("瑞典", "SE"), Map.entry("波兰", "PL"), Map.entry("比利时", "BE"),
            Map.entry("土耳其", "TR"), Map.entry("乌克兰", "UA"), Map.entry("匈牙利", "HU"), Map.entry("巴基斯坦", "PK"),
            Map.entry("黎巴嫩", "LB"), Map.entry("奥地利", "AT"), Map.entry("瑞士", "CH"), Map.entry("捷克", "CZ"),
            Map.entry("丹麦", "DK"), Map.entry("爱尔兰", "IE"), Map.entry("卢森堡", "LU"), Map.entry("挪威", "NO"),
            Map.entry("葡萄牙", "PT"), Map.entry("斯洛伐克", "SK"), Map.entry("俄罗斯", "RU"), Map.entry("哈萨克斯坦", "KZ"),
            Map.entry("白俄罗斯", "BY"), Map.entry("智利", "CL"), Map.entry("韩国", "KR"));
    @Resource
    private LingxingOpenApiClient lingxingClient;
    @Resource
    private LingxingQcTemplateClient qcTemplateClient;
    @Resource
    private BlacklakeOpenApiClient blacklakeClient;
    @Resource
    private ObjectMapper objectMapper;

    public void createInLingxing(CompanyGoodsPO goods) {
        // set接口兼具编辑能力；先明确拒绝已存在SKU，禁止借导入覆盖远端记录。
        lingxingClient.ensureProductAbsent(goods.getSku());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sku", goods.getSku());
        payload.put("product_name", goods.getGoodsName());
        payload.put("unit", goods.getUnit());
        List<Map<String, Object>> groups = new ArrayList<>();
        for (GoodsSkuRelation child : childRelations(goods)) {
            groups.add(Map.of("sku", child.getSku(), "quantity", child.getQuantity() == null ? DEFAULT_CHILD_QUANTITY : child.getQuantity()));
        }
        if (CollectionUtil.isNotEmpty(groups)) payload.put("group_list", groups);
        List<Long> ownerIds = new ArrayList<>();
        // todo：暂时不要，没有授权
        // List<LingxingOpenApiClient.AccountVO> accounts = lingxingClient.queryAccounts();
        // for (String owner : goods.getProductOwnerNames().split(",")) {
        //     Long ownerId = null;
        //     for (LingxingOpenApiClient.AccountVO account : accounts) {
        //         if (owner.trim().equals(account.realname())) { ownerId = account.uid(); break; }
        //     }
        //     if (ownerId == null) throw invalid("领星产品负责人不存在：" + owner.trim());
        //     ownerIds.add(ownerId);
        // }
        payload.put("product_duty_uids", ownerIds);
        payload.put("is_append_product_duty", 0);
        putLingxing(payload, "cg_opt_username", goods.getPurchaserName());
        putLingxing(payload, "product_developer", goods.getDeveloper());
        putLingxing(payload, "cg_price", goods.getPurchaseCost());
        putLingxing(payload, "cg_product_length", goods.getCgProductLength());
        putLingxing(payload, "cg_product_width", goods.getCgProductWidth());
        putLingxing(payload, "cg_product_height", goods.getCgProductHeight());
        putLingxing(payload, "cg_product_net_weight", goods.getNetWeight());
        putLingxing(payload, "cg_product_gross_weight", goods.getGrossWeight());
        putLingxing(payload, "cg_package_length", goods.getCgPackageLength());
        putLingxing(payload, "cg_package_width", goods.getCgPackageWidth());
        putLingxing(payload, "cg_package_height", goods.getCgPackageHeight());
        putLingxing(payload, "cg_box_length", goods.getCgBoxLength());
        putLingxing(payload, "cg_box_width", goods.getCgBoxWidth());
        putLingxing(payload, "cg_box_height", goods.getCgBoxHeight());
        putLingxing(payload, "cg_box_weight", goods.getCartonWeight());
        putLingxing(payload, "cg_box_pcs", goods.getCartonQuantity());
        Long supplierId = null;
        for (var supplier : lingxingClient.findAllSuppliers()) {
            if (goods.getSupplierName().equals(supplier.supplierName())) { supplierId = supplier.supplierId(); break; }
        }
        if (supplierId == null) throw invalid("领星供应商不存在：" + goods.getSupplierName());
        payload.put("supplier_quote", List.of(Map.of("erp_supplier_id", supplierId, "is_primary", 1,
                "supplier_product_url", List.of(), "quotes", List.of(Map.of("currency", "CNY", "is_tax", 0,
                        "tax_rate", "0", "step_prices", List.of(Map.of("moq", 1, "price_with_tax", goods.getSupplierQuote().toPlainString())))))));
        Map<String, Object> inspection = new LinkedHashMap<>();
        inspection.put("qc_method", goods.getQualityInspectionMethod());
        inspection.put("custom_qc_template", Map.of("template_item", List.of()));
        if (goods.getQualityTemplate() != null) {
            JsonNode response = qcTemplateClient.getAllQcTemplateList();
            if (!"1".equals(response.path("code").asText()) || !response.path("data").path("list").isArray()) {
                throw new ExternalApiResponseException(ResultCode.LINGXING_API_ERROR, "领星质检模板查询失败或响应格式错误", response.toString());
            }
            String templateId = null;
            for (JsonNode template : response.path("data").path("list")) {
                if (goods.getQualityTemplate().equals(template.path("name").asText())) {
                    templateId = template.path("pqt_id").asText();
                    break;
                }
            }
            if (templateId == null || templateId.isBlank()) throw invalid("领星质检模板不存在或缺少ID：" + goods.getQualityTemplate());
            inspection.put("pqt_id", templateId);
        }
        payload.put("new_qc_standard", inspection);
        Map<String, Object> declaration = new LinkedHashMap<>();
        putLingxing(declaration, "customs_export_name", goods.getCustomsNameCn());
        putLingxing(declaration, "customs_import_name", goods.getCustomsNameEn());
        putLingxing(declaration, "customs_declaration_hs_code", goods.getCustomsDeclarationHsCode());
        // 顶层申报金额字段为string，保留两位小数，避免嵌套字段文档int类型造成精度丢失。
        putLingxing(payload, "bg_customs_import_price", goods.getCustomsImportPrice());
        if (goods.getCustomsImportPrice() != null) payload.put("currency", "USD");
        if (!declaration.isEmpty()) payload.put("declaration", declaration);
        Map<String, Object> clearance = new LinkedHashMap<>();
        putLingxing(clearance, "customs_clearance_material", goods.getMaterialCn());
        putLingxing(clearance, "customs_clearance_en_material", goods.getMaterialEn());
        putLingxing(clearance, "customs_clearance_usage", goods.getUsageCn());
        putLingxing(clearance, "customs_clearance_en_usage", goods.getUsageEn());
        if (!clearance.isEmpty()) payload.put("clearance", clearance);
        if (goods.getClearancePrice() != null || goods.getBgImportHsCode() != null) {
            String country = COUNTRY_CODES.get(goods.getClearanceCountryCode());
            if (country == null && COUNTRY_CODES.containsValue(goods.getClearanceCountryCode())) country = goods.getClearanceCountryCode();
            if (country == null) throw invalid("领星清关国家不支持或未填写：" + goods.getClearanceCountryCode());
            Map<String, Object> logistics = new LinkedHashMap<>();
            putLingxing(logistics, country + "_clearance_price", goods.getClearancePrice());
            if (goods.getClearancePrice() != null) logistics.put(country + "_clearance_price_currency", "USD");
            putLingxing(logistics, country + "_bg_import_hs_code", goods.getBgImportHsCode());
            payload.put("product_logistics_list", logistics);
        }
        lingxingClient.createProduct(payload);
    }

    public void createInBlacklake(CompanyGoodsPO goods) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productCode", goods.getSku());
        payload.put("productName", goods.getGoodsName());
        payload.put("unit", goods.getUnit());
        put(payload, "originType", goods.getProductAttribute());
        put(payload, "criticalFlag", goods.getIsKeyPart());
        put(payload, "costPrice", goods.getPurchaseCost());
        if (goods.getProcessRoute() != null) {
            String routeCode = null;
            for (var route : blacklakeClient.queryProcessRoutings()) {
                if (goods.getProcessRoute().equals(route.processRoutingName())) { routeCode = route.processRoutingCode(); break; }
            }
            if (routeCode == null) throw invalid("工艺路线不在黑湖第一页100条中：" + goods.getProcessRoute());
            payload.put("model", routeCode);
        }
        List<Integer> inspectionCodes = new ArrayList<>();
        if (goods.getInspectionType() != null) {
            List<String> names;
            try { names = objectMapper.readValue(goods.getInspectionType(), new TypeReference<List<String>>() { }); }
            catch (JsonProcessingException exception) { throw invalid("检验类型快照格式错误"); }
            for (String name : names) {
                Integer code = null;
                for (GoodsInspectionTypeEnum type : GoodsInspectionTypeEnum.values()) {
                    if (type.getName().equals(name)) { code = type.getCode(); break; }
                }
                if (code == null) throw invalid("未知检验类型：" + name);
                inspectionCodes.add(code);
            }
        }
        if (CollectionUtil.isNotEmpty(inspectionCodes)) payload.put("inspectionSpecificationsTypes", inspectionCodes);
        if (goods.getIncomingInspectionStandard() != null) payload.put("materialIncomingInspectionSpecificationsCode",
                blacklakeClient.findInspectionSpecificationCode(goods.getIncomingInspectionStandard()));
        if (goods.getDefaultWarehouse() != null) payload.put("warehouseCode", blacklakeClient.findWarehouseCode(goods.getDefaultWarehouse()));
        payload.put("customFieldValues", List.of(Map.of("name", "产品分类", "value", goods.getProductCategory())));
        blacklakeClient.createProduct(payload);
    }

    private List<GoodsSkuRelation> childRelations(CompanyGoodsPO goods) {
        try { return objectMapper.readValue(goods.getGroupList(), new TypeReference<List<GoodsSkuRelation>>() { }); }
        catch (JsonProcessingException exception) { throw invalid("子SKU快照格式错误"); }
    }

    private void putLingxing(Map<String, Object> payload, String key, Object value) {
        if (value instanceof java.math.BigDecimal decimal) payload.put(key, decimal.toPlainString());
        else put(payload, key, value);
    }

    private void put(Map<String, Object> payload, String key, Object value) {
        if (value != null) payload.put(key, value);
    }

    private BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
}
