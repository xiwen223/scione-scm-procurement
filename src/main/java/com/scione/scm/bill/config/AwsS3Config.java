package com.scione.scm.bill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * AWS S3 配置。
 */
@Configuration
public class AwsS3Config {

    /**
     * 创建 S3 客户端。
     */
    @Bean
    public S3Client s3Client(StorageProperties properties) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                properties.getAccessKey(),
                properties.getSecretKey()
        );

        return S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(properties.getRegion()))
                .endpointOverride(URI.create(properties.getEndpoint()))
                .build();
    }
}