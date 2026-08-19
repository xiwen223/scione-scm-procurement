package com.scione.scm.bill.application.port;

/**
 * 投递箱唛后台处理任务的应用层端口。
 */
public interface ShippingMarkTaskDispatcher {

    void dispatch(Long markId);
}
