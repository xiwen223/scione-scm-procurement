package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.BuyerCompanyDetailResponse;
import com.scione.scm.bill.application.dto.BuyerCompanyListItemResponse;
import com.scione.scm.bill.application.dto.BuyerCompanyUpsertRequest;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.BuyerCompanyMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.BuyerCompanyPO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;

@Service
@RequiredArgsConstructor
public class BuyerCompanyApplicationService {

    private static final int MAX_SEAL_BYTES = 2 * 1024 * 1024;

    private final BuyerCompanyMapper mapper;

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
        company.setLegalPerson(blankToNull(request.legalPerson()));
        company.setAddress(address);
        company.setPhone(blankToNull(request.phone()));
        company.setBankName(blankToNull(request.bankName()));
        company.setBankAccount(blankToNull(request.bankAccount()));
        company.setSealUrl(blankToNull(request.sealUrl()));
        company.setSealBase64(sealBase64);
        company.setFadadaSealId(blankToNull(request.fadadaSealId()));
        company.setOpenCorpId(blankToNull(request.openCorpId()));
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
                po.getLegalPerson(),
                po.getAddress(),
                po.getPhone(),
                po.getBankName(),
                po.getBankAccount(),
                maskBankAccount(po.getBankAccount()),
                po.getSealUrl(),
                po.getSealBase64(),
                po.getFadadaSealId(),
                po.getOpenCorpId(),
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
                po.getAddress(),
                po.getPhone(),
                po.getSealUrl(),
                po.getOpenCorpId(),
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
