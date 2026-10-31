package com.scione.scm.bill.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.infrastructure.blacklake.BlacklakeOpenApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 黑湖仓库查询入口，使用 Spring 配置的账号登录，不接收外部账号或 Token。
 * 远端登录账号须有管理员权限，异常沿用项目统一异常处理。
 */
@RestController
@Validated
@RequestMapping("/api/v1/blacklake")
@Tag(name = "黑湖接口", description = "使用配置账号查询黑湖仓库")
public class BlacklakeController {

    @Resource
    private BlacklakeOpenApiClient blacklakeOpenApiClient;

    /**
     * 按仓库名称模糊查询：优先使用 Redis Token，未命中才按配置登录并缓存 24 小时。
     * 远端采用默认分页，每页 10 条。
     * @param likeName 仓库名称，必填且不能仅包含空白
     * @return 统一响应内包含黑湖完整 JSON 查询结果及分页信息，不包含登录凭证
     */
    @GetMapping("/warehouses")
    @Operation(summary = "按名称模糊查询黑湖仓库", description = "优先使用缓存 Token 查询，黑湖账号须有管理员权限，默认每页 10 条")
    public ApiResponse<JsonNode> queryWarehouses(
            @RequestParam("likeName") @NotBlank(message = "仓库名称不能为空") String likeName) {
        JsonNode warehouses = blacklakeOpenApiClient.queryWarehouses(likeName);
        return ApiResponse.success(warehouses);
    }
}
