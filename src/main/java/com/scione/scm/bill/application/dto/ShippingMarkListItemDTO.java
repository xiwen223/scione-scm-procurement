package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.shippingmark.ShippingMark;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 箱唛导入列表项。
 */
@Data
public class ShippingMarkListItemDTO {

    private Long id;
    private String billNo;
    private String billName;
    private int status;
    private String statusDesc;
    private ProcessResultDTO processResult;
    private String creator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public static ShippingMarkListItemDTO from(ShippingMark mark) {
        ShippingMarkListItemDTO dto = new ShippingMarkListItemDTO();
        dto.setId(mark.getId());
        dto.setBillNo(mark.getBillNo());
        dto.setBillName(mark.getBillName());
        dto.setStatus(mark.getStatus().getCode());
        dto.setStatusDesc(mark.getStatus().getDesc());
        dto.setProcessResult(ProcessResultDTO.from(mark));
        dto.setCreator(mark.getCreator());
        dto.setCreateTime(mark.getCreatedAt());
        return dto;
    }
}
