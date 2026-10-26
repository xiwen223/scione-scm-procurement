package com.scione.scm.bill.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.infrastructure.lingxing.LingxingQcTemplateClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
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
}
