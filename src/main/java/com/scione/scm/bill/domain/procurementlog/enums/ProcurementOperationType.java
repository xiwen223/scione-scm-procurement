package com.scione.scm.bill.domain.procurementlog.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** procurement_operation_log.operation_type 的操作类型枚举。 */
@Getter
@RequiredArgsConstructor
public enum ProcurementOperationType {

    CREATE("CREATE", "新增", false),
    UPDATE("UPDATE", "修改", true),
    DELETE("DELETE", "删除", false),
    UPLOAD_SEAL("UPLOAD_SEAL", "上传印章", false),
    REMOVE_SEAL("REMOVE_SEAL", "移除印章", false),

    // 以下三种由合同模块写入 procurement_operation_log（business_type=1），本模块不产出，
    // 只在此登记，供日志页把 operation_type 显示成中文并可筛选（对应 ContractOperationLog 里的常量）。
    // MODIFY 字面也是「修改」，为避免与 UPDATE 的「修改」在筛选下拉里重名，中文名加了来源前缀。
    MODIFY("MODIFY", "合同修改", false),
    SKIP_CREATE("SKIP_CREATE", "跳过创建", false),
    CREATE_FAILED("CREATE_FAILED", "合同自动创建失败", true),
    START_SIGN("START_SIGN", "发起签署", false),
    DOWNLOAD_FAILED("DOWNLOAD_FAILED", "合同下载失败", true);

    /** 落库值，写入 procurement_operation_log.operation_type（varchar(50)）。 */
    private final String code;

    /** 中文名，用于拼装 operation_desc。 */
    private final String label;

    /**
     * 是否记录操作详情（procurement_operation_log.operation_details）。
     * 只有「修改」记录逐字段的「字段名 / 修改前 / 修改后」，其余操作类型一律不写详情，
     * 避免日志里堆积没有检索价值的字段快照。
     */
    private final boolean detailSupported;
}
