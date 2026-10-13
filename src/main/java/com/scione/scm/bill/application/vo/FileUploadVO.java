package com.scione.scm.bill.application.vo;

/**
 * 文件上传结果；objectKey 为对象存储中的唯一标识，由调用方自行保存到业务表。
 */
public record FileUploadVO(
        String objectKey, String url, String originalName, String contentType, Long size) {
}
