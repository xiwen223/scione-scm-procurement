package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ContractTemplatePO {
    private Long id;
    private String templateName;
    private Integer contractType;
    private String objectKey;
    private Integer isDefault;
    private Integer isActive;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
