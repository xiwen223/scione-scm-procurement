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
     * 写回法大大建章结果：核验 ID（{@code /seal/create-by-image} 的 {@code verifyId}）与本次建章指定的
     * 归属主体 {@code entityId}。
     *
     * <p>在上传印章的「落库 → 调法大大」之后立即调用：印章审核结果是异步回调，
     * 回调报文只带 verifyId 而没有本地主键，必须先把它落到公司行上，回调才定位得到。
     * entityId 与它同一条语句写入 —— 两者都只在建章这一次调用里解析得到，分开写会出现
     * 「verifyId 已落库、归属主体还没落」的中间态，签署阶段读到空主体便会放行到法大大侧报错。</p>
     *
     * <p>{@code verifyId} 是 19 位长整型，与列 {@code seal_verify_id}（bigint）同类型，
     * 保证回调侧 {@code WHERE seal_verify_id = ?} 是精确的整数比较。</p>
     *
     * @param entityId 建章时匹配到的归属主体 ID；为 null 表示未匹配到同名主体，
     *                 此时把 {@code entity_id} 一并置空，保持与「印章按 openCorpId 默认归属」一致
     */
    int updateSealCreateResult(
            @Param("id") Long id,
            @Param("verifyId") Long verifyId,
            @Param("entityId") String entityId);

    /**
     * 移除签章：清空 seal_name / seal_url / seal_base64 / fadada_seal_id / seal_verify_id /
     * seal_flow_status / seal_failed_reason。这些字段由法大大侧维护，不进全量 update，故单独提供。
     */
    int clearSeal(@Param("id") Long id);

    /**
     * 法大大印章审核通过回调（event = seal-verify-successed）：按 seal_verify_id 定位公司，
     * 写入法大大印章 ID 并把 seal_flow_status 置为 1（审核成功）、清空 seal_failed_reason。
     *
     * <p>定位键是上传印章时写入的 verifyId（法大大侧唯一），不再按 open_corpid ——
     * 后者允许重复，用它定位会一次命中多行、把审核结果广播到其它公司。</p>
     *
     * <p>语句幂等，法大大重复回调结果一致；印章已被移除（该列被清空）时不产生残留写入。</p>
     *
     * @return 实际更新的行数，0 表示没有匹配到待更新记录
     */
    int updateSealVerified(@Param("verifyId") Long verifyId, @Param("sealId") String sealId);

    /**
     * 法大大印章免验证签授权回调（event = seal-authorize-free-sign）：按 fadada_seal_id 定位公司，
     * 写入免验证签场景码与授权到期时间。
     *
     * <p>免验证签在法大大侧是「印章 + 场景码」维度的授权，因此按印章定位；该事件发生在印章审核通过之后，
     * 此时 fadada_seal_id 已由 {@link #updateSealVerified} 写入。</p>
     *
     * <p>两个字段成对覆盖（不做「有值才写」）：回调是授权状态的权威来源，重新授权时旧到期时间必须能刷新。
     * expireTime 为 null 表示不限期，与签署侧的过期校验口径一致。语句幂等，重复回调结果一致。</p>
     *
     * @param sealId     法大大印章 ID，对应 fadada_seal_id
     * @param businessId 免验证签场景码，写入 fadada_free_sign_business_id
     * @param expireTime 授权到期时间（已由服务层把毫秒时间戳转换完成），null 表示不限期
     * @return 实际更新的行数，0 表示没有匹配到待更新记录
     */
    int updateFreeSignAuthorization(
            @Param("sealId") String sealId,
            @Param("businessId") String businessId,
            @Param("expireTime") LocalDateTime expireTime);

    /**
     * 法大大印章审核不通过回调（event = seal-verify-failed）：按 seal_verify_id 定位公司，
     * 把 seal_flow_status 置为 2（审核失败），并把回调里的原因写入 seal_failed_reason。
     *
     * <p>定位键与 {@link #updateSealVerified} 完全一致（verifyId）；印章已被移除或公司已逻辑删除时
     * 不产生残留写入；语句幂等，法大大重复回调结果一致。</p>
     *
     * @param verifyId 法大大受理创章时返回的核验 ID（19 位长整型），对应 {@code seal_verify_id}
     * @param reason   审核不通过原因，可为 null（此时只更新状态，失败原因留空）
     * @return 实际更新的行数，0 表示没有匹配到待更新记录
     */
    int updateSealVerifyFailed(@Param("verifyId") Long verifyId, @Param("reason") String reason);

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


    boolean existsDefaultExcept(@Param("id") Long id);

    int clearDefaultExcept(@Param("id") Long id);

    /**
     * 查询默认需方公司（priority=1 且 is_active=1）。
     */
    BuyerCompanyPO selectDefault();
}
