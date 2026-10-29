package com.scione.scm.bill.common;

/** 外部失败响应仅供本地消息落库；异常message仍使用可对外展示的错误说明。 */
public class ExternalApiResponseException extends BusinessException {
    private final String responseBody;

    /**
     * 创建保留原始第三方响应的业务异常，供消费者记录失败详情。
     *
     * @param resultCode 统一业务异常状态码
     * @param message 向调用方说明的业务失败原因
     * @param responseBody 第三方接口返回的原始响应字符串
     */
    public ExternalApiResponseException(ResultCode resultCode, String message, String responseBody) {
        super(resultCode, message);
        this.responseBody = responseBody;
    }

    /**
     * 获取异常携带的第三方原始响应。
     *
     * @return 待写入消息失败详情字段的响应字符串
     */
    public String getResponseBody() {
        return responseBody;
    }
}
