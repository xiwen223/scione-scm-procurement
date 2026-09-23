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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BuyerCompanyApplicationService {

    private static final int MAX_SEAL_BYTES = 2 * 1024 * 1024;

    /** 印章图片在对象存储中的目录，与前端上传约定一致。 */
    private static final String SEAL_UPLOAD_FOLDER = "company-seal";

    /** 印章名称上限，与 buyer_company.seal_name 的列宽一致。 */
    private static final int MAX_SEAL_NAME_LENGTH = 50;

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
     * 上传印章图片：公司必须已通过法大大实名认证，先用图片在法大大创建企业印章，
     * 创建受理成功后再把图片落到对象存储，最后把 objectKey 与印章名称写回 buyer_company。
     *
     * <p>顺序固定为「法大大 → 对象存储 → 落库」：法大大创建失败时不会产生对象存储残留和库内改动。
     * 印章图片的 Base64 只在本次调用中使用，不落库（seal_base64 一并清空）。</p>
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

        // 1. 法大大：用图片创建企业印章（sealImage 为 Base64 字符串）
        String verifyId = fadadaOpenApiClient.createSealByImage(
                openCorpId, name, Base64.getEncoder().encodeToString(content));

        // 2. 对象存储：法大大受理成功后才上传，避免失败时留下孤儿文件
        FileUploadResponse uploaded = fileApplicationService.upload(file, SEAL_UPLOAD_FOLDER);

        // 3. 落库：只存 objectKey 与印章名称，Base64 不保存
        mapper.updateSeal(id, uploaded.objectKey(), name);
        return new BuyerCompanySealUploadResponse(verifyId, toDetail(requireCompany(id)));
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
