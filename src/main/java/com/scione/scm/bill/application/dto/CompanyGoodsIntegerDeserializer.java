package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** 编辑接口的整数不允许被 Jackson 从小数静默截断。 */
public class CompanyGoodsIntegerDeserializer extends JsonDeserializer<Integer> {
    /**
     * 严格反序列化整数编码，拒绝小数及非整数JSON类型。
     *
     * @param parser 当前JSON字段的Jackson读取器
     * @param context 用于生成字段反序列化错误的上下文
     * @return 解析出的32位整数
     * @throws IOException JSON读取失败或字段格式不符合整数要求时抛出
     */
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return parser.getIntValue();
        }
        throw context.weirdStringException(parser.getText(), Integer.class, "必须使用整数编码或数量");
    }
}
