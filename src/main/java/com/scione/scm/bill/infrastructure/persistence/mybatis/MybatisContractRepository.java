package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ContractItemMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ContractMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ProcurementOperationLogMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractItemPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ProcurementOperationLogPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.scione.scm.bill.domain.contract.ContractPage;

import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis 的合同仓储适配器。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MybatisContractRepository implements ContractRepository {

    private final ContractMapper contractMapper;
    private final ContractItemMapper itemMapper;
    private final ProcurementOperationLogMapper logMapper;

    @Override
    @Transactional
    public long create(Contract contract) {
        // 1. 插入合同主表
        ContractPO po = toPO(contract);
        contractMapper.insert(po);
        Long contractId = po.getId();
        contract.setId(contractId);

        // 2. 批量插入明细（检查是否已存在，避免重复插入）
        List<ContractItem> items = contract.getItems();
        if (items != null && !items.isEmpty()) {
            // 检查该合同是否已有明细
            List<ContractItemPO> existingItems = itemMapper.selectByContractId(contractId);

            if (existingItems.isEmpty()) {
                // 没有明细，执行插入
                log.info("插入合同明细：contractId={}, itemCount={}", contractId, items.size());
                List<ContractItemPO> itemPOs = items.stream()
                        .map(item -> toItemPO(contractId, item))
                        .toList();
                itemMapper.batchInsert(itemPOs);
            } else {
                // 已有明细，跳过插入
                log.warn("合同明细已存在，跳过插入：contractId={}, existingCount={}",
                        contractId, existingItems.size());
            }
        }

        // 3. 插入 CREATE 操作日志
        ContractOperationLog log = ContractOperationLog.ofCreate(
                contractId,
                contract.getContractNo(),
                contract.getCreatorId(),
                contract.getCreatorName(),
                contract.getCreateType() != null && contract.getCreateType() == Contract.CREATE_TYPE_MANUAL
                        ? "手动创建采购合同" : "自动创建采购合同",
                buildCreateDetails(contract)
        );
        logMapper.insert(toLogPO(log));

        return contractId;
    }

    @Override
    public boolean existsActiveByPurchaseOrderNo(String purchaseOrderNo) {
        return contractMapper.existsActiveByPurchaseOrderNo(purchaseOrderNo) > 0;
    }

    @Override
    public Optional<Contract> findByContractNo(String contractNo) {
        ContractPO po = contractMapper.findByContractNo(contractNo);
        if (po == null) {
            return Optional.empty();
        }
        List<ContractItemPO> itemPOs = itemMapper.selectByContractId(po.getId());
        List<ContractItem> items = itemPOs.stream().map(this::toItemDomain).toList();
        return Optional.of(Contract.rehydrate(
                po.getId(), po.getContractNo(), po.getContractName(), po.getContractType(),
                po.getPurchaseOrderNo(), po.getSourceType(), po.getSupplierId(), po.getSupplierName(),
                po.getSupplierPhone(), po.getSupplierCreditCode(), po.getSupplierAccountName(),
                po.getSupplierBankName(), po.getSupplierBankAccount(), po.getPrepayPercent(), po.getSettlementMethod(),
                po.getSupplierAddress(), po.getContactPerson(),
                po.getBuyerCompanyId(), po.getBuyerCompanyName(),
                po.getBuyerCompanyCode(), po.getBuyerAddress(), po.getPostCode(),
                po.getBuyerPhone(), po.getFax(),
                po.getOriginalAmount(), po.getDiscountedAmount(),
                po.getContractAmount(), po.getContractDate(), po.getDeliveryDate(), po.getStatus(),
                po.getCreatorId(), po.getCreatorName(), po.getCreateType(),
                po.getContractPdfUrl(), po.getSignedPdfUrl(), po.getFadadaTaskId(),
                items
        ));
    }

    @Override
    public void updatePdfUrl(long contractId, String pdfUrl) {
        contractMapper.updatePdfUrl(contractId, pdfUrl);
    }

    @Override
    public void markSigning(long contractId, String fadadaTaskId) {
        if (contractMapper.markSigning(contractId, fadadaTaskId) != 1) {
            throw new IllegalStateException("合同状态已变更，无法发起签署");
        }
    }

    @Override
    public void cancel(long contractId, String cancelReason) {
        if (contractMapper.cancel(contractId, cancelReason) != 1) {
            throw new IllegalStateException("合同状态已变更，无法作废");
        }
    }

    @Override
    public void markExecuting(long contractId) {
        if (contractMapper.markExecuting(contractId) != 1) {
            throw new IllegalStateException("合同状态已变更，无法完成签署回调");
        }
    }

    @Override
    public void updateSignedPdfUrl(long contractId, String signedPdfUrl) {
        contractMapper.updateSignedPdfUrl(contractId, signedPdfUrl);
    }

    private ContractPO toPO(Contract c) {
        ContractPO po = new ContractPO();
        po.setId(c.getId());
        po.setContractNo(c.getContractNo());
        po.setContractName(c.getContractName());
        po.setContractType(c.getContractType());
        po.setPurchaseOrderNo(c.getPurchaseOrderNo());
        po.setSourceType(c.getSourceType());
        po.setSupplierId(c.getSupplierId());
        po.setSupplierName(c.getSupplierName());
        po.setSupplierPhone(c.getSupplierPhone());
        po.setSupplierCreditCode(c.getSupplierCreditCode());
        po.setSupplierAccountName(c.getSupplierAccountName());
        po.setSupplierBankName(c.getSupplierBankName());
        po.setSupplierBankAccount(c.getSupplierBankAccount());
        po.setPrepayPercent(c.getPrepayPercent());
        po.setSettlementMethod(c.getSettlementMethod());
        po.setSupplierAddress(c.getSupplierAddress());
        po.setContactPerson(c.getContactPerson());
        po.setBuyerCompanyId(c.getBuyerCompanyId());
        po.setBuyerCompanyName(c.getBuyerCompanyName());
        po.setBuyerCompanyCode(c.getBuyerCompanyCode());
        po.setBuyerAddress(c.getBuyerAddress());
        po.setPostCode(c.getPostCode());
        po.setBuyerPhone(c.getBuyerPhone());
        po.setFax(c.getFax());
        po.setOriginalAmount(c.getOriginalAmount());
        po.setDiscountedAmount(c.getDiscountedAmount());
        po.setContractAmount(c.getContractAmount());
        po.setContractDate(c.getContractDate());
        po.setDeliveryDate(c.getDeliveryDate());
        po.setTemplateId(c.getTemplateId());
        po.setStatus(c.getStatus().getCode());
        po.setCreatorId(c.getCreatorId());
        po.setCreatorName(c.getCreatorName());
        po.setCreateType(c.getCreateType());
        return po;
    }

    private ContractItemPO toItemPO(Long contractId, ContractItem item) {
        ContractItemPO po = new ContractItemPO();
        po.setContractId(contractId);
        po.setContractNo(item.getContractNo());
        po.setSku(item.getSku());
        po.setProductId(item.getProductId());
        po.setProductName(item.getProductName());
        po.setSpecification(item.getSpecification());
        po.setQuantity(item.getQuantity());
        po.setUnit(item.getUnit());
        po.setUnitPrice(item.getUnitPrice());
        po.setAmount(item.getAmount());
        po.setDeliveryDate(item.getDeliveryDate());
        po.setWarehouseName(item.getWarehouseName());
        po.setCasesNum(item.getCasesNum());
        po.setQuantityPerCase(item.getQuantityPerCase());
        po.setPicUrl(item.getPicUrl());
        po.setRemark(item.getRemark());
        return po;
    }

    private ContractItem toItemDomain(ContractItemPO po) {
        ContractItem item = new ContractItem();
        item.setId(po.getId());
        item.setContractId(po.getContractId());
        item.setContractNo(po.getContractNo());
        item.setSku(po.getSku());
        item.setProductId(po.getProductId());
        item.setProductName(po.getProductName());
        item.setSpecification(po.getSpecification());
        item.setQuantity(po.getQuantity());
        item.setUnit(po.getUnit());
        item.setUnitPrice(po.getUnitPrice());
        item.setAmount(po.getAmount());
        item.setDeliveryDate(po.getDeliveryDate());
        item.setWarehouseName(po.getWarehouseName());
        item.setCasesNum(po.getCasesNum());
        item.setQuantityPerCase(po.getQuantityPerCase());
        item.setPicUrl(po.getPicUrl());
        item.setRemark(po.getRemark());
        return item;
    }

    private ProcurementOperationLogPO toLogPO(ContractOperationLog log) {
        ProcurementOperationLogPO po = new ProcurementOperationLogPO();
        po.setBusinessType(1);
        po.setDataId(log.getContractId());
        po.setDataName(log.getContractNo());
        po.setOperatorId(toNumericOperatorId(log.getOperatorId()));
        po.setOperatorName(log.getOperatorName());
        po.setOperationType(log.getOperationType());
        po.setOperationDesc(log.getOperationDesc());
        po.setOperationDetails(log.getOperationDetails());
        po.setIpAddress(log.getIpAddress());
        return po;
    }

    @Override
    public void markCompleted(long contractId) {
        if (contractMapper.markCompleted(contractId) != 1) throw new IllegalStateException("合同状态已变更，无法更新为完成");
    }

    @Override
    public List<Contract> findExecutingContracts() {
        return contractMapper.selectExecutingContracts().stream().map(this::toDomainWithoutItems).toList();
    }

    /**
     * 现有合同操作人来源是登录邮箱，而采购操作日志表要求 bigint 类型的 operator_id。
     * 邮箱完整保存在 operator_name 中；没有可用数字用户 ID 时以 0 表示未关联内部用户 ID。
     */
    private Long toNumericOperatorId(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(operatorId);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String buildCreateDetails(Contract contract) {
        int itemCount = contract.getItems() == null ? 0 : contract.getItems().size();
        boolean manual = contract.getCreateType() != null
                && contract.getCreateType() == Contract.CREATE_TYPE_MANUAL;
        return "PO单号=" + valueOf(contract.getPurchaseOrderNo())
                + "; 创建方式=" + (manual ? "手动" : "自动")
                + "; 供方=" + valueOf(contract.getSupplierName())
                + "; 供方联系人=" + valueOf(contract.getContactPerson())
                + "; 供方电话=" + valueOf(contract.getSupplierPhone())
                + "; 需方=" + valueOf(contract.getBuyerCompanyName())
                + "; 合同金额=" + valueOf(contract.getContractAmount())
                + "; 合同日期=" + valueOf(contract.getContractDate())
                + "; 交付日期=" + valueOf(contract.getDeliveryDate())
                + "; 商品明细数=" + itemCount;
    }

    private String valueOf(Object value) {
        return value == null ? "未填写" : String.valueOf(value);
    }

    @Override
    public ContractPage findByPage(com.scione.scm.bill.application.dto.ContractListQueryRequest request) {
        // 1. 统计总数
        long total = contractMapper.countByCondition(request);

        // 2. 查询当前页数据
        List<ContractPO> pos = contractMapper.selectByPage(request);

        // 3. PO 转 Domain（列表查询不需要加载明细）
        List<Contract> contracts = pos.stream()
                .map(this::toDomainWithoutItems)
                .toList();

        return new ContractPage(total, contracts);
    }

    @Override
    public Optional<Contract> findById(Long contractId) {
        ContractPO po = contractMapper.selectById(contractId);
        if (po == null) {
            return Optional.empty();
        }

        // 查询明细
        List<ContractItemPO> itemPOs = itemMapper.selectByContractId(contractId);
        List<ContractItem> items = itemPOs.stream()
                .map(this::toItemDomain)
                .toList();

        // 组装聚合根
        return Optional.of(Contract.rehydrate(
                po.getId(), po.getContractNo(), po.getContractName(), po.getContractType(),
                po.getPurchaseOrderNo(), po.getSourceType(), po.getSupplierId(), po.getSupplierName(),
                po.getSupplierPhone(), po.getSupplierCreditCode(), po.getSupplierAccountName(),
                po.getSupplierBankName(), po.getSupplierBankAccount(), po.getPrepayPercent(), po.getSettlementMethod(),
                po.getSupplierAddress(), po.getContactPerson(),
                po.getBuyerCompanyId(), po.getBuyerCompanyName(),
                po.getBuyerCompanyCode(), po.getBuyerAddress(), po.getPostCode(),
                po.getBuyerPhone(), po.getFax(),
                po.getOriginalAmount(), po.getDiscountedAmount(),
                po.getContractAmount(), po.getContractDate(), po.getDeliveryDate(), po.getStatus(),
                po.getCreatorId(), po.getCreatorName(), po.getCreateType(),
                po.getContractPdfUrl(), po.getSignedPdfUrl(), po.getFadadaTaskId(),
                items
        ));
    }

    /**
     * PO 转 Domain（不含明细，用于列表查询）。
     */
    private Contract toDomainWithoutItems(ContractPO po) {
        return Contract.rehydrate(
                po.getId(), po.getContractNo(), po.getContractName(), po.getContractType(),
                po.getPurchaseOrderNo(), po.getSourceType(), po.getSupplierId(), po.getSupplierName(),
                po.getSupplierPhone(), po.getSupplierCreditCode(), po.getSupplierAccountName(),
                po.getSupplierBankName(), po.getSupplierBankAccount(), po.getPrepayPercent(), po.getSettlementMethod(),
                po.getSupplierAddress(), po.getContactPerson(),
                po.getBuyerCompanyId(), po.getBuyerCompanyName(),
                po.getBuyerCompanyCode(), po.getBuyerAddress(), po.getPostCode(),
                po.getBuyerPhone(), po.getFax(),
                po.getOriginalAmount(), po.getDiscountedAmount(),
                po.getContractAmount(), po.getContractDate(), po.getDeliveryDate(), po.getStatus(),
                po.getCreatorId(), po.getCreatorName(), po.getCreateType(),
                po.getContractPdfUrl(), po.getSignedPdfUrl(), po.getFadadaTaskId(),
                null  // items=null
        );
    }

    @Override
    @Transactional
    public void update(Contract contract) {
        // 1. 更新合同主表
        ContractPO po = toPO(contract);
        contractMapper.updateById(po);
        log.info("合同主表更新成功：contractId={}", contract.getId());

        // 2. 更新明细
        List<ContractItem> items = contract.getItems();
        if (items != null && !items.isEmpty()) {
            for (ContractItem item : items) {
                ContractItemPO itemPO = toItemPO(contract.getId(), item);
                itemPO.setId(item.getId());  // 确保有ID才能更新
                itemMapper.updateById(itemPO);
            }
            log.info("合同明细更新成功：contractId={}, itemCount={}", contract.getId(), items.size());
        }
    }

    @Override
    public void saveOperationLog(ContractOperationLog operationLog) {
        logMapper.insert(toLogPO(operationLog));
        log.info("操作日志保存成功：contractId={}, operationType={}",
                operationLog.getContractId(), operationLog.getOperationType());
    }
}
