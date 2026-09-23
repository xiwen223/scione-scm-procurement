package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Size;

/**
 * 签章单独维护的请求体：sealUrl 传对象存储 objectKey 表示设置签章（此时 sealName 必填），
 * 传空或 null 表示移除签章（此时 seal_name 一并清空）。
 * 与全量更新（BuyerCompanyUpsertRequest）区分，避免列表页改签章时覆盖公司其他字段。
 */
public record BuyerCompanySealRequest(
        @Size(max = 512) String sealUrl,
        /** 印章名称，新增签章时必填 */
        @Size(max = 50) String sealName) {
}
