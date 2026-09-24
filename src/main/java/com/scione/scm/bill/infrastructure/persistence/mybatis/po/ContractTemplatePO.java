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
    /** 逻辑删除标记：0-未删除，1-已删除（删除模板只置该标记，不做物理删除） */
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
