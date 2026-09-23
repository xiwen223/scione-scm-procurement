package com.scione.scm.bill.domain.contract;

/**
 * 某个采购单号名下已存在的「有效合同」（未取消、未逻辑删除）的轻量引用。
 *
 * <p>用于在创建合同链路里提前告知「这张采购单已经有合同了」：
 * {@code contract.purchase_order_no} 上只有普通索引，重复判定靠应用层先查后写，
 * 用户在表单里把明细、数量、单价都改完再提交时才被拒，体验很差。
 * 预填阶段拿到这个引用就能直接拦住。</p>
 *
 * @param id         合同 ID
 * @param contractNo 合同编号
 * @param status     合同状态码（1=创建 2=签署中 3=履行中 4=完成 5=取消）
 * @param statusText 状态中文描述，前端可直接展示
 */
public record ActiveContractRef(long id, String contractNo, int status, String statusText) {
}
