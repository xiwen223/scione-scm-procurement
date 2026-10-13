package com.scione.scm.bill.domain.contract.enums;

/** 固定业务编码；使用显式 code，保持现有接口及数据库取值。 */
public enum ContractSignLaunchStateEnum {
    QUEUED("QUEUED", "排队中"),
    RUNNING("RUNNING", "执行中"),
    WAIT_CALLBACK("WAIT_CALLBACK", "等待回调"),
    UNKNOWN("UNKNOWN", "结果未知"),
    FAILED("FAILED", "失败"),
    DONE("DONE", "完成");

    private final String code;
    private final String description;

    ContractSignLaunchStateEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() { return code; }

    public String getDescription() { return description; }

    public boolean matches(String value) { return code.equals(value); }

}
