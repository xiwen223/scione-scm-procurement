package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.BuyerCompanyPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BuyerCompanyMapper {

    int insert(BuyerCompanyPO company);

    int update(BuyerCompanyPO company);

    /** 仅更新签章（图片 + 印章名称），避免列表页单独维护签章时覆盖公司其他字段 */
    int updateSeal(@Param("id") Long id, @Param("sealUrl") String sealUrl, @Param("sealName") String sealName);

    /**
     * 移除签章：清空 seal_name / seal_url / seal_base64 / fadada_seal_id / seal_flow_status / seal_failed_reason。
     * 这些字段由法大大侧维护，不进全量 update，故单独提供。
     */
    int clearSeal(@Param("id") Long id);

    /**
     * 法大大印章审核通过回调（event = seal-verify-successed）：按 open_corpid 定位公司，
     * 写入法大大印章 ID 并把 seal_flow_status 置为 1（审核成功）、清空 seal_failed_reason。
     *
     * <p>只命中「仍持有签章且处于审核中 / 历史未写状态」的记录，印章已被移除时不会产生残留写入；
     * 语句幂等，法大大重复回调结果一致。</p>
     *
     * @return 实际更新的行数，0 表示没有匹配到待更新记录
     */
    int updateSealVerified(@Param("openCorpId") String openCorpId, @Param("sealId") String sealId);

    /**
     * 法大大印章免验证签授权回调（event = seal-authorize-free-sign）：按 open_corpid 定位公司，
     * 写入免验证签场景码与授权到期时间。
     *
     * <p>两个字段成对覆盖（不做「有值才写」）：回调是授权状态的权威来源，重新授权时旧到期时间必须能刷新。
     * expireTime 为 null 表示不限期，与签署侧的过期校验口径一致。语句幂等，重复回调结果一致。</p>
     *
     * @param businessId 免验证签场景码，写入 fadada_free_sign_business_id
     * @param expireTime 授权到期时间（已由服务层把毫秒时间戳转换完成），null 表示不限期
     * @return 实际更新的行数，0 表示没有匹配到待更新记录
     */
    int updateFreeSignAuthorization(
            @Param("openCorpId") String openCorpId,
            @Param("businessId") String businessId,
            @Param("expireTime") LocalDateTime expireTime);

    /**
     * 法大大印章审核不通过回调（event = seal-verify-failed）：按 open_corpid 定位公司，
     * 把 seal_flow_status 置为 2（审核失败），并把回调里的原因写入 seal_failed_reason。
     *
     * <p>守卫与 {@link #updateSealVerified} 完全一致：只命中「仍持有签章且处于审核中 / 历史未写状态」的记录，
     * 印章已被移除或公司已逻辑删除时不产生残留写入；语句幂等，法大大重复回调结果一致。</p>
     *
     * @param reason 审核不通过原因，可为 null（此时只更新状态，失败原因留空）
     * @return 实际更新的行数，0 表示没有匹配到待更新记录
     */
    int updateSealVerifyFailed(@Param("openCorpId") String openCorpId, @Param("reason") String reason);

    Optional<BuyerCompanyPO> findById(@Param("id") Long id);

    long count(
            @Param("keyword") String keyword,
            @Param("isActive") Integer isActive,
            @Param("defaultOnly") boolean defaultOnly);

    List<BuyerCompanyPO> findPage(
            @Param("keyword") String keyword,
            @Param("isActive") Integer isActive,
            @Param("defaultOnly") boolean defaultOnly,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    boolean existsByCreditCode(
            @Param("creditCode") String creditCode,
            @Param("excludeId") Long excludeId);

    boolean existsByOpenCorpId(
            @Param("openCorpId") String openCorpId,
            @Param("excludeId") Long excludeId);

    boolean existsDefaultExcept(@Param("id") Long id);

    int clearDefaultExcept(@Param("id") Long id);

    /**
     * 查询默认需方公司（priority=1 且 is_active=1）。
     */
    BuyerCompanyPO selectDefault();
}
