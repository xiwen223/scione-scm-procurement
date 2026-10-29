package com.scione.scm.bill.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.infrastructure.blacklake.BlacklakeOpenApiClient;
import com.scione.scm.bill.infrastructure.lingxing.LingxingOpenApiClient;
import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同一次导入或编辑的外部字典快照，随消息持久化供消费者复用。 */
@Data
public class GoodsSyncDictionarySnapshot {
    private List<LingxingOpenApiClient.AccountVO> accounts;
    private List<LingxingSupplierClient.SupplierSummary> suppliers = new ArrayList<>();
    private List<BlacklakeOpenApiClient.ProcessRoutingVO> processRoutings = new ArrayList<>();
    private JsonNode qcTemplates;
    private Map<String, String> warehouseCodes = new LinkedHashMap<>();
}
