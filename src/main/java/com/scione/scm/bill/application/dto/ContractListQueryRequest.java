package com.scione.scm.bill.application.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 合同列表查询请求参数。
 */
@Data
public class ContractListQueryRequest {

    /** 合同编号（精确匹配） */
    private String contractNo;

    /** 采购单号（精确匹配） */
    private String purchaseOrderNo;

    /** 供应商名称（模糊匹配） */
    private String supplierName;

    /** 合同状态：1-创建 2-签署中 3-履行中 4-完成 5-取消 */
    private Integer status;

    /**
     * 签署时间开始：按我方（需方）签署时间过滤，即 contract.sign_start_time（发起签署并自动盖章的时间）。
     * 未签署的合同不落在任何签署时间区间内。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /**
     * 签署时间结束：含当天（SQL 里按次日 0 点前比较）。同上，按 sign_start_time 过滤。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /** 页码（默认1） */
    private Integer pageNum = 1;

    /** 每页数量（默认10，最大100） */
    private Integer pageSize = 10;

    /**
     * 获取 LIMIT offset。
     */
    public int getOffset() {
        return (pageNum - 1) * pageSize;
    }

    /**
     * 校验并修正分页参数。
     */
    public void validateAndFix() {
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 10;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
    }
}
