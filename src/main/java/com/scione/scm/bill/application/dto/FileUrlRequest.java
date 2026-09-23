package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 获取文件访问地址的请求；minutes 为空时由服务端取默认有效期。
 */
public record FileUrlRequest(
        @NotBlank(message = "objectKey 不能为空")
        @Size(max = 512, message = "objectKey 长度不能超过 512")
        String objectKey,
        @Min(value = 1, message = "有效期必须大于 0 分钟")
        @Max(value = 1440, message = "有效期不能超过 1440 分钟") Long minutes) {
}
