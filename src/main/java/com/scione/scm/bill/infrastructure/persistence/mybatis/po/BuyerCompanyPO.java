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
    /** 印章审核状态：0-审核中，1-审核成功，2-审核失败（由法大大回调写入） */
    private Integer sealFlowStatus;
    /** 印章审核不通过的原因，仅 sealFlowStatus = 2 时有值（由法大大回调写入），为空表示无失败原因 */
    private String sealFailedReason;
    private String openCorpId;
    private Integer priority;
    private Integer isActive;
    /** 实名认证状态：1-已认证，0-未认证（由法大大 identStatus 判定） */
    private Integer identStatus;
    /** 逻辑删除标记：0-未删除，1-已删除（删除公司只置该标记，不做物理删除） */
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
