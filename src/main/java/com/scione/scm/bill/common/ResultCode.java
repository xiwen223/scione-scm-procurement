package com.scione.scm.bill.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 服务业务响应码；HTTP 状态映射由接口层维护。
 */
@Getter
@RequiredArgsConstructor
public enum ResultCode {

    SUCCESS(0, "Success"),
    SYSTEM_ERROR(1099001, "系统异常"),
    PARAM_ERROR(1099002, "请求参数错误"),
    REQUEST_METHOD_ERROR(1099003, "请求方式错误"),
    JSON_PARSE_ERROR(1099004, "请求报文格式错误"),
    RESOURCE_NOT_FOUND(1099005, "资源不存在"),
    DATABASE_DUPLICATE_KEY(1099101, "数据已存在"),
    DATABASE_ERROR(1099102, "数据库异常"),
    PRODUCT_NOT_FOUND(1011001, "商品不存在"),
    LINGXING_API_ERROR(1011002, "领星商品服务调用失败"),
    FADADA_API_ERROR(1011003, "法大大电子签章服务调用失败"),
    BUYER_COMPANY_NOT_FOUND(1012001, "需方公司不存在"),
    BUYER_COMPANY_CREDIT_CODE_DUPLICATE(1012002, "统一社会信用代码已存在"),
    BUYER_COMPANY_OPEN_CORPID_DUPLICATE(1012003, "法大大公司 ID 已存在"),
    BUYER_COMPANY_DEFAULT_REQUIRED(1012004, "系统必须保留一个启用的默认合同需方"),
    BUYER_COMPANY_DEFAULT_CANNOT_DELETE(1012005, "默认合同需方不可删除"),
    BUYER_COMPANY_IN_USE(1012006, "需方公司已被合同引用，不能删除"),
    BUYER_COMPANY_SEAL_NOT_IDENTIFIED(1012007, "公司未完成法大大实名认证，无法创建印章"),
    CONTRACT_TEMPLATE_NOT_FOUND(1013001, "合同模板不存在"),
    CONTRACT_TEMPLATE_DEFAULT_REQUIRED(1013002, "系统必须保留一个启用的默认合同模板"),
    CONTRACT_TEMPLATE_DEFAULT_CANNOT_DELETE(1013003, "默认合同模板不可删除"),
    CONTRACT_TEMPLATE_IN_USE(1013004, "合同模板已被合同引用，不能删除"),
    STORAGE_API_ERROR(1014001, "文件存储服务调用失败"),
    PROCUREMENT_OPERATION_LOG_BUSINESS_TYPE_INVALID(1015001, "供应链操作日志业务类型无效"),
    CONTRACT_TEMPLATE_FILL_FAILED(1013001, "合同模板填充失败"),
    SHIPPING_MARK_ALREADY_EXISTS(1010001, "单据编号已存在"),
    SHIPPING_MARK_NOT_FOUND(1010002, "单据不存在"),
    IMPORT_FILE_UNSUPPORTED(1010003, "仅支持 .xlsx / .xls 格式的 Excel 文件"),
    IMPORT_FILE_INVALID(1010004, "Excel 模板不正确或未包含有效数据"),
    IMPORT_FILE_TOO_MANY_ROWS(1010005, "Excel 数据行超过允许数量"),
    IMPORT_FILE_TOO_LARGE(1010009, "上传文件不能超过允许大小"),
    SHIPPING_MARK_DETAIL_NOT_FOUND(1010006, "箱唛明细不存在"),
    SHIPPING_MARK_NOT_READY(1010007, "箱唛尚未生成完成，暂不可操作"),
    SHIPPING_MARK_FILE_NOT_FOUND(1010008, "箱唛文件不存在"),
    SHIPPING_MARK_BATCH_TOO_LARGE(1010010, "批量下载文件过大，请减少选择数量");

    private final int code;
    private final String message;
}
