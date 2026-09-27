package com.scione.scm.bill.application.dto;

/** 法大大印章免验证签授权页链接；链接短期有效，不应持久化。 */
public record FadadaSealFreeSignUrlResponse(
        String freeSignUrl,
        String freeSignShortUrl) {
}
