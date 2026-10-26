package com.scione.scm.bill.application.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/** 企业货品图片信息，与 picture_list 的存储结构对应。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsPictureVO {
    /** 对象存储文件键；历史数据可能仅包含 pic_url。 */
    private String objectKey;
    /** 历史图片链接；新上传图片使用 object_key。 */
    private String picUrl;
    /** 主图标记：1 是主图，0 是其他图片。 */
    private Integer isPrimary;
}
