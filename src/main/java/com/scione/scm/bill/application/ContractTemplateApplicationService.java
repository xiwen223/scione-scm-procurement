package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.ContractTemplateDetailResponse;
import com.scione.scm.bill.application.dto.ContractTemplateListItemResponse;
import com.scione.scm.bill.application.dto.ContractTemplateUpsertRequest;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.contract.enums.ContractType;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ContractTemplateMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractTemplatePO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContractTemplateApplicationService {

    /** 新增场景下尚未生成主键，用不可能出现的 ID 清空同类型默认标记。 */
    private static final long UNSAVED_ID = -1L;

    private final ContractTemplateMapper mapper;

    public PageResult<ContractTemplateListItemResponse> findPage(
            String keyword,
            Integer contractType,
            Boolean isActive,
            boolean defaultOnly,
            int pageNum,
            int pageSize) {
        Integer active = isActive == null ? null : (isActive ? 1 : 0);
        String normalizedKeyword = blankToNull(keyword);
        long total = mapper.count(normalizedKeyword, contractType, active, defaultOnly);
        int offset = (pageNum - 1) * pageSize;

        return PageResult.of(
                pageNum,
                pageSize,
                total,
                mapper.findPage(normalizedKeyword, contractType, active, defaultOnly, offset, pageSize)
                        .stream()
                        .map(this::toListItem)
                        .toList());
    }

    public ContractTemplateDetailResponse getById(Long id) {
        ContractTemplatePO template = requireTemplate(id);
        return toDetail(template);
    }

    @Transactional
    public ContractTemplateDetailResponse create(ContractTemplateUpsertRequest request) {
        ContractTemplatePO template = toNewPO(request);

        if (isDefault(template)) {
            mapper.clearDefaultExcept(template.getContractType(), UNSAVED_ID);
        }
        mapper.insert(template);

        return toDetail(requireTemplate(template.getId()));
    }

    @Transactional
    public ContractTemplateDetailResponse update(Long id, ContractTemplateUpsertRequest request) {
        ContractTemplatePO current = requireTemplate(id);
        ContractTemplatePO template = toUpdatedPO(current, request);
        validateDefaultTransition(current, template);

        if (isDefault(template)) {
            mapper.clearDefaultExcept(template.getContractType(), id);
        }
        mapper.update(template);

        return toDetail(requireTemplate(id));
    }

    @Transactional
    public void delete(Long id) {
        ContractTemplatePO template = requireTemplate(id);
        if (isDefault(template)) {
            throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_DEFAULT_CANNOT_DELETE);
        }
        // 合同主表落地后在此补充 contract.template_id 引用校验，被引用时抛 CONTRACT_TEMPLATE_IN_USE。
        mapper.deleteById(id);
    }

    private ContractTemplatePO toNewPO(ContractTemplateUpsertRequest request) {
        ContractTemplatePO template = new ContractTemplatePO();
        apply(template, request);
        return template;
    }

    private ContractTemplatePO toUpdatedPO(ContractTemplatePO current, ContractTemplateUpsertRequest request) {
        ContractTemplatePO template = new ContractTemplatePO();
        template.setId(current.getId());
        apply(template, request);
        return template;
    }

    private void apply(ContractTemplatePO template, ContractTemplateUpsertRequest request) {
        String templateName = requireText(request.templateName(), "模板名称不能为空");
        if (!ContractType.supports(request.contractType())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "合同类型不合法");
        }

        boolean active = request.isActive() == null || request.isActive();
        boolean isDefault = Boolean.TRUE.equals(request.isDefault());
        if (isDefault && !active) {
            throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_DEFAULT_REQUIRED, "默认合同模板必须处于启用状态");
        }

        template.setTemplateName(templateName);
        template.setContractType(request.contractType());
        template.setObjectKey(blankToNull(request.objectKey()));
        template.setIsDefault(isDefault ? 1 : 0);
        template.setIsActive(active ? 1 : 0);
    }

    /**
     * 每种合同类型必须保留一个启用中的默认模板：原记录是默认模板，
     * 但修改后不再担任原合同类型的默认模板且该类型下已无其他默认模板时拒绝。
     */
    private void validateDefaultTransition(ContractTemplatePO current, ContractTemplatePO next) {
        if (!isDefault(current)) {
            return;
        }

        boolean keepsTypeDefault = isDefault(next)
                && Objects.equals(current.getContractType(), next.getContractType());
        if (keepsTypeDefault) {
            return;
        }

        if (!mapper.existsDefaultExcept(current.getContractType(), current.getId())) {
            throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_DEFAULT_REQUIRED);
        }
    }

    private ContractTemplatePO requireTemplate(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "合同模板 ID 必须为正整数");
        }
        return mapper.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.CONTRACT_TEMPLATE_NOT_FOUND));
    }

    private ContractTemplateDetailResponse toDetail(ContractTemplatePO po) {
        return new ContractTemplateDetailResponse(
                String.valueOf(po.getId()),
                po.getTemplateName(),
                po.getContractType(),
                ContractType.descOf(po.getContractType()),
                po.getObjectKey(),
                isDefault(po),
                isActive(po),
                po.getCreateTime(),
                po.getUpdateTime());
    }

    private ContractTemplateListItemResponse toListItem(ContractTemplatePO po) {
        return new ContractTemplateListItemResponse(
                String.valueOf(po.getId()),
                po.getTemplateName(),
                po.getContractType(),
                ContractType.descOf(po.getContractType()),
                po.getObjectKey(),
                isDefault(po),
                isActive(po),
                po.getUpdateTime());
    }

    private static boolean isDefault(ContractTemplatePO po) {
        return po.getIsDefault() != null && po.getIsDefault() == 1;
    }

    private static boolean isActive(ContractTemplatePO po) {
        return po.getIsActive() != null && po.getIsActive() == 1;
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
}
