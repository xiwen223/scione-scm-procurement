package com.scione.scm.bill.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Mapper 扫描配置。
 */
@Configuration
@MapperScan("com.scione.scm.bill.infrastructure.persistence.mybatis.mapper")
public class MybatisConfiguration {
}
