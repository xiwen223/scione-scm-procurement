package com.scione.scm.bill.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.BuyerCompanyApplicationService;
import com.scione.scm.bill.application.FileApplicationService;
import com.scione.scm.bill.application.dto.BuyerCompanySealUploadResponse;
import com.scione.scm.bill.application.dto.FadadaCorpAuthStatusResponse;
import com.scione.scm.bill.application.dto.FileUrlRequest;
import com.scione.scm.bill.infrastructure.fadada.FadadaOpenApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

/**
 * 法大大开放平台相关接口。
 *
 * <p>当前提供两类能力：</p>
 * <ul>
 *   <li>企业授权状态查询：按企业证件号（统一社会信用代码）调用法大大 {@code /corp/get}，
 *       返回企业绑定、认证、授权与法大大公司 ID 等信息，供前端回显公司名称与法大大公司 ID；</li>
 *   <li>印章上传：把需方公司的印章图片提交给法大大创建企业印章
 *       （{@code /seal/create-by-image}），成功后把图片存到对象存储并写回公司档案；</li>
 *   <li>印章图片访问地址：按 objectKey 换取对象存储的临时访问地址（预签名 URL），
 *       供「我司信息」详情页预览与下载签章图片。</li>
 * </ul>
 */
@RestController
@Validated
@RequestMapping("/api/v1/fadada")
@Tag(name = "法大大", description = "法大大企业授权状态查询、印章上传与签章图片访问地址")
public class FadadaController {

    @Autowired
    private FadadaOpenApiClient fadadaOpenApiClient;

    @Autowired
    private BuyerCompanyApplicationService buyerCompanyApplicationService;

    @Autowired
    private FileApplicationService fileApplicationService;

    @GetMapping("/corp/auth-status")
    @Operation(summary = "根据 corpIdentNo 查询企业授权状态")
    public ApiResponse<FadadaCorpAuthStatusResponse> corpAuthStatus(
            @RequestParam("corpIdentNo") @NotBlank String corpIdentNo) {
        Optional<JsonNode> corp = fadadaOpenApiClient.getCorp(corpIdentNo);
        return ApiResponse.success(corp.map(FadadaCorpAuthStatusResponse::from).orElse(null));
    }

    /**
     * 上传印章图片：公司必须已通过法大大实名认证（buyer_company.ident_status = 1）。
     * 服务端先用图片调法大大创建企业印章，受理成功后把图片上传到对象存储（目录 company-seal），
     * 并把 objectKey 与印章名称分别写入 seal_url / seal_name，印章图片的 Base64 不落库。
     */
    @PostMapping(value = "/seal/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传印章图片并创建法大大企业印章")
    public ApiResponse<BuyerCompanySealUploadResponse> uploadSeal(
            @RequestParam("id") @Min(1) Long id,
            @RequestPart("file") MultipartFile file,
            @RequestParam("sealName") @NotBlank @Size(max = 50) String sealName) {
        return ApiResponse.success(buyerCompanyApplicationService.uploadSeal(id, file, sealName));
    }

    /**
     * 按 objectKey 换取签章图片的临时访问地址（预签名 URL），供「我司信息」详情页预览与下载。
     * 与通用文件接口 {@link FileController} 的 {@code /api/v1/files/file-url} 同源，
     * 这里单独暴露是为了让签章相关的读写都收敛在法大大模块下。
     */
    @PostMapping("/seal/file-url")
    @Operation(summary = "根据 objectKey 获取签章图片访问地址")
    public ApiResponse<String> sealFileUrl(@Valid @RequestBody FileUrlRequest request) {
        return ApiResponse.success(fileApplicationService.presignedUrl(request.objectKey(), request.minutes()));
    }
}
