package com.scione.scm.bill.domain.sop.enums;

/**
 * 发送前复核结论；由SopReminderStatusVerifier对每个候选单据判定。
 * ELIGIBLE=可以发；EXITED=业务状态已变化不应再催；UNKNOWN=查不到可靠结果，按未知处理不发送。
 */
public enum Decision { ELIGIBLE, EXITED, UNKNOWN }
