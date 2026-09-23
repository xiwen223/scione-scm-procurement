package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BuyerCompanyPO {
    private Long id;
    private String companyName;
    private String companyShortName;
    private String creditCode;
    private String legalPerson;
    private String address;
    private String phone;
    private String bankName;
    private String bankAccount;
    private String sealUrl;
    private String sealBase64;
    private String fadadaSealId;
    private String openCorpId;
    private Integer priority;
    private Integer isActive;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
