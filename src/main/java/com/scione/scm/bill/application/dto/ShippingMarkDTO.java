package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.shippingmark.ShippingMark;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkDetail;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 箱唛导入单详情读模型。
 */
@Data
public class ShippingMarkDTO {

    private Long id;
    private String billNo;
    private String billName;
    private int status;
    private String statusDesc;
    private String creator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime processedAt;
    private ProcessResultDTO processResult;
    private List<DetailDTO> details;

    @Data
    public static class DetailDTO {
        private Long id;
        private String purchaseOrderNo;
        private String skuCode;
        private String skuName;
        private String skuImage;
        private int status;
        private String statusDesc;
        private String errorReason;
        private String labelFile;
    }

    public static ShippingMarkDTO from(ShippingMark mark) {
        ShippingMarkDTO dto = new ShippingMarkDTO();
        dto.setId(mark.getId());
        dto.setBillNo(mark.getBillNo());
        dto.setBillName(mark.getBillName());
        dto.setStatus(mark.getStatus().getCode());
        dto.setStatusDesc(mark.getStatus().getDesc());
        dto.setCreator(mark.getCreator());
        dto.setCreatedAt(mark.getCreatedAt());
        dto.setProcessedAt(mark.getProcessedAt());
        dto.setProcessResult(ProcessResultDTO.from(mark));
        dto.setDetails(mark.getDetails().stream().map(ShippingMarkDTO::fromDetail).toList());
        return dto;
    }

    private static DetailDTO fromDetail(ShippingMarkDetail detail) {
        DetailDTO dto = new DetailDTO();
        dto.setId(detail.getId());
        dto.setPurchaseOrderNo(detail.getPurchaseOrderNo());
        dto.setSkuCode(detail.getSkuCode());
        dto.setSkuName(detail.getSkuName());
        dto.setSkuImage(detail.getSkuImage());
        dto.setStatus(detail.getStatus().getCode());
        dto.setStatusDesc(detail.getStatus().getDesc());
        dto.setErrorReason(detail.getErrorReason());
        dto.setLabelFile(detail.getLabelFile());
        return dto;
    }
}
