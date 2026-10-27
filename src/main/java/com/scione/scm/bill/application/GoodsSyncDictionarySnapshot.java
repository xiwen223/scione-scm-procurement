package com.scione.scm.bill.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.infrastructure.blacklake.BlacklakeOpenApiClient;
import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同一次导入的外部字典快照，随消息持久化，消费时不再查询字典。 */
@Data
public class GoodsSyncDictionarySnapshot {
    private List<LingxingSupplierClient.SupplierSummary> suppliers = new ArrayList<>();
    private List<BlacklakeOpenApiClient.ProcessRoutingVO> processRoutings = new ArrayList<>();
    private JsonNode qcTemplates;
    private Map<String, String> warehouseCodes = new LinkedHashMap<>();
}
