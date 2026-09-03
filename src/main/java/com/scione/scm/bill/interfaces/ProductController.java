package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.ProductAppService;
import com.scione.scm.bill.application.dto.ProductDetailDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品实时查询接口。
 */
@RestController
@Validated
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "商品查询", description = "实时查询领星商品信息")
public class ProductController {

    private final ProductAppService productAppService;

    @GetMapping("/detail")
    @Operation(summary = "按 SKU 查询商品详情")
    public ApiResponse<ProductDetailDTO> detail(
            @RequestParam("sku")
            @NotBlank(message = "SKU 不能为空")
            @Size(max = 64, message = "SKU 长度不能超过 64") String sku) {
        return ApiResponse.success(productAppService.findDetail(sku));
    }
}
