package com.scione.scm.bill.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 单个采购服务实例内，所有合同共用的图片下载并发数。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "contract.image-download")
public class ContractImageDownloadProperties {
    @Min(1)
    @Max(100)
    private int concurrency = 20;
}
