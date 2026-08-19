package com.scione.scm.bill.common;

import lombok.Getter;

/**
 * 可安全返回给客户端的业务异常。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }
}
