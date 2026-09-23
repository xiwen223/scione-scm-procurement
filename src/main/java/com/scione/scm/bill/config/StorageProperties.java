package com.scione.scm.bill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * S3 存储配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aws.s3")
public class StorageProperties {

    /**
     * AccessKey
     */
    private String accessKey;

    /**
     * SecretKey
     */
    private String secretKey;

    /**
     * 区域
     */
    private String region;

    /**
     * Bucket名称
     */
    private String bucket;

    /**
     * Endpoint
     */
    private String endpoint;

    /**
     * Bucket访问域名
     *
     * Public Bucket：
     * https://bucket.s3.ap-southeast-1.amazonaws.com
     *
     * 或CloudFront域名：
     * https://cdn.scione.com
     */
    private String domain;
}