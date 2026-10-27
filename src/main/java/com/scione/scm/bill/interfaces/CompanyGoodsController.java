package com.scione.scm.bill.interfaces;

import com.scione.common.model.PageResult;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.CompanyGoodsService;
import com.scione.scm.bill.infrastructure.blacklake.BlacklakeOpenApiClient;
import com.scione.scm.bill.application.CompanyGoodsLocalSaveService;
import com.scione.scm.bill.application.dto.CompanyGoodsCreateDTO;
import com.scione.scm.bill.application.dto.CompanyGoodsModifyDTO;
import com.scione.scm.bill.application.CompanyGoodsImportService;
import com.scione.scm.bill.application.CompanyGoodsExportService;
import com.scione.scm.bill.application.dto.CompanyGoodsExportDTO;
import com.scione.scm.bill.application.vo.CompanyGoodsImportVO;
import com.scione.scm.bill.application.dto.CompanyGoodsQueryDTO;
import com.scione.scm.bill.application.dto.CompanyGoodsUpdateDTO;
import com.scione.scm.bill.application.vo.CompanyGoodsDetailVO;
import com.scione.scm.bill.application.vo.CompanyGoodsListItemVO;
import com.scione.scm.bill.domain.goods.enums.BlacklakeProductCategoryEnum;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;

/** 企业货品查询与编辑入口，沿用项目响应与分页结构。 */
@RestController
@Validated
@RequestMapping("/company-goods")
public class CompanyGoodsController {
    @Resource
    private CompanyGoodsService companyGoodsService;
    @Resource
    private BlacklakeOpenApiClient blacklakeOpenApiClient;
    @Resource
    private CompanyGoodsImportService companyGoodsImportService;
    @Resource
    private CompanyGoodsExportService companyGoodsExportService;

    @Resource
    private CompanyGoodsLocalSaveService companyGoodsLocalSaveService;

    @PostMapping("/create")
    @Operation(summary = "创建货品", description = "企业商品库创建主货品及完整子货品，同一事务仅保存本地；主货品group_list维护子SKU、数量及名称")
    public ApiResponse<Long> create(@Valid @RequestBody CompanyGoodsCreateDTO request) {
        return ApiResponse.success("货品已创建", companyGoodsLocalSaveService.create(request));
    }

    @PostMapping("/modify")
    @Operation(summary = "修改货品", description = "企业商品库整体修改主、子货品，子项无id则新增，移除只解除关联；空值保留原值，仅保存本地")
    public ApiResponse<Void> modify(@Valid @RequestBody CompanyGoodsModifyDTO request) {
        companyGoodsLocalSaveService.modify(request);
        return ApiResponse.success("货品已修改", null);
    }

    @PostMapping("/export")
    @Operation(summary = "导出企业货品", description = "按勾选的主货品ID导出主、子SKU，使用当前Excel模板")
    public ResponseEntity<byte[]> export(@Valid @RequestBody CompanyGoodsExportDTO request) {
        byte[] content = companyGoodsExportService.export(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("企业货品库_导出.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "批量导入企业货品", description = "Excel按SKU修改已有记录，按企业商品ID分组事务保存，按原状态异步新增或修改外部产品")
    public ApiResponse<CompanyGoodsImportVO> importFile(@RequestPart("file") MultipartFile file) {
        return ApiResponse.success("导入处理完成", companyGoodsImportService.importFile(file));
    }

    @GetMapping("/import/template")
    @Operation(summary = "下载企业货品导入模板")
    public ResponseEntity<byte[]> importTemplate() throws IOException {
        byte[] template = new ClassPathResource("templates/company-goods-import.xlsx").getContentAsByteArray();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("货品信息导入导出模板.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(template);
    }

    @PostMapping("/update")
    @Operation(summary = "编辑企业货品", description = "主、子SKU整体保存后置为已创，并按修改前状态异步新增或修改外部产品")
    public ApiResponse<Void> update(@Valid @RequestBody CompanyGoodsUpdateDTO request) {
        companyGoodsService.update(request);
        return ApiResponse.success("货品已保存", null);
    }

    @GetMapping("/blacklake/inspection-specifications")
    @Operation(summary = "查询黑湖来料检验规范选项", description = "查询最近一年创建的检验规范，返回code-name格式的下拉选项")
    public ApiResponse<List<String>> queryBlacklakeInspectionSpecifications() {
        return ApiResponse.success(blacklakeOpenApiClient.queryInspectionSpecifications());
    }

    /**
     * 查询本地维护的黑湖产品分类，无需入参，按分类编码从 0 到 17 返回名称。
     * @return 项目统一响应内包含全部分类名称列表
     */
    @GetMapping("/blacklake/product-categories")
    @Operation(summary = "查询黑湖产品分类列表", description = "查询黑湖产品分类列表")
    public ApiResponse<List<String>> queryBlacklakeProductCategories() {
        List<String> categoryNames = new ArrayList<>();
        for (BlacklakeProductCategoryEnum category : BlacklakeProductCategoryEnum.values()) {
            categoryNames.add(category.getName());
        }
        return ApiResponse.success(categoryNames);
    }

    /**
     * 主 SKU 分页查询；子 SKU 命中返回所属主货品，不同条件 AND，人员三角色 OR。
     * @param sku SKU 模糊条件
     * @param goodsName 名称模糊条件
     * @param productAttribute 产品属性编码
     * @param productType 主货品类型编码
     * @param goodsStatus 展示状态，2 表示主或子货品存在异常
     * @param productCategory 分类标识
     * @param personnelName 人员名称模糊条件
     * @param page 从 1 开始的页码
     * @param pageSize 页容量，保持文档 pageSIze 参数名
     * @return 主货品分页及每条货品的一层子 SKU
     */
    @GetMapping("/list")
    public ApiResponse<PageResult<CompanyGoodsListItemVO>> list(
            @RequestParam(required = false) @Size(max = 128) String sku,
            @RequestParam(name = "goods_name", required = false) @Size(max = 512) String goodsName,
            @RequestParam(name = "product_attribute", required = false) @Min(0) @Max(2) Integer productAttribute,
            @RequestParam(name = "product_type", required = false) @Min(0) @Max(1) Integer productType,
            @RequestParam(name = "goods_status", required = false) @Min(0) @Max(2) Integer goodsStatus,
            @RequestParam(name = "product_category", required = false) @Size(max = 128) String productCategory,
            @RequestParam(name = "personnel_name", required = false) @Size(max = 512) String personnelName,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "pageSIze", defaultValue = "10") @Min(1) @Max(100) int pageSize) {
        CompanyGoodsQueryDTO query = new CompanyGoodsQueryDTO();
        query.setSku(sku);
        query.setGoodsName(goodsName);
        query.setProductAttribute(productAttribute);
        query.setProductType(productType);
        query.setGoodsStatus(goodsStatus);
        query.setProductCategory(productCategory);
        query.setPersonnelName(personnelName);
        query.setPageNum(page);
        query.setPageSize(pageSize);
        return ApiResponse.success(companyGoodsService.findPage(query));
    }

    /**
     * 按货品主键查看完整详情，关联只展开一层，避免循环及无界查询。
     * @param id 必填正整数主键
     * @return 完整货品及一层完整子货品，不存在时返回项目统一 404 响应
     */
    @GetMapping("/detail")
    public ApiResponse<CompanyGoodsDetailVO> detail(@RequestParam @Min(1) Long id) {
        return ApiResponse.success(companyGoodsService.getDetail(id));
    }
}
