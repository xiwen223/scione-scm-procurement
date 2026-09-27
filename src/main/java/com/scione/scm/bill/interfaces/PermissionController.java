package com.scione.scm.bill.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.infrastructure.fadada.FadadaOpenApiClient;
import com.scione.scm.bill.infrastructure.lingxing.LingxingOpenApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * 角色权限查询接口。
 */
@RestController
@Validated
@RequestMapping("/api/v1/permissions")
@Tag(name = "权限查询", description = "查询角色是否在 roleList 中")
public class PermissionController {

    /**
     * 我方公司按钮操作权限名单，逗号分隔。application-dev.yml 提供默认值，Nacos 中的同名配置优先级更高。
     * 冒号后的空串是兜底默认值：其他 profile 未配置时解析为空列表而不是启动失败。
     */
    @Value("${permission.buyerCompanyList:}")
    private List<String> buyerCompanyList;

    /**
     * 合同模板查看菜单权限名单，逗号分隔。application-dev.yml 提供默认值，Nacos 中的同名配置优先级更高。
     * 冒号后的空串是兜底默认值：其他 profile 未配置时解析为空列表而不是启动失败。
     */
    @Value("${permission.contractTemplateList:}")
    private List<String> contractTemplateList;

    @Autowired
    private LingxingOpenApiClient lingxingOpenApiClient;

    @Autowired
    private FadadaOpenApiClient fadadaOpenApiClient;

    @GetMapping("/checkBuyerCompanyList")
    @Operation(summary = "查询当前用户是否有我方公司按钮操作权限")
    public ApiResponse<Boolean> checkBuyerCompanyList(@RequestHeader("X-User-Email") String userEmail) {
        return ApiResponse.success(buyerCompanyList.contains(userEmail.trim()));
    }

    @GetMapping("/checkContractTemplate")
    @Operation(summary = "查询当前用户是否有合同模板查看菜单权限")
    public ApiResponse<Boolean> checkContractTemplateList(@RequestHeader("X-User-Email") String userEmail) {
        return ApiResponse.success(contractTemplateList.contains(userEmail.trim()));
    }

    @GetMapping("/api/test")
    public ApiResponse<JsonNode> ApiTest(@RequestParam("id") Long id) {
        Optional<JsonNode> supplierById = lingxingOpenApiClient.findSupplierById(id);
        if (supplierById.isPresent()) {
            JsonNode jsonNode = supplierById.get();
            return ApiResponse.success(jsonNode);
        }
        return ApiResponse.success(null);
    }

    @GetMapping("/api/test1")
    public ApiResponse<JsonNode> ApiTest1(@RequestParam("corpIdentNo") String openCorpId) {
        Optional<JsonNode> supplierById = fadadaOpenApiClient.getCorp(openCorpId);
        if (supplierById.isPresent()) {
            JsonNode jsonNode = supplierById.get();
            return ApiResponse.success(jsonNode);
        }
        return ApiResponse.success(null);
    }
}
