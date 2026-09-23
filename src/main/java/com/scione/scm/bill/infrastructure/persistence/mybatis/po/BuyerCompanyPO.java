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
    /**
     * 印章核验 ID：调用法大大 {@code /seal/create-by-image} 成功后的返回值，上传印章时立即写入。
     * 印章审核结果回调（{@code seal-verify-successed} / {@code seal-verify-failed}）以它作为定位键，
     * 取代原先「按 open_corpid 定位」的做法 —— open_corpid 允许重复，用它定位会一次命中多行。
     *
     * <p>法大大该字段是 19 位长整型，本地列 {@code seal_verify_id} 也是 {@code bigint}，
     * 两边同为整数才能做精确等值比较；若列退化成字符型，MySQL 比较时会把列转成 DOUBLE，
     * 尾数精度不足会让相邻的 verifyId 互相误命中。</p>
     */
    private Long sealVerifyId;
    /** 印章审核状态：0-审核中，1-审核成功，2-审核失败（由法大大回调写入） */
    private Integer sealFlowStatus;
    /** 印章审核不通过的原因，仅 sealFlowStatus = 2 时有值（由法大大回调写入），为空表示无失败原因 */
    private String sealFailedReason;
    private String fadadaFreeSignBusinessId;
    private LocalDateTime fadadaFreeSignExpireTime;
    private String openCorpId;
    /**
     * 法大大企业主体 ID：建章时按 {@code company_name} 在 {@code /corp/entity/get-list} 里匹配出的主体，
     * 与 {@link #sealVerifyId} 在同一步写入（见 {@code BuyerCompanyApplicationService#uploadSeal}）。
     *
     * <p>一个 {@code open_corpid} 下可以有多个主体（企业本身 primary / 成员企业 subsidiary），
     * 「这枚印章归属哪个主体」由该列表达；为空表示建章时未匹配到同名主体，印章按 openCorpId 默认归属。</p>
     */
    private String entityId;
    private Integer priority;
    private Integer isActive;
    /** 实名认证状态：1-已认证，0-未认证（由法大大 identStatus 判定） */
    private Integer identStatus;
    /** 逻辑删除标记：0-未删除，1-已删除（删除公司只置该标记，不做物理删除） */
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
