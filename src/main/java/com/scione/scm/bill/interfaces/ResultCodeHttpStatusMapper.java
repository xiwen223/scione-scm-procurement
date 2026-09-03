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
                    IMPORT_FILE_TOO_MANY_ROWS -> HttpStatus.BAD_REQUEST;
            case REQUEST_METHOD_ERROR -> HttpStatus.METHOD_NOT_ALLOWED;
            case RESOURCE_NOT_FOUND, PRODUCT_NOT_FOUND, SHIPPING_MARK_NOT_FOUND, SHIPPING_MARK_DETAIL_NOT_FOUND,
                    SHIPPING_MARK_FILE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DATABASE_DUPLICATE_KEY, SHIPPING_MARK_ALREADY_EXISTS, SHIPPING_MARK_NOT_READY -> HttpStatus.CONFLICT;
            case IMPORT_FILE_TOO_LARGE, SHIPPING_MARK_BATCH_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case LINGXING_API_ERROR -> HttpStatus.BAD_GATEWAY;
            case SYSTEM_ERROR, DATABASE_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
