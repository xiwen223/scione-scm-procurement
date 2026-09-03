package com.scione.scm.bill.common;

import lombok.Getter;

/**
 * 可安全返回给客户端的业务异常。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message == null || message.isBlank() ? resultCode.getMessage() : message);
        this.resultCode = resultCode;
    }
}
