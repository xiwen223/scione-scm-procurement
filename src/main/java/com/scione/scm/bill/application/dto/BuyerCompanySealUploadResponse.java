package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 印章上传结果。
 *
 * <p>{@code verifyId} 为法大大受理「图片创建印章」后返回的核验 ID（印章审核为异步流程）；
 * {@code company} 为落库后的公司详情，其中 {@code sealUrl} 是对象存储 objectKey、{@code sealName} 是印章名称。
 * 印章图片的 Base64 只在调用法大大时使用，不落库。</p>
 *
 * <p><b>verifyId 服务端是 19 位长整型，对外仍序列化成字符串</b>：它超过 JavaScript 的
 * {@code Number.MAX_SAFE_INTEGER}（2^53-1，约 16 位十进制），直接以 JSON number 输出会被前端
 * 静默截断成错误的值；前端契约（{@code lib/api/fadada.ts} 的 {@code FadadaSealUploadResult}）
 * 声明的也正是 {@code string | null}。故此处固定用 {@link ToStringSerializer} 转字符串输出，
 * 仅内部的存储与比较使用 long。</p>
 *
 * <p>注：该字段只是受理回执，前端展示用途；公司详情里的 {@code sealVerifyId} 不对外返回。</p>
 */
public record BuyerCompanySealUploadResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        Long verifyId,
        BuyerCompanyDetailResponse company) {
}
