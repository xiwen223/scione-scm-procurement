package com.scione.scm.bill.domain.company;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需方公司领域模型。对应 buyer_company 表。
 */
@Data
public class BuyerCompany {
    private Long id;
    private String companyName;
    private String companyShortName;
    private String creditCode;
    private String legalPerson;
    private String address;
    private String postCode;
    private String phone;
    private String fax;
    private String bankName;
    private String bankAccount;
    private String sealUrl;
    private String fadadaSealId;
    private String fadadaFreeSignBusinessId;
    private LocalDateTime fadadaFreeSignExpireTime;
    /** 法大大企业 OpenCorpId，指定企业印章签署时作为 actorOpenId。 */
    private String openCorpId;
    private Integer priority;
    private Integer isActive;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String entityId;
}
