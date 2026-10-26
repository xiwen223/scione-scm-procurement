package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface LingxingCookiesCacheMapper {

    @Select("SELECT cookies FROM lx_cookies_cache WHERE cookie_date = #{cookieDate}")
    String findCookiesByDate(@Param("cookieDate") LocalDate cookieDate);
}
