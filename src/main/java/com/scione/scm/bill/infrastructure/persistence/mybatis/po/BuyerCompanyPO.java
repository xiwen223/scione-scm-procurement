package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BuyerCompanyPO {
    private Long id;
    private String companyName;
    private String companyShortName;
    private String creditCode;
    private String postCode;
    private String fax;
    private String legalPerson;
    private String address;
    private String phone;
    private String bankName;
    private String bankAccount;
    /** 印章名称（新增签章时填写，随 seal_url 一起维护） */
    private String sealName;
    private String sealUrl;
    private String sealBase64;
    private String fadadaSealId;
    private String openCorpId;
    private Integer priority;
    private Integer isActive;
    /** 实名认证状态：1-已认证，0-未认证（由法大大 identStatus 判定） */
    private Integer identStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
