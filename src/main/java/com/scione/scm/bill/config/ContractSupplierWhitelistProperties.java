package com.scione.scm.bill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 可自动或手动创建采购合同的供应商白名单。
 *
 * <p>默认值来自当前业务提供的日常签约供应商清单；可由 Nacos 或应用配置中的
 * {@code contract.supplier-whitelist.names} 覆盖。</p>
 */
@Component
@ConfigurationProperties(prefix = "contract.supplier-whitelist")
public class ContractSupplierWhitelistProperties {

    private Set<String> names = new LinkedHashSet<>(Set.of(
            "吉林省瑞沅科技有限公司", "义乌市双爱纽扣商行", "东莞市辉锐塑胶制品有限公司",
            "深圳市七彩精灵电子商务有限公司", "浦江宇铭饰品有限公司", "东莞市旭轩五金塑胶有限公司",
            "浦江世诚饰品有限公司", "温州寅煌工艺礼品有限公司", "汕头市前搏塑胶制品有限公司",
            "汕头市铭洋玩具有限公司", "浙江曼尔希纸业科技股份有限公司", "义乌市陈港科技有限公司",
            "义乌市卓艺彩印有限公司", "金华市滕军贸易有限公司", "杭州东望工艺品有限公司",
            "龙港市鑫莎包装有限公司", "东莞市唯美包装有限公司", "义乌市义茂纸制品有限公司",
            "义乌市明创玩具有限公司", "浦江县义强包装有限公司", "义乌市轩瑞包装制品有限公司"));

    public Set<String> getNames() {
        return names;
    }

    public void setNames(Set<String> names) {
        this.names = names == null ? new LinkedHashSet<>() : new LinkedHashSet<>(names);
    }

    /** 白名单匹配的是领星供应商默认收款账户的 account_name，不是 supplier_name。 */
    public boolean contains(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            return false;
        }
        return names.stream().filter(name -> name != null)
                .anyMatch(name -> name.trim().equals(accountName.trim()));
    }
}
