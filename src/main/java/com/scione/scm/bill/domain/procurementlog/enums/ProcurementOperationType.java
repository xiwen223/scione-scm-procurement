package com.scione.scm.bill.domain.procurementlog.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** procurement_operation_log.operation_type 的操作类型枚举。 */
@Getter
@RequiredArgsConstructor
public enum ProcurementOperationType {

    CREATE("CREATE", "新增"),
    UPDATE("UPDATE", "修改"),
    DELETE("DELETE", "删除"),
    UPDATE_SEAL("UPDATE_SEAL", "修改签章"),
    UPLOAD_SEAL("UPLOAD_SEAL", "上传印章"),
    REMOVE_SEAL("REMOVE_SEAL", "移除印章");

    /** 落库值，写入 procurement_operation_log.operation_type（varchar(50)）。 */
    private final String code;

    /** 中文名，用于拼装 operation_desc。 */
    private final String label;
}
