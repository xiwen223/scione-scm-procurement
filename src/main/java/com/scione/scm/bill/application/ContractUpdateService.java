package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.ContractItemUpdateRequest;
import com.scione.scm.bill.application.dto.ContractUpdateRequest;
import com.scione.scm.bill.application.dto.ContractUpdateResponse;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.ContractStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 合同修改应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractUpdateService {

    private final ContractRepository contractRepository;

    /**
     * 专用明细修改入口：保证路径中的明细确实属于该合同，并仅允许创建状态的合同修改。
     * 实际更新、金额重算和操作日志均复用合同修改主流程；文件仅在下载或签署时生成。
     */
    @Transactional
    public ContractUpdateResponse updateContractItem(Long contractId, Long itemId,
                                                     ContractItemUpdateRequest itemRequest,
                                                     String operatorEmail) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new RuntimeException("合同不存在：contractId=" + contractId));
        if (contract.getStatus() != ContractStatus.CREATED) {
            throw new RuntimeException("仅创建状态的合同可修改明细");
        }
        boolean itemExists = contract.getItems().stream().anyMatch(item -> itemId.equals(item.getId()));
        if (!itemExists) {
            throw new RuntimeException("合同明细不存在或不属于该合同：itemId=" + itemId);
        }
        if (itemRequest.getQuantity() != null && itemRequest.getQuantity() <= 0) {
            throw new IllegalArgumentException("明细数量必须大于0");
        }
        if (itemRequest.getUnitPrice() != null && itemRequest.getUnitPrice().signum() < 0) {
            throw new IllegalArgumentException("明细单价不能小于0");
        }
        if (itemRequest.getAmount() != null || StringUtils.hasText(itemRequest.getDeliveryDate())
                || StringUtils.hasText(itemRequest.getRemark())) {
            throw new IllegalArgumentException("合同明细仅允许修改数量和不含税单价，金额将由系统自动计算");
        }

        itemRequest.setId(itemId);
        ContractItem item = contract.getItems().stream()
                .filter(current -> itemId.equals(current.getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("合同明细不存在或不属于该合同：itemId=" + itemId));
        List<String> changeDetails = new ArrayList<>();
        ContractUpdateRequest request = new ContractUpdateRequest();
        request.setItems(List.of(itemRequest));
        applyItemUpdates(contract, request, changeDetails);
        recalculateContractAmount(contract, changeDetails);

        // 单条明细编辑只发两条精确 SQL：该明细的数量/单价/金额 + 合同金额合计。
        contractRepository.updateItemPricing(item);
        contractRepository.updateAmounts(contract);
        saveOperationLog(contract, operatorEmail, "修改合同明细", changeDetails);
        log.info("合同单条明细编辑完成，无全量明细更新：contractNo={}, itemId={}", contract.getContractNo(), itemId);
        return new ContractUpdateResponse(contractId, contract.getContractNo(), contract.getContractPdfUrl(), "合同明细修改成功");
    }

    /**
     * 修改合同。
     *
     * @param contractId 合同ID
     * @param request    修改请求
     * @return 修改结果
     */
    @Transactional
    public ContractUpdateResponse updateContract(Long contractId, ContractUpdateRequest request, String operatorEmail) {
        String operationDesc = request.getDiscountedAmount() != null
                ? "修改合同折扣" : "修改合同";
        if (isDiscountOnlyRequest(request)) {
            return updateDiscountOnly(contractId, request, operatorEmail, operationDesc);
        }
        return updateContractInternal(contractId, request, operatorEmail, operationDesc);
    }

    private ContractUpdateResponse updateDiscountOnly(Long contractId, ContractUpdateRequest request,
                                                      String operatorEmail, String operationDesc) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new RuntimeException("合同不存在：contractId=" + contractId));
        if (contract.getStatus() != ContractStatus.CREATED) {
            throw new RuntimeException("合同状态为【" + contract.getStatus().getDesc() + "】，不允许修改");
        }
        List<String> changeDetails = new ArrayList<>();
        applyFieldUpdates(contract, request, changeDetails);
        recalculateContractAmount(contract, changeDetails);

        // 折扣保存只更新 contract 的三个金额字段；不更新任何 contract_item。
        contractRepository.updateAmounts(contract);
        saveOperationLog(contract, operatorEmail, operationDesc, changeDetails);
        log.info("合同折扣编辑完成，仅更新金额字段：contractNo={}, discountAmount={}, actualAmount={}",
                contract.getContractNo(), contract.getDiscountedAmount(), contract.getContractAmount());
        return new ContractUpdateResponse(contractId, contract.getContractNo(), contract.getContractPdfUrl(), "合同折扣修改成功");
    }

    private boolean isDiscountOnlyRequest(ContractUpdateRequest request) {
        return request.getDiscountedAmount() != null
                && request.getOriginalAmount() == null
                && !StringUtils.hasText(request.getSupplierName())
                && !StringUtils.hasText(request.getSupplierAddress())
                && !StringUtils.hasText(request.getContactPerson())
                && !StringUtils.hasText(request.getSupplierPhone())
                && !StringUtils.hasText(request.getSupplierCreditCode())
                && !StringUtils.hasText(request.getSupplierBankAccount())
                && !StringUtils.hasText(request.getSupplierBankName())
                && !StringUtils.hasText(request.getBuyerCompanyName())
                && !StringUtils.hasText(request.getBuyerAddress())
                && !StringUtils.hasText(request.getPostCode())
                && !StringUtils.hasText(request.getBuyerPhone())
                && !StringUtils.hasText(request.getFax())
                && !StringUtils.hasText(request.getContractDate())
                && !StringUtils.hasText(request.getDeliveryDate())
                && (request.getItems() == null || request.getItems().isEmpty());
    }

    private ContractUpdateResponse updateContractInternal(Long contractId, ContractUpdateRequest request,
                                                          String operatorEmail, String operationDesc) {
        log.info("开始修改合同：contractId={}", contractId);

        // 1. 加载现有合同
        Optional<Contract> contractOpt = contractRepository.findById(contractId);
        if (contractOpt.isEmpty()) {
            log.error("合同不存在：contractId={}", contractId);
            throw new RuntimeException("合同不存在：contractId=" + contractId);
        }
        Contract contract = contractOpt.get();
        String contractNo = contract.getContractNo();

        log.info("加载合同成功：contractNo={}, status={}", contractNo, contract.getStatus().getDesc());

        // 2. 仅“创建”状态允许修改合同及折扣，避免签署中的文件与已提交签署任务不一致。
        if (contract.getStatus() != ContractStatus.CREATED) {
            log.error("合同状态不允许修改：contractNo={}, status={}", contractNo, contract.getStatus().getDesc());
            throw new RuntimeException("合同状态为【" + contract.getStatus().getDesc() + "】，不允许修改");
        }

        // 3. 记录变更内容（用于操作日志）
        List<String> changeDetails = new ArrayList<>();

        // 4. 应用字段修改
        applyFieldUpdates(contract, request, changeDetails);

        // 5. 应用明细修改
        applyItemUpdates(contract, request, changeDetails);

        // 6. 重新计算合同金额
        recalculateContractAmount(contract, changeDetails);

        // 7. 保存修改
        contractRepository.update(contract);
        log.info("合同修改已保存：contractNo={}, changeCount={}", contractNo, changeDetails.size());

        // 8. 记录操作日志
        saveOperationLog(contract, operatorEmail, operationDesc, changeDetails);

        // 9. 编辑阶段（折扣、明细及其他可编辑字段）只修改数据库，绝不生成合同文件。
        //    原始合同下载和发起签署均会从数据库读取当前值，生成并保存最新 PDF。
        String fileUrl = contract.getContractPdfUrl();
        log.info("合同编辑完成，跳过 PDF 重生成：contractNo={}, operation={}, originalAmount={}, discountAmount={}, actualAmount={}",
                contractNo, operationDesc, contract.getOriginalAmount(), contract.getDiscountedAmount(), contract.getContractAmount());

        return new ContractUpdateResponse(
                contractId,
                contractNo,
                fileUrl,
                "合同修改成功"
        );
    }


    /**
     * 应用字段更新。
     */
    private void applyFieldUpdates(Contract contract, ContractUpdateRequest request, List<String> changeDetails) {
        String contractNo = contract.getContractNo();

        // 供方信息
        if (StringUtils.hasText(request.getSupplierName())
                && !request.getSupplierName().equals(contract.getSupplierName())) {
            log.info("修改供方名称：contractNo={}, old={}, new={}",
                    contractNo, contract.getSupplierName(), request.getSupplierName());
            changeDetails.add("供方名称：" + contract.getSupplierName() + " → " + request.getSupplierName());
            contract.setSupplierName(request.getSupplierName());
        }

        if (StringUtils.hasText(request.getSupplierAddress())
                && !request.getSupplierAddress().equals(contract.getSupplierAddress())) {
            log.info("修改供方地址：contractNo={}, old={}, new={}",
                    contractNo, contract.getSupplierAddress(), request.getSupplierAddress());
            changeDetails.add("供方地址：" + contract.getSupplierAddress() + " → " + request.getSupplierAddress());
            contract.setSupplierAddress(request.getSupplierAddress());
        }

        if (StringUtils.hasText(request.getContactPerson())
                && !request.getContactPerson().equals(contract.getContactPerson())) {
            log.info("修改供方联系人：contractNo={}, old={}, new={}",
                    contractNo, contract.getContactPerson(), request.getContactPerson());
            changeDetails.add("供方联系人：" + contract.getContactPerson() + " → " + request.getContactPerson());
            contract.setContactPerson(request.getContactPerson());
        }

        if (StringUtils.hasText(request.getSupplierPhone())
                && !request.getSupplierPhone().equals(contract.getSupplierPhone())) {
            log.info("修改供方电话：contractNo={}, old={}, new={}",
                    contractNo, contract.getSupplierPhone(), request.getSupplierPhone());
            changeDetails.add("供方电话：" + contract.getSupplierPhone() + " → " + request.getSupplierPhone());
            contract.setSupplierPhone(request.getSupplierPhone());
        }

        if (StringUtils.hasText(request.getSupplierCreditCode())
                && !request.getSupplierCreditCode().equals(contract.getSupplierCreditCode())) {
            log.info("修改供方统一社会信用代码：contractNo={}", contractNo);
            changeDetails.add("供方统一社会信用代码：" + contract.getSupplierCreditCode()
                    + " → " + request.getSupplierCreditCode());
            contract.setSupplierCreditCode(request.getSupplierCreditCode());
        }

        if (StringUtils.hasText(request.getSupplierBankAccount())
                && !request.getSupplierBankAccount().equals(contract.getSupplierBankAccount())) {
            log.info("修改供方银行账号：contractNo={}", contractNo);
            changeDetails.add("供方银行账号：" + contract.getSupplierBankAccount()
                    + " → " + request.getSupplierBankAccount());
            contract.setSupplierBankAccount(request.getSupplierBankAccount());
        }

        if (StringUtils.hasText(request.getSupplierBankName())
                && !request.getSupplierBankName().equals(contract.getSupplierBankName())) {
            log.info("修改供方开户行：contractNo={}", contractNo);
            changeDetails.add("供方开户行：" + contract.getSupplierBankName()
                    + " → " + request.getSupplierBankName());
            contract.setSupplierBankName(request.getSupplierBankName());
        }

        // 需方信息
        if (StringUtils.hasText(request.getBuyerCompanyName())
                && !request.getBuyerCompanyName().equals(contract.getBuyerCompanyName())) {
            log.info("修改需方名称：contractNo={}, old={}, new={}",
                    contractNo, contract.getBuyerCompanyName(), request.getBuyerCompanyName());
            changeDetails.add("需方名称：" + contract.getBuyerCompanyName() + " → " + request.getBuyerCompanyName());
            contract.setBuyerCompanyName(request.getBuyerCompanyName());
        }

        if (StringUtils.hasText(request.getBuyerAddress())
                && !request.getBuyerAddress().equals(contract.getBuyerAddress())) {
            log.info("修改需方地址：contractNo={}, old={}, new={}",
                    contractNo, contract.getBuyerAddress(), request.getBuyerAddress());
            changeDetails.add("需方地址：" + contract.getBuyerAddress() + " → " + request.getBuyerAddress());
            contract.setBuyerAddress(request.getBuyerAddress());
        }

        if (StringUtils.hasText(request.getPostCode())
                && !request.getPostCode().equals(contract.getPostCode())) {
            log.info("修改需方邮编：contractNo={}, old={}, new={}",
                    contractNo, contract.getPostCode(), request.getPostCode());
            changeDetails.add("需方邮编：" + contract.getPostCode() + " → " + request.getPostCode());
            contract.setPostCode(request.getPostCode());
        }

        if (StringUtils.hasText(request.getBuyerPhone())
                && !request.getBuyerPhone().equals(contract.getBuyerPhone())) {
            log.info("修改需方电话：contractNo={}, old={}, new={}",
                    contractNo, contract.getBuyerPhone(), request.getBuyerPhone());
            changeDetails.add("需方电话：" + contract.getBuyerPhone() + " → " + request.getBuyerPhone());
            contract.setBuyerPhone(request.getBuyerPhone());
        }

        if (StringUtils.hasText(request.getFax())
                && !request.getFax().equals(contract.getFax())) {
            log.info("修改需方传真：contractNo={}, old={}, new={}",
                    contractNo, contract.getFax(), request.getFax());
            changeDetails.add("需方传真：" + contract.getFax() + " → " + request.getFax());
            contract.setFax(request.getFax());
        }

        // 金额信息
        if (request.getOriginalAmount() != null
                && request.getOriginalAmount().compareTo(contract.getOriginalAmount()) != 0) {
            log.info("修改原价：contractNo={}, old={}, new={}",
                    contractNo, contract.getOriginalAmount(), request.getOriginalAmount());
            changeDetails.add("原价：" + contract.getOriginalAmount() + " → " + request.getOriginalAmount());
            contract.setOriginalAmount(request.getOriginalAmount());
        }

        if (request.getDiscountedAmount() != null
                && request.getDiscountedAmount().compareTo(
                contract.getDiscountedAmount() == null ? BigDecimal.ZERO : contract.getDiscountedAmount()) != 0) {
            log.info("修改折扣金额：contractNo={}, old={}, new={}",
                    contractNo, contract.getDiscountedAmount(), request.getDiscountedAmount());
            changeDetails.add("折扣金额：" + contract.getDiscountedAmount() + " → " + request.getDiscountedAmount());
            contract.setDiscountedAmount(request.getDiscountedAmount());
        }

        // 日期信息
        if (StringUtils.hasText(request.getContractDate())) {
            try {
                LocalDate newDate = LocalDate.parse(request.getContractDate());
                if (!newDate.equals(contract.getContractDate())) {
                    log.info("修改签署日期：contractNo={}, old={}, new={}",
                            contractNo, contract.getContractDate(), newDate);
                    changeDetails.add("签署日期：" + contract.getContractDate() + " → " + newDate);
                    contract.setContractDate(newDate);
                }
            } catch (Exception ex) {
                log.warn("签署日期格式错误，忽略：{}", request.getContractDate());
            }
        }

        if (StringUtils.hasText(request.getDeliveryDate())) {
            try {
                LocalDate newDate = LocalDate.parse(request.getDeliveryDate());
                if (!newDate.equals(contract.getDeliveryDate())) {
                    log.info("修改交货日期：contractNo={}, old={}, new={}",
                            contractNo, contract.getDeliveryDate(), newDate);
                    changeDetails.add("交货日期：" + contract.getDeliveryDate() + " → " + newDate);
                    contract.setDeliveryDate(newDate);
                }
            } catch (Exception ex) {
                log.warn("交货日期格式错误，忽略：{}", request.getDeliveryDate());
            }
        }
    }

    /**
     * 应用明细修改。
     */
    private void applyItemUpdates(Contract contract, ContractUpdateRequest request, List<String> changeDetails) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            log.debug("无明细修改请求，跳过");
            return;
        }

        String contractNo = contract.getContractNo();
        List<ContractItem> contractItems = contract.getItems();

        // 构建明细ID到明细对象的映射
        Map<Long, ContractItem> itemMap = contractItems.stream()
                .collect(Collectors.toMap(ContractItem::getId, item -> item));

        log.info("开始修改合同明细：contractNo={}, requestItemCount={}", contractNo, request.getItems().size());

        for (ContractItemUpdateRequest itemRequest : request.getItems()) {
            Long itemId = itemRequest.getId();
            ContractItem item = itemMap.get(itemId);

            if (item == null) {
                log.warn("明细不存在，跳过：itemId={}", itemId);
                continue;
            }

            String itemDesc = "明细[" + item.getProductName() + "]";

            // 修改数量
            if (itemRequest.getQuantity() != null
                    && !itemRequest.getQuantity().equals(item.getQuantity())) {
                log.info("修改明细数量：itemId={}, old={}, new={}",
                        itemId, item.getQuantity(), itemRequest.getQuantity());
                changeDetails.add(itemDesc + "数量：" + item.getQuantity() + " → " + itemRequest.getQuantity());
                item.setQuantity(itemRequest.getQuantity());
            }

            // 修改单价
            if (itemRequest.getUnitPrice() != null
                    && itemRequest.getUnitPrice().compareTo(item.getUnitPrice()) != 0) {
                log.info("修改明细单价：itemId={}, old={}, new={}",
                        itemId, item.getUnitPrice(), itemRequest.getUnitPrice());
                changeDetails.add(itemDesc + "单价：" + item.getUnitPrice() + " → " + itemRequest.getUnitPrice());
                item.setUnitPrice(itemRequest.getUnitPrice());
            }

            // 重新计算明细金额（如果数量或单价改变了）
            if (itemRequest.getQuantity() != null || itemRequest.getUnitPrice() != null) {
                BigDecimal newAmount = new BigDecimal(item.getQuantity())
                        .multiply(item.getUnitPrice());
                if (newAmount.compareTo(item.getAmount()) != 0) {
                    log.info("重新计算明细金额：itemId={}, old={}, new={}",
                            itemId, item.getAmount(), newAmount);
                    changeDetails.add(itemDesc + "金额：" + item.getAmount() + " → " + newAmount);
                    item.setAmount(newAmount);
                }
            }

        }

        log.info("合同明细修改完成：contractNo={}, modifiedItemCount={}", contractNo, request.getItems().size());
    }

    /**
     * 重新计算合同金额。
     */
    private void recalculateContractAmount(Contract contract, List<String> changeDetails) {
        // 原价合计始终以合同明细金额之和为准：数量 × 不含税单价（或人工确认的明细金额）。
        BigDecimal originalAmount = contract.getItems() == null || contract.getItems().isEmpty()
                ? contract.getOriginalAmount()
                : contract.getItems().stream()
                        .map(ContractItem::getAmount)
                        .filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (originalAmount == null) {
            originalAmount = BigDecimal.ZERO;
        }
        if (contract.getOriginalAmount() == null || originalAmount.compareTo(contract.getOriginalAmount()) != 0) {
            log.info("重新计算原价合计：contractNo={}, old={}, new={}",
                    contract.getContractNo(), contract.getOriginalAmount(), originalAmount);
            changeDetails.add("原价合计：" + contract.getOriginalAmount() + " → " + originalAmount);
            contract.setOriginalAmount(originalAmount);
        }

        BigDecimal discountedAmount = contract.getDiscountedAmount() == null
                ? BigDecimal.ZERO
                : contract.getDiscountedAmount();

        if (discountedAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("折扣金额不能小于0");
        }
        if (discountedAmount.compareTo(originalAmount) > 0) {
            throw new IllegalArgumentException("折扣金额不能大于原价合计");
        }

        // 模板“整”后的金额为实际合同金额：原价合计 − 折扣。
        BigDecimal newContractAmount = originalAmount.subtract(discountedAmount);

        if (newContractAmount.compareTo(contract.getContractAmount()) != 0) {
            log.info("重新计算合同金额：contractNo={}, old={}, new={}",
                    contract.getContractNo(), contract.getContractAmount(), newContractAmount);
            changeDetails.add("合同金额：" + contract.getContractAmount() + " → " + newContractAmount);
            contract.setContractAmount(newContractAmount);
        }
    }

    /**
     * 保存操作日志。
     */
    private void saveOperationLog(Contract contract, String operatorEmail, String operationDesc,
                                  List<String> changeDetails) {
        String operatorId = StringUtils.hasText(operatorEmail) ? operatorEmail : "system";
        String operatorName = StringUtils.hasText(operatorEmail) ? operatorEmail : "系统";

        String operationDetails = changeDetails.isEmpty()
                ? "无变更"
                : String.join("；", changeDetails);

        ContractOperationLog operationLog = ContractOperationLog.ofUpdate(
                contract.getId(),
                contract.getContractNo(),
                operatorId,
                operatorName,
                operationDesc,
                operationDetails
        );

        contractRepository.saveOperationLog(operationLog);
        log.info("操作日志已保存：contractNo={}, operator={}, changeCount={}",
                contract.getContractNo(), operatorName, changeDetails.size());
    }

}
