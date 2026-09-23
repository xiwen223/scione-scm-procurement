package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ContractItemMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ContractMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ContractOperationLogMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractItemPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractOperationLogPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.scione.scm.bill.domain.contract.ContractPage;

import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis 的合同仓储适配器。
 */
@Repository
@RequiredArgsConstructor
public class MybatisContractRepository implements ContractRepository {

    private final ContractMapper contractMapper;
    private final ContractItemMapper itemMapper;
    private final ContractOperationLogMapper logMapper;

    @Override
    @Transactional
    public long create(Contract contract) {
        // 1. 插入合同主表
        ContractPO po = toPO(contract);
        contractMapper.insert(po);
        Long contractId = po.getId();
        contract.setId(contractId);

        // 2. 批量插入明细
        List<ContractItem> items = contract.getItems();
        if (items != null && !items.isEmpty()) {
            List<ContractItemPO> itemPOs = items.stream()
                    .map(item -> toItemPO(contractId, item))
                    .toList();
            itemMapper.batchInsert(itemPOs);
        }

        // 3. 插入 CREATE 操作日志
        ContractOperationLog log = ContractOperationLog.ofCreate(
                contractId,
                contract.getContractNo(),
                contract.getCreatorId(),
                contract.getCreatorName(),
                "系统自动创建采购合同"
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
                po.getSupplierPhone(), po.getSupplierAddress(), po.getContactPerson(),
                po.getBuyerCompanyId(), po.getBuyerCompanyName(),
                po.getBuyerCompanyCode(), po.getBuyerAddress(), po.getPostCode(),
                po.getBuyerPhone(), po.getFax(),
                po.getOriginalAmount(), po.getDiscountedAmount(),
                po.getContractAmount(), po.getContractDate(), po.getDeliveryDate(), po.getStatus(),
                po.getCreatorId(), po.getCreatorName(), po.getCreateType(),
                po.getContractPdfUrl(), po.getSignedPdfUrl(),
                items
        ));
    }

    @Override
    public void updatePdfUrl(long contractId, String pdfUrl) {
        contractMapper.updatePdfUrl(contractId, pdfUrl);
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
        item.setRemark(po.getRemark());
        return item;
    }

    private ContractOperationLogPO toLogPO(ContractOperationLog log) {
        ContractOperationLogPO po = new ContractOperationLogPO();
        po.setContractId(log.getContractId());
        po.setContractNo(log.getContractNo());
        po.setOperatorId(log.getOperatorId());
        po.setOperatorName(log.getOperatorName());
        po.setOperationType(log.getOperationType());
        po.setOperationDesc(log.getOperationDesc());
        po.setOperationDetails(log.getOperationDetails());
        po.setIpAddress(log.getIpAddress());
        return po;
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
                po.getSupplierPhone(), po.getSupplierAddress(), po.getContactPerson(),
                po.getBuyerCompanyId(), po.getBuyerCompanyName(),
                po.getBuyerCompanyCode(), po.getBuyerAddress(), po.getPostCode(),
                po.getBuyerPhone(), po.getFax(),
                po.getOriginalAmount(), po.getDiscountedAmount(),
                po.getContractAmount(), po.getContractDate(), po.getDeliveryDate(), po.getStatus(),
                po.getCreatorId(), po.getCreatorName(), po.getCreateType(),
                po.getContractPdfUrl(), po.getSignedPdfUrl(),
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
                po.getSupplierPhone(), po.getSupplierAddress(), po.getContactPerson(),
                po.getBuyerCompanyId(), po.getBuyerCompanyName(),
                po.getBuyerCompanyCode(), po.getBuyerAddress(), po.getPostCode(),
                po.getBuyerPhone(), po.getFax(),
                po.getOriginalAmount(), po.getDiscountedAmount(),
                po.getContractAmount(), po.getContractDate(), po.getDeliveryDate(), po.getStatus(),
                po.getCreatorId(), po.getCreatorName(), po.getCreateType(),
                po.getContractPdfUrl(), po.getSignedPdfUrl(),
                null  // items=null
        );
    }
}