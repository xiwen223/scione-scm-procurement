package com.scione.scm.bill.interfaces;

import com.scione.scm.bill.common.ResultCode;
import org.springframework.http.HttpStatus;

/**
 * HTTP 传输层对业务码的状态码映射。
 */
final class ResultCodeHttpStatusMapper {

    private ResultCodeHttpStatusMapper() {
    }

    static HttpStatus statusOf(ResultCode resultCode) {
        return switch (resultCode) {
            case SUCCESS -> HttpStatus.OK;
            case PARAM_ERROR, JSON_PARSE_ERROR, IMPORT_FILE_UNSUPPORTED, IMPORT_FILE_INVALID,
                    IMPORT_FILE_TOO_MANY_ROWS, PROCUREMENT_OPERATION_LOG_BUSINESS_TYPE_INVALID -> HttpStatus.BAD_REQUEST;
            case REQUEST_METHOD_ERROR -> HttpStatus.METHOD_NOT_ALLOWED;
            case RESOURCE_NOT_FOUND, PRODUCT_NOT_FOUND, SHIPPING_MARK_NOT_FOUND, SHIPPING_MARK_DETAIL_NOT_FOUND,
                    SHIPPING_MARK_FILE_NOT_FOUND, BUYER_COMPANY_NOT_FOUND,
                    CONTRACT_TEMPLATE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DATABASE_DUPLICATE_KEY, SHIPPING_MARK_ALREADY_EXISTS, SHIPPING_MARK_NOT_READY,
                    BUYER_COMPANY_CREDIT_CODE_DUPLICATE, BUYER_COMPANY_OPEN_CORPID_DUPLICATE,
                    BUYER_COMPANY_DEFAULT_REQUIRED, BUYER_COMPANY_DEFAULT_CANNOT_DELETE, BUYER_COMPANY_IN_USE,
                    BUYER_COMPANY_SEAL_NOT_IDENTIFIED,
                    CONTRACT_TEMPLATE_DEFAULT_REQUIRED, CONTRACT_TEMPLATE_DEFAULT_CANNOT_DELETE,
                    CONTRACT_TEMPLATE_IN_USE -> HttpStatus.CONFLICT;
            case IMPORT_FILE_TOO_LARGE, SHIPPING_MARK_BATCH_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case LINGXING_API_ERROR, FADADA_API_ERROR, STORAGE_API_ERROR -> HttpStatus.BAD_GATEWAY;
            case SYSTEM_ERROR, DATABASE_ERROR, CONTRACT_TEMPLATE_FILL_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
