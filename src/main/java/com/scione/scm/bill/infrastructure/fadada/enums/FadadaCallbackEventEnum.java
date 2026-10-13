package com.scione.scm.bill.infrastructure.fadada.enums;

/** 固定业务编码；使用显式 code，保持现有接口及数据库取值。 */
public enum FadadaCallbackEventEnum {
    SEAL_VERIFY_SUCCESS("seal-verify-successed", "印章审核通过"),
    SEAL_VERIFY_FAILED("seal-verify-failed", "印章审核失败"),
    SEAL_AUTHORIZE_FREE_SIGN("seal-authorize-free-sign", "印章免验证签授权"),
    SIGN_TASK_SIGNED("sign-task-signed", "参与方签署完成"),
    SIGN_TASK_FINISHED("sign-task-finished", "签署任务完成"),
    SIGN_TASK_SIGN_FAILED("sign-task-sign-failed", "签署失败"),
    SIGN_TASK_SIGN_REJECTED("sign-task-sign-rejected", "拒绝签署"),
    SIGN_TASK_CANCELED("sign-task-canceled", "签署任务取消"),
    SIGN_TASK_EXPIRE("sign-task-expire", "签署任务过期"),
    SIGN_TASK_ABOLISH("sign-task-abolish", "签署任务作废"),
    UNKNOWN("", "未处理事件");

    private final String code;
    private final String description;

    FadadaCallbackEventEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() { return code; }

    public String getDescription() { return description; }

    public boolean matches(String value) { return code.equals(value); }

    /** 未知或空事件继续走原有未处理事件分支。 */
    public static FadadaCallbackEventEnum fromCode(String code) {
        for (FadadaCallbackEventEnum event : values()) {
            if (event.matches(code)) return event;
        }
        return UNKNOWN;
    }

}
