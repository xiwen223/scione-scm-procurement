package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 要给人员A发一条消息（ReminderPushBatchPO），里面有三条代办，即明细(PushDetailInsert)
 */
@Data
public class ReminderPushBatchPO {

    private Long id;//自增主键，插入后回填
    private LocalDateTime scheduledAt;//计划催办时点（该轮的业务时点）
    private String recipientUserid;//实际收件人企微userid
    private LocalDateTime sentAt;//实际发出时间
    private String sendResult;//发送上下文JSON（含消息体、token、重试信息）
}
