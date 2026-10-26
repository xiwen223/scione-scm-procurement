package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** 主键和版本支持无精度损失的整数字符串，拒绝小数和超出 BIGINT 的值。 */
public class CompanyGoodsLongDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return parser.getLongValue();
        }
        if (parser.currentToken() == JsonToken.VALUE_STRING && parser.getText().matches("[0-9]+")) {
            try {
                return Long.valueOf(parser.getText());
            } catch (NumberFormatException exception) {
                throw context.weirdStringException(parser.getText(), Long.class, "超出主键或版本的有效范围");
            }
        }
        throw context.weirdStringException(parser.getText(), Long.class, "必须使用整数或整数字符串");
    }
}
