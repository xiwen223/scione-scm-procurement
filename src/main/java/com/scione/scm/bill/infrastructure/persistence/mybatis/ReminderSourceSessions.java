package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.scione.scm.bill.config.SopReminderProperties;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * 催办来源快照的会话来源。
 * 默认复用 Spring 管理的 SqlSessionFactory；Nacos 指定独立来源库时，
 * 懒加载一个只含来源 Mapper 的工厂，保证所有查询仍走同一套 XML。
 */
@Component
public class ReminderSourceSessions {

    private static final String SOURCE_MAPPER_LOCATION = "classpath*:mapping/SopReminderSourceMapper.xml";

    private final SopReminderProperties properties;
    private final SqlSessionFactory primaryFactory;
    private volatile SqlSessionFactory sourceFactory;

    public ReminderSourceSessions(SopReminderProperties properties, SqlSessionFactory sqlSessionFactory) {
        this.properties = properties;
        this.primaryFactory = sqlSessionFactory;
    }

    /** 快照会话：可重复读，保证任务状态、进度和业务数据来自同一数据库快照。 */
    public SqlSession openSnapshot() {
        return factory().openSession(TransactionIsolationLevel.REPEATABLE_READ);
    }

    private SqlSessionFactory factory() {
        SopReminderProperties.Source source = properties.getSource();
        if (source.getJdbcUrl() == null || source.getJdbcUrl().isBlank()) return primaryFactory;
        if (sourceFactory == null) {
            synchronized (this) {
                if (sourceFactory == null) sourceFactory = build(source);
            }
        }
        return sourceFactory;
    }

    private SqlSessionFactory build(SopReminderProperties.Source source) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(source.getJdbcUrl(), source.getUsername(), source.getPassword());
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        try {
            bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources(SOURCE_MAPPER_LOCATION));
            SqlSessionFactory factory = bean.getObject();
            if (factory == null) throw new IllegalStateException("催办来源库SqlSessionFactory未创建");
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            return factory;
        } catch (Exception exception) {
            throw new IllegalStateException("催办来源库SqlSessionFactory初始化失败", exception);
        }
    }
}
