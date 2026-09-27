package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.ContractItemUpdateRequest;
import com.scione.scm.bill.application.dto.ContractUpdateRequest;
import com.scione.scm.bill.application.dto.ContractUpdateResponse;
import com.scione.scm.bill.application.port.ContractFileStore;
import com.scione.scm.bill.application.port.ContractPdfConverter;
import com.scione.scm.bill.application.port.ContractTemplateService;
import com.scione.scm.bill.application.port.LingxingProductClient;
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
    private final ContractTemplateService contractTemplateService;
    private final ContractFileStore contractFileStore;
    private final ContractPdfConverter contractPdfConverter;
    private final LingxingProductClient lingxingProductClient;

    /**
     * 修改合同。
     *
     * @param contractId 合同ID
     * @param request    修改请求
     * @return 修改结果
     */
    @Transactional
    public ContractUpdateResponse updateContract(Long contractId, ContractUpdateRequest request, String operatorEmail) {
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

        // 2. 检查合同状态（已签署、已完成、已取消的合同不允许修改）
        if (contract.getStatus() == ContractStatus.COMPLETED
                || contract.getStatus() == ContractStatus.CANCELLED) {
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
        saveOperationLog(contract, operatorEmail, changeDetails);

        // 9. 重新生成合同文件
        String fileUrl = regenerateContractFile(contract);

        log.info("合同修改完成：contractNo={}, fileUrl={}", contractNo, fileUrl);

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

            // 如果传入了金额，直接覆盖
            if (itemRequest.getAmount() != null
                    && itemRequest.getAmount().compareTo(item.getAmount()) != 0) {
                log.info("覆盖明细金额：itemId={}, old={}, new={}",
                        itemId, item.getAmount(), itemRequest.getAmount());
                changeDetails.add(itemDesc + "金额：" + item.getAmount() + " → " + itemRequest.getAmount());
                item.setAmount(itemRequest.getAmount());
            }

            // 修改交货日期
            if (StringUtils.hasText(itemRequest.getDeliveryDate())) {
                try {
                    LocalDate newDate = LocalDate.parse(itemRequest.getDeliveryDate());
                    if (!newDate.equals(item.getDeliveryDate())) {
                        log.info("修改明细交货日期：itemId={}, old={}, new={}",
                                itemId, item.getDeliveryDate(), newDate);
                        changeDetails.add(itemDesc + "交货日期：" + item.getDeliveryDate() + " → " + newDate);
                        item.setDeliveryDate(newDate);
                    }
                } catch (Exception ex) {
                    log.warn("明细交货日期格式错误，忽略：itemId={}, date={}", itemId, itemRequest.getDeliveryDate());
                }
            }

            // 修改备注
            if (StringUtils.hasText(itemRequest.getRemark())
                    && !itemRequest.getRemark().equals(item.getRemark())) {
                log.info("修改明细备注：itemId={}, old={}, new={}",
                        itemId, item.getRemark(), itemRequest.getRemark());
                changeDetails.add(itemDesc + "备注：" + item.getRemark() + " → " + itemRequest.getRemark());
                item.setRemark(itemRequest.getRemark());
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
    private void saveOperationLog(Contract contract, String operatorEmail, List<String> changeDetails) {
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
                "修改合同",
                operationDetails
        );

        contractRepository.saveOperationLog(operationLog);
        log.info("操作日志已保存：contractNo={}, operator={}, changeCount={}",
                contract.getContractNo(), operatorName, changeDetails.size());
    }

    /**
     * 重新生成合同文件。
     */
    private String regenerateContractFile(Contract contract) {
        String contractNo = contract.getContractNo();
        log.info("开始重新生成合同文件：contractNo={}", contractNo);

        try {
            // 1. 从领星API重新获取商品图片
            enrichContractItemsWithImages(contract);

            // 2. 直接根据合同数据生成 PDF，运行环境不依赖 Office/LibreOffice。
            byte[] pdfBytes = contractPdfConverter.convert(contractTemplateService.fillTemplate(contract), contractNo);

            // 3. 上传到 S3，返回访问 URL
            String fileUrl = contractFileStore.store(contractNo, pdfBytes, "pdf");

            // 4. 更新合同表的 contract_pdf_url 字段
            contractRepository.updatePdfUrl(contract.getId(), fileUrl);

            log.info("合同文件重新生成成功：contractNo={}, url={}", contractNo, fileUrl);
            return fileUrl;

        } catch (Exception ex) {
            log.error("合同文件重新生成失败：contractNo={}", contractNo, ex);
            return null;
        }
    }

    /**
     * 从领星API获取商品图片并填充到合同明细中（复用创建时的逻辑）。
     */
    private void enrichContractItemsWithImages(Contract contract) {
        String contractNo = contract.getContractNo();
        log.info("开始从领星API获取商品图片：contractNo={}", contractNo);

        try {
            List<ContractItem> contractItems = contract.getItems();
            if (contractItems == null || contractItems.isEmpty()) {
                log.warn("合同无明细，跳过图片获取：contractNo={}", contractNo);
                return;
            }

            int imageFoundCount = 0;
            int imageNotFoundCount = 0;
            int imageErrorCount = 0;

            // 为每个合同明细获取图片URL
            for (ContractItem contractItem : contractItems) {
                String sku = contractItem.getSku();

                if (sku == null || sku.isBlank()) {
                    log.warn("合同明细SKU为空，跳过图片获取：contractNo={}", contractNo);
                    imageNotFoundCount++;
                    continue;
                }

                try {
                    log.debug("查询商品详情：sku={}", sku);

                    // 调用领星API查询产品详情
                    Optional<LingxingProductClient.ProductDetail> productOpt =
                            lingxingProductClient.findBySku(sku);

                    if (productOpt.isPresent()) {
                        LingxingProductClient.ProductDetail product = productOpt.get();
                        String picUrl = product.picUrl();

                        if (picUrl != null && !picUrl.isBlank()) {
                            contractItem.setPicUrl(picUrl);
                            imageFoundCount++;
                            log.info("获取商品图片成功：sku={}, picUrl={}", sku, picUrl);
                        } else {
                            log.warn("商品无图片URL：sku={}", sku);
                            imageNotFoundCount++;
                        }
                    } else {
                        log.warn("领星API未找到商品：sku={}", sku);
                        imageNotFoundCount++;
                    }

                } catch (Exception ex) {
                    log.error("获取商品图片失败（继续处理其他明细）：sku={}", sku, ex);
                    imageErrorCount++;
                }
            }

            log.info("领星图片获取完成：contractNo={}, 成功={}, 未找到={}, 失败={}",
                    contractNo, imageFoundCount, imageNotFoundCount, imageErrorCount);

        } catch (Exception ex) {
            log.error("从领星获取商品图片失败（合同修改继续）：contractNo={}", contractNo, ex);
        }
    }
}
