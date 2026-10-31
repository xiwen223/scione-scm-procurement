package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface LingxingCookiesCacheMapper {

    /**
     * 按日期读取领星网页后台Cookie缓存。
     *
     * @param cookieDate 需要读取Cookie的业务日期，调用方使用上海时区当天日期
     * @return 该日期缓存的Cookie文本；不存在时返回null
     */
    @Select("SELECT cookies FROM lx_cookies_cache WHERE cookie_date = #{cookieDate}")
    String findCookiesByDate(@Param("cookieDate") LocalDate cookieDate);
}
