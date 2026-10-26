package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * reminder_push_record 的批次行（record_type=1）持久化对象：只用于插入并回填自增主键。
 */
@Data
public class ReminderPushBatchPO {

    private Long id;
    private LocalDateTime scheduledAt;
    private String recipientUserid;
    private LocalDateTime sentAt;
    private String sendResult;
}
