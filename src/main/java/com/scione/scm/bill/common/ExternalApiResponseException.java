package com.scione.scm.bill.common;

/** 外部失败响应仅供本地消息落库；异常message仍使用可对外展示的错误说明。 */
public class ExternalApiResponseException extends BusinessException {
    private final String responseBody;

    public ExternalApiResponseException(ResultCode resultCode, String message, String responseBody) {
        super(resultCode, message);
        this.responseBody = responseBody;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
