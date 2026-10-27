package com.scione.scm.bill.application;

import cn.hutool.core.collection.CollectionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.scione.scm.bill.common.ExternalApiResponseException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.infrastructure.blacklake.BlacklakeOpenApiClient;
import com.scione.scm.bill.infrastructure.lingxing.LingxingOpenApiClient;
import com.scione.scm.bill.infrastructure.lingxing.LingxingQcTemplateClient;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.CompanyGoodsMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 导入写库前加载本批次需要的字典，名称是否存在仍由消费者校验。 */
@Service
public class CompanyGoodsDictionaryService {
    @Resource
    private CompanyGoodsMapper goodsMapper;
    @Resource
    private LingxingOpenApiClient lingxingClient;
    @Resource
    private LingxingQcTemplateClient qcTemplateClient;
    @Resource
    private BlacklakeOpenApiClient blacklakeClient;

    public GoodsSyncDictionarySnapshot loadForImport(List<CompanyGoodsPO> importedGoods) {
        GoodsSyncDictionarySnapshot snapshot = new GoodsSyncDictionarySnapshot();
        if (CollectionUtil.isEmpty(importedGoods)) return snapshot;
        List<String> productIds = new ArrayList<>();
        for (CompanyGoodsPO goods : importedGoods) productIds.add(goods.getProductId());
        List<CompanyGoodsPO> storedGoods = goodsMapper.findByProductIds(productIds);
        boolean needsTemplates = false;
        boolean needsRoutings = false;
        Set<String> warehouseNames = new LinkedHashSet<>();
        for (CompanyGoodsPO goods : importedGoods) {
            CompanyGoodsPO stored = null;
            for (CompanyGoodsPO candidate : storedGoods) {
                if (candidate.getProductId().equalsIgnoreCase(goods.getProductId())
                        && candidate.getGoodsName().equalsIgnoreCase(goods.getGoodsName())) {
                    stored = candidate;
                    break;
                }
            }
            // Excel空白会保留本地值，字典加载必须包含这些保留字段。
            String template = goods.getQualityTemplate();
            if (template == null && stored != null) template = stored.getQualityTemplate();
            if (template != null) needsTemplates = true;
            Integer scope = goods.getSyncScope();
            if (scope == null && stored != null) scope = stored.getSyncScope();
            if (!Integer.valueOf(1).equals(scope)) continue;
            String route = goods.getProcessRoute();
            if (route == null && stored != null) route = stored.getProcessRoute();
            if (route != null) needsRoutings = true;
            String warehouse = goods.getDefaultWarehouse();
            if (warehouse == null && stored != null) warehouse = stored.getDefaultWarehouse();
            if (warehouse != null) warehouseNames.add(warehouse);
        }
        snapshot.setSuppliers(lingxingClient.findAllSuppliers());
        if (needsTemplates) {
            JsonNode response = qcTemplateClient.getAllQcTemplateList();
            if (!"1".equals(response.path("code").asText()) || !response.path("data").path("list").isArray()) {
                throw new ExternalApiResponseException(ResultCode.LINGXING_API_ERROR,
                        "领星质检模板查询失败或响应格式错误", response.toString());
            }
            snapshot.setQcTemplates(response.path("data").path("list"));
        }
        if (needsRoutings) snapshot.setProcessRoutings(blacklakeClient.queryProcessRoutings());
        for (String warehouseName : warehouseNames) {
            snapshot.getWarehouseCodes().putAll(blacklakeClient.queryWarehouseCodes(warehouseName));
        }
        return snapshot;
    }
}
