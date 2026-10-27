package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

/** 货品同步任务；messageBody 保存导入时快照，不受后续编辑影响。 */
@Data
public class GoodsLocalMessagePO {
    private Long id;
    private String messageId;
    private Long goodsId;
    private String sku;
    private Long goodsVersion;
    private Integer targetSystem;
    private Integer triggerType;
    private String topic;
    private String messageKey;
    private String messageBody;
    private Integer publishStatus;
    private Integer consumeStatus;
    private Integer publishCount;
}
