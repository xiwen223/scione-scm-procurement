package com.scione.scm.bill.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.scm.bill.application.vo.LingxingProductVO;
import java.util.List;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.infrastructure.lingxing.LingxingQcTemplateClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/qc-templates")
@Tag(name = "质检模板查询")
public class QcTemplateController {

    @Resource
    private LingxingQcTemplateClient lingxingQcTemplateClient;

    @GetMapping
    @Operation(summary = "查询领星全部质检模板原始 JSON")
    public ApiResponse<JsonNode> getAllQcTemplateList() {
        return ApiResponse.success(lingxingQcTemplateClient.getAllQcTemplateList());
    }

    /** 通过产品名称搜索领星后台产品列表，调用方指定offset，其余筛选及分页参数固定。 */
    @PostMapping("/products")
    @Operation(summary = "按产品名称查询领星后台产品列表")
    public ApiResponse<List<LingxingProductVO>> queryProducts(@RequestParam("searchValue") String searchValue,
                                                             @RequestParam("offset") int offset) {
        return ApiResponse.success(lingxingQcTemplateClient.queryProducts(searchValue, offset));
    }
}
