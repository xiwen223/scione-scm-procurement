package com.scione.scm.bill.infrastructure.fadada;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要补充法大大告警业务信息的方法，由切面自动建立并清理上下文。
 * <p>用于经 Spring 代理调用的公共方法；同类直接调用不会触发切面。
 * 默认从第一个参数获取合同 ID 或公司 ID，合同场景也支持直接传入 Contract 对象。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FadadaAlertContext {
    /** 业务类型，决定查询合同信息还是公司信息。 */
    Type value();
    /** 提供业务 ID 或合同对象的参数下标，从 0 开始。 */
    int argumentIndex() default 0;

    /** 告警上下文支持的业务类型。 */
    enum Type {
        /** 合同业务：补充需方公司名称和合同编号。 */
        CONTRACT,
        /** 公司印章业务：补充公司名称。 */
        COMPANY
    }
}
