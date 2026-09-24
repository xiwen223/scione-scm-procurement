package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.BuyerCompanyDetailResponse;
import com.scione.scm.bill.application.dto.BuyerCompanyListItemResponse;
import com.scione.scm.bill.application.dto.BuyerCompanySealRequest;
import com.scione.scm.bill.application.dto.BuyerCompanySealUploadResponse;
import com.scione.scm.bill.application.dto.BuyerCompanyUpsertRequest;
import com.scione.scm.bill.application.dto.FileUploadResponse;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.infrastructure.fadada.FadadaOpenApiClient;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.BuyerCompanyMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.BuyerCompanyPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
     * 再用法大大创建企业印章。
     *
     * <p><b>顺序固定为「对象存储 → 落库 → 法大大」，不能颠倒。</b>法大大受理建章后会立刻异步回调
     * （{@code seal-verify-successed}），回调按 {@code open_corpid} 把 {@code seal_flow_status} 置为 1。
     * 若落库排在法大大之后，回调就可能插在两次写之间：回调先写入 1，随后这里的 0（审核中）把它覆盖，
     * 而法大大收到 {@code success} 应答后不再重推，状态会永久停在「审核中」。
     * 先落库则保证回调必然晚于本次写入，顺序天然正确。</p>
     *
     * <p>因此法大大创建失败时必须补偿：删除刚上传的对象存储文件并清空库内签章字段，
     * 保持「法大大失败 = 无残留」的语义。印章图片的 Base64 只在本次调用中使用，不落库。</p>
     *
     * <p>该方法刻意<b>不加 {@code @Transactional}</b>：落库必须立即提交，
     * 回调线程才能读到「已持有签章且审核中」的那一行。</p>
     */
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

        // 3. 法大大：用图片创建企业印章（sealImage 为 Base64 字符串）
        String verifyId;
        try {
            verifyId = fadadaOpenApiClient.createSealByImage(
                    openCorpId, name, Base64.getEncoder().encodeToString(content));
        } catch (RuntimeException exception) {
            compensateFailedSealUpload(id, uploaded.objectKey(), exception);
            throw exception;
        }

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
     * <p>按 {@code open_corpid} 找到对应公司，写入 {@code fadada_seal_id}（法大大印章 ID）并把
     * {@code seal_flow_status} 置为 {@value #SEAL_FLOW_APPROVED}（审核成功），同时清空 {@code seal_failed_reason}。
     * 回调可能重复投递，更新语句本身幂等；未匹配到记录（印章已被移除、或公司已逻辑删除）时只记日志，
     * 不抛异常 —— 该场景重试也无法修复，且回调方期望 success 应答以停止重推。</p>
     *
     * @param openCorpId 法大大唯一公司 ID，对应 {@code buyer_company.open_corpid}
     * @param sealId     法大大印章 ID，写入 {@code buyer_company.fadada_seal_id}
     */
    @Transactional
    public void handleSealVerifySuccess(String openCorpId, String sealId) throws InterruptedException {
        String corpId = requireText(openCorpId, "openCorpId 不能为空");
        String fadadaSealId = requireText(sealId, "sealId 不能为空");
        Thread.sleep(3000);
        int updated = mapper.updateSealVerified(corpId, fadadaSealId);
        if (updated == 0) {
            log.warn("法大大印章审核通过回调未匹配到待更新记录（印章可能已移除或公司已删除）：openCorpId={}, sealId={}",
                    corpId, fadadaSealId);
            return;
        }
        log.info("法大大印章审核通过，已更新公司印章：openCorpId={}, sealId={}, 更新行数={}",
                corpId, fadadaSealId, updated);
    }

    /**
     * 法大大印章审核不通过回调（{@code X-FASC-Event = seal-verify-failed}）的落库入口。
     *
     * <p>按 {@code open_corpid} 找到对应公司，把 {@code seal_flow_status} 置为
     * {@value #SEAL_FLOW_REJECTED}（审核失败），并把回调携带的 {@code reason} 写入
     * {@code seal_failed_reason}；详情页只在失败原因非空时展示。原因可以为空，此时仅更新审核状态。
     * 原因超过列宽时按 {@value #MAX_SEAL_FAILED_REASON_LENGTH} 个字符截断 ——
     * 超长值直接入库会被 MySQL 严格模式拒绝，导致整笔回调连状态一起丢失。
     * 未匹配到记录时只记日志、不抛异常，与审核通过回调一致：重试无法修复，且回调方期望 success 应答。</p>
     *
     * @param openCorpId 法大大唯一公司 ID，对应 {@code buyer_company.open_corpid}
     * @param reason     审核不通过原因，可为空
     */
    @Transactional
    public void handleSealVerifyFailed(String openCorpId, String reason) throws InterruptedException {
        String corpId = requireText(openCorpId, "openCorpId 不能为空");
        String failedReason = truncateFailedReason(reason);
        Thread.sleep(3000);
        int updated = mapper.updateSealVerifyFailed(corpId, failedReason);
        if (updated == 0) {
            log.warn("法大大印章审核不通过回调未匹配到待更新记录（openCorpId 无对应公司、或公司已逻辑删除）：openCorpId={}",
                    corpId);
            return;
        }
        log.info("法大大印章审核不通过，已更新公司印章：openCorpId={}, 更新行数={}, 是否携带原因={}",
                corpId, updated, failedReason != null);
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

    /**
     * 删除公司：只做逻辑删除（buyer_company.is_deleted 置 1），数据仍保留在库中，
     * 之后所有查询都不再返回该记录；默认合同需方不允许删除。
     */
    @Transactional
    public void delete(Long id) {
        BuyerCompanyPO company = requireCompany(id);
        if (company.getPriority() != null && company.getPriority() == 1) {
            throw new BusinessException(ResultCode.BUYER_COMPANY_DEFAULT_CANNOT_DELETE);
        }
        mapper.deleteById(id);
    }

    private BuyerCompanyPO toNewPO(BuyerCompanyUpsertRequest request) {
        BuyerCompanyPO company = new BuyerCompanyPO();
        apply(company, request);
        return company;
    }

    private BuyerCompanyPO toUpdatedPO(BuyerCompanyPO current, BuyerCompanyUpsertRequest request) {
        BuyerCompanyPO company = new BuyerCompanyPO();
        company.setId(current.getId());
        apply(company, request);
        return company;
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
            throw new BusinessException(ResultCode.PARAM_ERROR, "priority=1 时必须设为默认公司");
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

        if (company.getOpenCorpId() != null
                && mapper.existsByOpenCorpId(company.getOpenCorpId(), excludeId)) {
            throw new BusinessException(ResultCode.BUYER_COMPANY_OPEN_CORPID_DUPLICATE);
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
