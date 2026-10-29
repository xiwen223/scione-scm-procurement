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
import java.util.List;

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

    /**
     * 结合导入字段及本地保留值，加载本批次同步所需的共享字典。
     *
     * @param importedGoods 通过行级校验且待加载字典的导入货品
     * @return 导入批次共享的字典快照；远程查询失败时抛出异常
     */
    public GoodsSyncDictionarySnapshot loadForImport(List<CompanyGoodsPO> importedGoods) {
        GoodsSyncDictionarySnapshot snapshot = new GoodsSyncDictionarySnapshot();
        if (CollectionUtil.isEmpty(importedGoods)) return snapshot;
        List<String> productIds = new ArrayList<>();
        for (CompanyGoodsPO goods : importedGoods) productIds.add(goods.getProductId());
        List<CompanyGoodsPO> storedGoods = goodsMapper.findByProductIds(productIds);
        List<CompanyGoodsPO> effectiveGoods = new ArrayList<>();
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
            Integer scope = goods.getSyncScope();
            if (scope == null && stored != null) scope = stored.getSyncScope();
            String route = goods.getProcessRoute();
            if (route == null && stored != null) route = stored.getProcessRoute();
            String warehouse = goods.getDefaultWarehouse();
            if (warehouse == null && stored != null) warehouse = stored.getDefaultWarehouse();
            CompanyGoodsPO effective = new CompanyGoodsPO();
            effective.setQualityTemplate(template);
            effective.setSyncScope(scope);
            effective.setProcessRoute(route);
            effective.setDefaultWarehouse(warehouse);
            effectiveGoods.add(effective);
        }
        return loadForSync(effectiveGoods);
    }

    /**
     *  一次导入或编辑共享字典，空列表表示没有待同步货品。
     *
     * @param goodsToSync 本次需要生成外部同步任务的货品列表
     * @return 本次保存操作共享的字典快照；无待同步货品时返回空快照
     */
    public GoodsSyncDictionarySnapshot loadForSync(List<CompanyGoodsPO> goodsToSync) {
        GoodsSyncDictionarySnapshot snapshot = new GoodsSyncDictionarySnapshot();
        if (CollectionUtil.isEmpty(goodsToSync)) return snapshot;
        boolean needsTemplates = false;
        boolean needsRoutings = false;
        boolean needsWarehouses = false;
        for (CompanyGoodsPO goods : goodsToSync) {
            if (goods.getQualityTemplate() != null) needsTemplates = true;
            if (!Integer.valueOf(1).equals(goods.getSyncScope())) continue;
            if (goods.getProcessRoute() != null) needsRoutings = true;
            if (goods.getDefaultWarehouse() != null) needsWarehouses = true;
        }
        snapshot.setAccounts(lingxingClient.queryAccounts());
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
        if (needsWarehouses) snapshot.setWarehouseCodes(blacklakeClient.queryAllWarehouseCodes());
        return snapshot;
    }
}
