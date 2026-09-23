package com.scione.scm.bill.application.dto;

/**
 * 印章上传结果。
 *
 * <p>{@code verifyId} 为法大大受理「图片创建印章」后返回的核验 ID（印章审核为异步流程）；
 * {@code company} 为落库后的公司详情，其中 {@code sealUrl} 是对象存储 objectKey、{@code sealName} 是印章名称。
 * 印章图片的 Base64 只在调用法大大时使用，不落库。</p>
 */
public record BuyerCompanySealUploadResponse(
        String verifyId,
        BuyerCompanyDetailResponse company) {
}
