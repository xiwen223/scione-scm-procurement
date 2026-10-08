package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.BuyerCompanyDetailResponse;
import com.scione.scm.bill.application.dto.BuyerCompanyListItemResponse;
import com.scione.scm.bill.application.dto.BuyerCompanySealRequest;
import com.scione.scm.bill.application.dto.BuyerCompanySealUploadResponse;
import com.scione.scm.bill.application.dto.BuyerCompanyUpsertRequest;
import com.scione.scm.bill.application.dto.FileUploadResponse;
import com.scione.scm.bill.application.dto.FadadaSealFreeSignUrlResponse;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.infrastructure.fadada.FadadaOpenApiClient;
import com.scione.scm.bill.infrastructure.fadada.FadadaAlertContext;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.BuyerCompanyMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.BuyerCompanyPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
@RequiredArgsConstructor
public class BuyerCompanyApplicationService {

    private static final int MAX_SEAL_BYTES = 2 * 1024 * 1024;

    /** 印章审核状态：审核中（法大大侧流程未结束，不允许删除印章） */
    private static final int SEAL_FLOW_AUDITING = 0;

    /** 印章审核状态：审核成功（法大大侧已存在有效印章，删除前需先停用） */
    private static final int SEAL_FLOW_APPROVED = 1;

    /** 印章审核状态：审核失败（法大大侧审核未通过，详情页展示失败原因） */
    private static final int SEAL_FLOW_REJECTED = 2;

    /** 印章图片在对象存储中的目录，与前端上传约定一致。 */
    private static final String SEAL_UPLOAD_FOLDER = "company-seal";

    /** 印章名称上限，与 buyer_company.seal_name 的列宽一致。 */
    private static final int MAX_SEAL_NAME_LENGTH = 50;

    /** 印章审核不通过原因上限，与 buyer_company.seal_failed_reason 的列宽一致。 */
    private static final int MAX_SEAL_FAILED_REASON_LENGTH = 128;

    private static final List<String> SEAL_IMAGE_EXTENSIONS = List.of("png", "jpg", "jpeg", "bmp", "gif");

    private final BuyerCompanyMapper mapper;
    private final FadadaOpenApiClient fadadaOpenApiClient;
    private final FileApplicationService fileApplicationService;

    public PageResult<BuyerCompanyListItemResponse> findPage(
            String keyword,
            Boolean isActive,
            boolean defaultOnly,
            int pageNum,
            int pageSize) {
        Integer active = isActive == null ? null : (isActive ? 1 : 0);
        String normalizedKeyword = blankToNull(keyword);
        long total = mapper.count(normalizedKeyword, active, defaultOnly);
        int offset = (pageNum - 1) * pageSize;

        return PageResult.of(
                pageNum,
                pageSize,
                total,
                mapper.findPage(normalizedKeyword, active, defaultOnly, offset, pageSize)
                        .stream()
                        .map(this::toListItem)
                        .toList());
    }

    public BuyerCompanyDetailResponse getById(Long id) {
        BuyerCompanyPO company = requireCompany(id);
        return toDetail(company);
    }

    /**
     * 为公司当前法大大印章和已配置场景码生成免验证签授权页。
     * 该方法不直接授予权限，企业超管仍需在法大大页面确认。
     */
    @FadadaAlertContext(FadadaAlertContext.Type.COMPANY)
    public FadadaSealFreeSignUrlResponse getSealFreeSignAuthorizationUrl(Long id, String clientUserId) {
        BuyerCompanyPO company = requireCompany(id);
        String openCorpId = requireText(company.getOpenCorpId(), "法大大 openCorpId 为空，请先完成企业授权");
        String sealId = requireText(company.getFadadaSealId(), "法大大印章 ID 为空，请先完成印章审核");
        String businessId = requireText(company.getFadadaFreeSignBusinessId(), "免验证签场景码为空，请先配置场景码");
        FadadaOpenApiClient.SealFreeSignUrl result = fadadaOpenApiClient.getSealFreeSignUrl(
                new FadadaOpenApiClient.SealFreeSignUrlRequest(openCorpId, sealId, businessId,
                        blankToNull(clientUserId), null));
        log.info("已生成法大大印章免验证签授权链接：companyId={}, openCorpIdPresent=true, sealIdPresent=true, businessIdPresent=true",
                id);
        return new FadadaSealFreeSignUrlResponse(result.freeSignUrl(), result.freeSignShortUrl());
    }

    @Transactional
    public BuyerCompanyDetailResponse create(BuyerCompanyUpsertRequest request) {
        BuyerCompanyPO company = toNewPO(request);
        validateUnique(company, null);

        try {
            if (company.getPriority() == 1) {
                mapper.clearDefaultExcept(-1L);
            }
            mapper.insert(company);
        } catch (DuplicateKeyException exception) {
            throw duplicateError(company);
        }

        return toDetail(requireCompany(company.getId()));
    }

    @Transactional
    public BuyerCompanyDetailResponse update(Long id, BuyerCompanyUpsertRequest request) {
        BuyerCompanyPO current = requireCompany(id);
        BuyerCompanyPO company = toUpdatedPO(current, request);
        validateUnique(company, id);
        validateDefaultTransition(current, company);

        try {
            if (company.getPriority() == 1) {
                mapper.clearDefaultExcept(id);
            }
            mapper.update(company);
        } catch (DuplicateKeyException exception) {
            throw duplicateError(company);
        }

        return toDetail(requireCompany(id));
    }

    @Transactional
    public BuyerCompanyDetailResponse updateSeal(Long id, BuyerCompanySealRequest request) {
        requireCompany(id);
        String sealUrl = blankToNull(request.sealUrl());
        if (sealUrl != null && sealUrl.length() > 512) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "签章图片地址长度不能超过 512");
        }

        // 移除签章时印章名称一并清空；新增 / 更换签章时印章名称必填
        String sealName = blankToNull(request.sealName());
        if (sealUrl == null) {
            sealName = null;
        } else if (sealName == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "印章名称不能为空");
        }
        if (sealName != null && sealName.length() > MAX_SEAL_NAME_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "印章名称长度不能超过 " + MAX_SEAL_NAME_LENGTH);
        }

        mapper.updateSeal(id, sealUrl, sealName);
        return toDetail(requireCompany(id));
    }

    /**
     * 上传印章图片：公司必须已通过法大大实名认证，先把图片落到对象存储并写入库内签章字段，
     * 再用法大大创建企业印章，最后把法大大返回的 {@code verifyId} 与本次建章指定的归属主体
     * {@code entityId} 回写到 {@code seal_verify_id} / {@code entity_id}。
     *
     * <p><b>顺序固定为「对象存储 → 落库 → 法大大 → 回写 verifyId」，不能颠倒。</b>法大大受理建章后会异步回调
     * （{@code seal-verify-successed}），回调按 {@code seal_verify_id} 定位公司并把 {@code seal_flow_status} 置为 1。
     * 若落库排在法大大之后，回调就可能插在两次写之间：回调先写入 1，随后这里的 0（审核中）把它覆盖，
     * 而法大大收到 {@code success} 应答后不再重推，状态会永久停在「审核中」。
     * 先落库则保证回调必然晚于本次写入，顺序天然正确。</p>
     *
     * <p><b>已知残余窗口</b>：{@code verifyId} 只有调完法大大才拿得到，因此回调若恰好落在
     * 「法大大建章成功」与「回写 verifyId」之间，会因定位不到记录而被丢弃（记 WARN）。印章审核本身是异步流程
     * （人工审核），实际远晚于这两步，窗口可忽略。</p>
     *
     * <p>因此法大大创建失败时必须补偿：删除刚上传的对象存储文件并清空库内签章字段，
     * 保持「法大大失败 = 无残留」的语义。印章图片的 Base64 只在本次调用中使用，不落库。</p>
     *
     * <p>建章这一步内部是两次法大大调用：先按 {@code openCorpId} 查 {@code /corp/entity/get-list}，
     * 用本行 {@code company_name} 匹配出主体 {@code entityId}，再带着它调 {@code /seal/create-by-image}；
     * 匹配不到就不传 {@code entityId}。主体查询失败会向上抛，走下面同一条补偿路径。
     * 匹配结果由建章调用原样带回（{@code SealCreation.entityId}），与 {@code verifyId} 一起落库 ——
     * 此时 {@code entity_id} 为空即表示这枚印章按 openCorpId 默认归属，签署阶段据此判断归属主体。</p>
     *
     * <p>该方法刻意<b>不加 {@code @Transactional}</b>：落库必须立即提交，
     * 回调线程才能读到「已持有签章且审核中」的那一行。</p>
     */
    @FadadaAlertContext(FadadaAlertContext.Type.COMPANY)
    public BuyerCompanySealUploadResponse uploadSeal(Long id, MultipartFile file, String sealName) {
        BuyerCompanyPO company = requireCompany(id);

        // 先判断公司是否已认证：未认证不允许创建印章
        if (company.getIdentStatus() == null || company.getIdentStatus() != 1) {
            throw new BusinessException(ResultCode.BUYER_COMPANY_SEAL_NOT_IDENTIFIED);
        }
        String openCorpId = blankToNull(company.getOpenCorpId());
        if (openCorpId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "法大大公司 ID 为空，请先完成公司认证");
        }

        String name = requireText(sealName, "印章名称不能为空");
        if (name.length() > MAX_SEAL_NAME_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "印章名称长度不能超过 " + MAX_SEAL_NAME_LENGTH);
        }
        byte[] content = readSealImage(file);

        // 1. 对象存储：先落图片，此时库内还没有任何改动，上传失败无需回滚
        FileUploadResponse uploaded = fileApplicationService.upload(file, SEAL_UPLOAD_FOLDER);

        // 2. 落库：写入 objectKey 与印章名称，并把审核状态初始化回「审核中」。
        //    必须先于第 3 步提交，回调到达时才能匹配到这一行（见方法注释的时序说明）
        mapper.updateSeal(id, uploaded.objectKey(), name);

        // 3. 法大大：用图片创建企业印章（sealImage 为 Base64 字符串）。
        //    传公司名进去，客户端会先查 /corp/entity/get-list 匹配主体 entityId，命中才带上该字段
        FadadaOpenApiClient.SealCreation creation;
        try {
            creation = fadadaOpenApiClient.createSealByImage(
                    openCorpId, company.getCompanyName(), name, Base64.getEncoder().encodeToString(content));
        } catch (RuntimeException exception) {
            compensateFailedSealUpload(id, uploaded.objectKey(), exception);
            throw exception;
        }

        // 4. 回写建章结果：核验 ID 是印章审核回调的定位键（回调报文只带 verifyId、没有本地主键，
        //    不落库就无法定位到这一行）；entityId 是本次建章实际指定的归属主体，签署阶段要用它
        //    判断印章归属哪个主体，两者同一条语句写入。此步失败不做落库补偿 —— 法大大侧印章已经创建，
        //    清空本地字段只会留下孤儿印章；异常向上抛，由调用方决定重试或人工处理。
        Long verifyId = creation.verifyId();
        mapper.updateSealCreateResult(id, verifyId, creation.entityId());

        return new BuyerCompanySealUploadResponse(verifyId, toDetail(requireCompany(id)));
    }

    /**
     * 上传印章在法大大侧失败后的补偿：清理对象存储文件与库内签章字段，
     * 避免留下「库里显示审核中、法大大侧却没有印章」的僵尸签章。
     *
     * <p>补偿本身失败只记 ERROR 并保留原始异常向上抛出，不能让清理异常掩盖真正的失败原因。</p>
     */
    private void compensateFailedSealUpload(Long id, String objectKey, RuntimeException cause) {
        log.warn("法大大创建印章失败，回滚本次上传的签章数据：companyId={}, objectKey={}", id, objectKey, cause);
        try {
            fileApplicationService.delete(objectKey);
            mapper.clearSeal(id);
        } catch (RuntimeException cleanupFailure) {
            log.error("回滚本次上传的签章数据失败，需人工清理：companyId={}, objectKey={}", id, objectKey, cleanupFailure);
        }
    }

    /**
     * 移除签章：先按 buyer_company.seal_flow_status 判断印章在法大大侧的状态，
     * 需要时清理法大大印章，再删除对象存储里的图片，最后清空库内签章字段。
     *
     * <ol>
     *   <li>审核中（0）：印章还在法大大审核流程中，不允许删除，直接返回业务错误；</li>
     *   <li>审核成功（1）：先调法大大 {@code /seal/set-status} 把印章置为 disable，
     *       再调 {@code /seal/delete} 删除；印章 ID 为空（历史数据）时跳过这两步；</li>
     *   <li>审核失败（2）或未记录状态：法大大侧没有有效印章，跳过法大大直接清理。</li>
     * </ol>
     *
     * <p>清理顺序固定为「法大大 → 对象存储 → 清库」：法大大删除失败时不会把本地签章清掉，
     * 保证失败后可重试。只有法大大侧删除成功后才会删除对象存储文件并清空
     * seal_name / seal_url / seal_base64 / fadada_seal_id / seal_flow_status。</p>
     */
    @FadadaAlertContext(FadadaAlertContext.Type.COMPANY)
    public BuyerCompanyDetailResponse removeSeal(Long id) {
        BuyerCompanyPO company = requireCompany(id);
        Integer flowStatus = company.getSealFlowStatus();

        // 1. 审核中的印章不能删：法大大侧还在审核流程里
        if (flowStatus != null && flowStatus == SEAL_FLOW_AUDITING) {
            throw new BusinessException(ResultCode.BUYER_COMPANY_SEAL_AUDITING);
        }

        // 2. 审核成功且已拿到法大大印章 ID：先停用再删除
        if (flowStatus != null && flowStatus == SEAL_FLOW_APPROVED) {
            String sealId = blankToNull(company.getFadadaSealId());
            if (sealId != null) {
                String openCorpId = blankToNull(company.getOpenCorpId());
                if (openCorpId == null) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "法大大公司 ID 为空，无法删除法大大印章");
                }
                fadadaOpenApiClient.setSealStatus(openCorpId, sealId, FadadaOpenApiClient.SEAL_STATUS_DISABLE);
                fadadaOpenApiClient.deleteSeal(openCorpId, sealId);
            }
        }

        // 3. 法大大侧处理完毕（或无需处理）后，删除对象存储文件并清空库内签章字段
        fileApplicationService.delete(company.getSealUrl());
        mapper.clearSeal(id);
        return toDetail(requireCompany(id));
    }

    /**
     * 法大大印章审核通过回调（{@code X-FASC-Event = seal-verify-successed}）的落库入口。
     *
     * <p>按 {@code seal_verify_id}（上传印章时由 {@link #uploadSeal} 写入的法大大 verifyId）定位公司，
     * 写入 {@code fadada_seal_id}（法大大印章 ID）并把 {@code seal_flow_status} 置为
     * {@value #SEAL_FLOW_APPROVED}（审核成功），同时清空 {@code seal_failed_reason}。
     * 不再按 {@code open_corpid} 定位 —— 该列允许重复，用它定位会一次命中多行、把审核结果广播到其它公司。</p>
     *
     * <p>回调可能重复投递，更新语句本身幂等；未匹配到记录（印章已被移除、或公司已逻辑删除）时只记日志，
     * 不抛异常 —— 该场景重试也无法修复，且回调方期望 success 应答以停止重推。</p>
     *
     * @param verifyId 法大大受理创章时返回的核验 ID（19 位长整型），对应 {@code buyer_company.seal_verify_id}
     * @param sealId   法大大印章 ID，写入 {@code buyer_company.fadada_seal_id}
     */
    @Transactional
    public void handleSealVerifySuccess(Long verifyId, String sealId) throws InterruptedException {
        Long verify = requireVerifyId(verifyId);
        String fadadaSealId = requireText(sealId, "sealId 不能为空");
        Thread.sleep(3000);
        int updated = mapper.updateSealVerified(verify, fadadaSealId);
        if (updated == 0) {
            log.warn("法大大印章审核通过回调未匹配到待更新记录（印章可能已移除、公司已删除或 verifyId 未被记录）：verifyId={}, sealId={}",
                    verify, fadadaSealId);
            return;
        }
        log.info("法大大印章审核通过，已更新公司印章：verifyId={}, sealId={}, 更新行数={}",
                verify, fadadaSealId, updated);
    }

    /**
     * 法大大印章免验证签授权回调（{@code X-FASC-Event = seal-authorize-free-sign}）的落库入口。
     *
     * <p>按 {@code fadada_seal_id}（法大大印章 ID）定位公司 —— 免验证签在法大大侧是「印章 + 场景码」维度的授权，
     * 因此按印章定位；该事件发生在印章审核通过之后，此时 {@code fadada_seal_id} 已由审核通过回调写入。
     * 把回调携带的场景码 {@code businessId} 与授权到期时间 {@code expiresTime} 成对写入
     * {@code fadada_free_sign_business_id} 与 {@code fadada_free_sign_expire_time}。
     * 这两个字段会在发起签署前被读取用于判断授权是否可用（见 {@code ContractSignAppService}），
     * 因此以本回调为权威来源整体覆盖，不做「有值才写」—— 否则重新授权时无法把旧的到期时间刷新。
     * 更新语句幂等，法大大重复回调结果一致；未匹配到记录（印章已被移除、或公司已逻辑删除）时
     * 只记日志、不抛异常 —— 该场景重试无法修复，且回调方期望 success 应答以停止重推。</p>
     *
     * @param sealId      法大大印章 ID，对应 {@code buyer_company.fadada_seal_id}
     * @param businessId  免验证签场景码，写入 {@code buyer_company.fadada_free_sign_business_id}
     * @param expiresTime 授权到期时间（法大大下发的毫秒级时间戳字符串），可为空表示不限期
     */
    @Transactional
    public void handleSealAuthorizeFreeSign(String sealId, String businessId, String expiresTime) {
        String fadadaSealId = requireText(sealId, "sealId 不能为空");
        String sceneCode = requireText(businessId, "businessId 不能为空");
        LocalDateTime expireAt = toFreeSignExpireTime(expiresTime);

        int updated = mapper.updateFreeSignAuthorization(fadadaSealId, sceneCode, expireAt);
        if (updated == 0) {
            log.warn("法大大免验证签授权回调未匹配到待更新记录（印章已被移除、公司已逻辑删除、或印章尚未落库）：sealId={}",
                    fadadaSealId);
            return;
        }
        log.info("法大大免验证签授权已更新公司配置：sealId={}, businessId={}, 到期时间={}, 更新行数={}",
                fadadaSealId, sceneCode, expireAt, updated);
    }

    /**
     * 毫秒级时间戳字符串转本地时间。
     *
     * <p>空白 / {@code 0} / 非正数都表示「不限期」，返回 null —— 与签署侧的过期校验口径一致
     * （{@code fadada_free_sign_expire_time} 为空时不做过期判断）。无法解析为数字时只记 WARN 并按不限期处理，
     * 避免一个格式异常的字段让整笔回调白跑。</p>
     */
    private static LocalDateTime toFreeSignExpireTime(String expiresTime) {
        String value = blankToNull(expiresTime);
        if (value == null) {
            return null;
        }
        long millis;
        try {
            millis = Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            log.warn("法大大免验证签授权回调 expiresTime 不是合法毫秒时间戳，到期时间按不限期处理：expiresTime={}", value);
            return null;
        }
        return millis > 0 ? LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()) : null;
    }

    /**
     * 法大大印章审核不通过回调（{@code X-FASC-Event = seal-verify-failed}）的落库入口。
     *
     * <p>按 {@code seal_verify_id}（上传印章时由 {@link #uploadSeal} 写入的法大大 verifyId，与审核通过回调同一套定位键）
     * 定位公司，把 {@code seal_flow_status} 置为 {@value #SEAL_FLOW_REJECTED}（审核失败），
     * 并把回调携带的 {@code reason} 写入 {@code seal_failed_reason}；详情页只在失败原因非空时展示。
     * 原因可以为空，此时仅更新审核状态。原因超过列宽时按 {@value #MAX_SEAL_FAILED_REASON_LENGTH} 个字符截断 ——
     * 超长值直接入库会被 MySQL 严格模式拒绝，导致整笔回调连状态一起丢失。
     * 未匹配到记录时只记日志、不抛异常，与审核通过回调一致：重试无法修复，且回调方期望 success 应答。</p>
     *
     * @param verifyId 法大大受理创章时返回的核验 ID（19 位长整型），对应 {@code buyer_company.seal_verify_id}
     * @param reason   审核不通过原因，可为空
     */
    @Transactional
    public void handleSealVerifyFailed(Long verifyId, String reason) throws InterruptedException {
        Long verify = requireVerifyId(verifyId);
        String failedReason = truncateFailedReason(reason);
        Thread.sleep(3000);
        int updated = mapper.updateSealVerifyFailed(verify, failedReason);
        if (updated == 0) {
            log.warn("法大大印章审核不通过回调未匹配到待更新记录（印章已被移除、公司已逻辑删除、或 verifyId 未被记录）：verifyId={}",
                    verify);
            return;
        }
        log.info("法大大印章审核不通过，已更新公司印章：verifyId={}, 更新行数={}, 是否携带原因={}",
                verify, updated, failedReason != null);
    }

    /** 审核不通过原因：空白转 null；超长按列宽截断，避免整笔回调因列长度报错而白跑。 */
    private static String truncateFailedReason(String reason) {
        String value = blankToNull(reason);
        if (value == null || value.length() <= MAX_SEAL_FAILED_REASON_LENGTH) {
            return value;
        }
        log.warn("法大大印章审核不通过原因超过 {} 个字符，已截断入库", MAX_SEAL_FAILED_REASON_LENGTH);
        return value.substring(0, MAX_SEAL_FAILED_REASON_LENGTH);
    }

    /** 读取印章图片字节，并校验类型与大小，避免把非图片或超大内容提交给法大大。 */
    private static byte[] readSealImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "印章图片不能为空");
        }
        String contentType = file.getContentType();
        boolean imageContentType = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
        if (!imageContentType && !hasSealImageExtension(file.getOriginalFilename())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "印章图片仅支持 png / jpg / bmp / gif 格式");
        }
        if (file.getSize() > MAX_SEAL_BYTES) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "印章图片不能超过 2MB");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "读取印章图片失败");
        }
    }

    private static boolean hasSealImageExtension(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return SEAL_IMAGE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private BuyerCompanyPO toNewPO(BuyerCompanyUpsertRequest request) {
        BuyerCompanyPO company = new BuyerCompanyPO();
        apply(company, request);
        return company;
    }

    private BuyerCompanyPO toUpdatedPO(BuyerCompanyPO current, BuyerCompanyUpsertRequest request) {
        BuyerCompanyPO company = new BuyerCompanyPO();
        company.setId(current.getId());
        apply(company, mergeForUpdate(current, request));
        return company;
    }

    /**
     * 编辑接口是差量提交语义：请求里为 {@code null} 的字段表示「本次未提交该字段」，
     * 沿用库中现值；空串仍然表示清空（由 {@link #apply} / {@code blankToNull} 处理）。
     *
     * <p>这样前端只需提交变化字段，编辑时也不会因为漏传某个字段而把库里的值清掉。</p>
     */
    private static BuyerCompanyUpsertRequest mergeForUpdate(BuyerCompanyPO current, BuyerCompanyUpsertRequest request) {
        return new BuyerCompanyUpsertRequest(
                request.companyName() == null ? current.getCompanyName() : request.companyName(),
                request.companyShortName() == null ? current.getCompanyShortName() : request.companyShortName(),
                request.creditCode() == null ? current.getCreditCode() : request.creditCode(),
                request.postCode() == null ? current.getPostCode() : request.postCode(),
                request.fax() == null ? current.getFax() : request.fax(),
                request.legalPerson() == null ? current.getLegalPerson() : request.legalPerson(),
                request.address() == null ? current.getAddress() : request.address(),
                request.phone() == null ? current.getPhone() : request.phone(),
                request.bankName() == null ? current.getBankName() : request.bankName(),
                request.bankAccount() == null ? current.getBankAccount() : request.bankAccount(),
                request.sealUrl() == null ? current.getSealUrl() : request.sealUrl(),
                request.sealName() == null ? current.getSealName() : request.sealName(),
                request.sealBase64() == null ? current.getSealBase64() : request.sealBase64(),
                request.fadadaSealId() == null ? current.getFadadaSealId() : request.fadadaSealId(),
                request.openCorpId() == null ? current.getOpenCorpId() : request.openCorpId(),
                request.identStatus() == null ? identStatusText(current.getIdentStatus()) : request.identStatus(),
                request.priority() == null ? current.getPriority() : request.priority(),
                request.isDefault() == null ? Boolean.valueOf(isDefaultCompany(current)) : request.isDefault(),
                request.isActive() == null ? Boolean.valueOf(isActiveCompany(current)) : request.isActive());
    }

    /** 库中的 ident_status 数值回推成法大大原值，供差量合并时复用（未点「检测」时前端提交该原值）。 */
    private static String identStatusText(Integer identStatus) {
        return identStatus != null && identStatus == 1 ? "identified" : "unidentified";
    }

    private static boolean isDefaultCompany(BuyerCompanyPO company) {
        return company.getPriority() != null && company.getPriority() == 1;
    }

    private static boolean isActiveCompany(BuyerCompanyPO company) {
        return company.getIsActive() != null && company.getIsActive() == 1;
    }

    private void apply(BuyerCompanyPO company, BuyerCompanyUpsertRequest request) {
        String companyName = requireText(request.companyName(), "公司名称不能为空");
        String address = requireText(request.address(), "公司地址不能为空");
        boolean active = request.isActive() == null || request.isActive();
        boolean isDefault = Boolean.TRUE.equals(request.isDefault());
        int requestedPriority = request.priority() == null ? 0 : request.priority();

        if (requestedPriority < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "priority 不能小于 0");
        }
        if (!isDefault && requestedPriority == 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "必有一个默认需方");
        }
        if (isDefault && !active) {
            throw new BusinessException(ResultCode.BUYER_COMPANY_DEFAULT_REQUIRED, "默认合同需方必须处于启用状态");
        }

        String sealBase64 = blankToNull(request.sealBase64());
        validateSealBase64(sealBase64);

        company.setCompanyName(companyName);
        company.setCompanyShortName(blankToNull(request.companyShortName()));
        company.setCreditCode(blankToNull(request.creditCode()));
        company.setPostCode(blankToNull(request.postCode()));
        company.setFax(blankToNull(request.fax()));
        company.setLegalPerson(blankToNull(request.legalPerson()));
        company.setAddress(address);
        company.setPhone(blankToNull(request.phone()));
        company.setBankName(blankToNull(request.bankName()));
        company.setBankAccount(blankToNull(request.bankAccount()));
        company.setSealName(blankToNull(request.sealName()));
        company.setSealUrl(blankToNull(request.sealUrl()));
        company.setSealBase64(sealBase64);
        company.setFadadaSealId(blankToNull(request.fadadaSealId()));
        company.setOpenCorpId(blankToNull(request.openCorpId()));
        // 实名认证状态由法大大 identStatus 判定：非空且等于 identified 记 1（已认证），否则记 0（未认证）
        company.setIdentStatus("identified".equals(blankToNull(request.identStatus())) ? 1 : 0);
        company.setPriority(isDefault ? 1 : requestedPriority);
        company.setIsActive(active ? 1 : 0);
    }

    private void validateDefaultTransition(BuyerCompanyPO current, BuyerCompanyPO next) {
        boolean currentDefault = current.getPriority() != null && current.getPriority() == 1;
        boolean nextDefault = next.getPriority() != null && next.getPriority() == 1;
        boolean hasOtherDefault = mapper.existsDefaultExcept(current.getId());

        if (currentDefault && !nextDefault && !hasOtherDefault) {
            throw new BusinessException(ResultCode.BUYER_COMPANY_DEFAULT_REQUIRED);
        }
    }

    private void validateUnique(BuyerCompanyPO company, Long excludeId) {
        if (company.getCreditCode() != null
                && mapper.existsByCreditCode(company.getCreditCode(), excludeId)) {
            throw new BusinessException(ResultCode.BUYER_COMPANY_CREDIT_CODE_DUPLICATE);
        }
    }

    private BusinessException duplicateError(BuyerCompanyPO company) {
        if (company.getCreditCode() != null) {
            return new BusinessException(ResultCode.BUYER_COMPANY_CREDIT_CODE_DUPLICATE);
        }
        if (company.getOpenCorpId() != null) {
            return new BusinessException(ResultCode.BUYER_COMPANY_OPEN_CORPID_DUPLICATE);
        }
        return new BusinessException(ResultCode.DATABASE_DUPLICATE_KEY);
    }

    private BuyerCompanyPO requireCompany(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "公司 ID 必须为正整数");
        }
        return mapper.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.BUYER_COMPANY_NOT_FOUND));
    }

    private BuyerCompanyDetailResponse toDetail(BuyerCompanyPO po) {
        boolean isDefault = po.getPriority() != null && po.getPriority() == 1;
        boolean isActive = po.getIsActive() != null && po.getIsActive() == 1;

        return new BuyerCompanyDetailResponse(
                String.valueOf(po.getId()),
                po.getCompanyName(),
                po.getCompanyShortName(),
                po.getCreditCode(),
                po.getPostCode(),
                po.getFax(),
                po.getLegalPerson(),
                po.getAddress(),
                po.getPhone(),
                po.getBankName(),
                po.getBankAccount(),
                maskBankAccount(po.getBankAccount()),
                po.getSealName(),
                po.getSealUrl(),
                po.getSealBase64(),
                po.getFadadaSealId(),
                po.getSealFlowStatus(),
                po.getSealFailedReason(),
                po.getOpenCorpId(),
                po.getFadadaFreeSignBusinessId(),
                po.getIdentStatus(),
                po.getPriority(),
                isDefault,
                isActive,
                po.getCreateTime(),
                po.getUpdateTime());
    }

    private BuyerCompanyListItemResponse toListItem(BuyerCompanyPO po) {
        boolean isDefault = po.getPriority() != null && po.getPriority() == 1;
        boolean isActive = po.getIsActive() != null && po.getIsActive() == 1;

        return new BuyerCompanyListItemResponse(
                String.valueOf(po.getId()),
                po.getCompanyName(),
                po.getCompanyShortName(),
                po.getCreditCode(),
                po.getPostCode(),
                po.getFax(),
                po.getAddress(),
                po.getPhone(),
                po.getSealName(),
                po.getSealUrl(),
                po.getOpenCorpId(),
                po.getIdentStatus(),
                po.getPriority(),
                isDefault,
                isActive,
                po.getUpdateTime());
    }

    private static void validateSealBase64(String value) {
        if (value == null) {
            return;
        }

        String raw = value.replaceFirst("^data:image/[a-zA-Z0-9.+-]+;base64,", "");
        try {
            byte[] content = Base64.getDecoder().decode(raw);
            if (content.length > MAX_SEAL_BYTES) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "印章图片不能超过 2MB");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "印章图片 Base64 格式错误");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, message);
        }
        return value.trim();
    }

    /** 回调定位键校验：verifyId 是法大大侧必填的长整型，缺失即报文非法。 */
    private static Long requireVerifyId(Long verifyId) {
        if (verifyId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "verifyId 不能为空");
        }
        return verifyId;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String maskBankAccount(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        if (account.length() <= 4) {
            return "****";
        }
        return "****" + account.substring(account.length() - 4);
    }
}
