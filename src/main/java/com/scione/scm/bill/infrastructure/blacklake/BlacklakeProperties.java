package com.scione.scm.bill.infrastructure.blacklake;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 黑湖连接和默认登录配置，通过 blacklake 配置节点绑定。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "blacklake")
public class BlacklakeProperties {
    /** 黑湖小工单服务地址。 */
    private String endpoint = "https://liteweb.blacklake.cn";
    /** 默认登录手机号；配置后优先使用手机号登录。 */
    private String phone;
    /** 默认登录密码；默认按明文执行 SHA3-224。 */
    private String password;
    /** 默认登录工厂代码；未配置手机号时使用。 */
    private String factoryCode;
    /** 默认登录工厂内用户名；与工厂代码配合使用。 */
    private String username;
    /** 是否加密默认登录密码；false 表示配置的是已加密密码。 */
    private boolean encryptPassword = true;
    /** 建立连接超时，单位毫秒。 */
    private int connectTimeout = 30000;
    /** 请求超时，单位毫秒。 */
    private int readTimeout = 60000;
}
