package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.ContractItemUpdateRequest;
import com.scione.scm.bill.application.dto.ContractUpdateRequest;
import com.scione.scm.bill.application.dto.ContractUpdateResponse;
import com.scione.scm.bill.application.port.ContractTemplateService;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.company.BuyerCompany;
import com.scione.scm.bill.domain.company.BuyerCompanyRepository;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.ContractStatus;
import com.scione.scm.bill.domain.contract.RatioText;
import com.scione.scm.bill.domain.contract.enums.ContractType;
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
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 合同修改应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractUpdateService {

    /**
     * 合同编号允许的字符：字母、数字、短横线、下划线，最长 32 位。
     * 不强制 "HT+日期+序号" 格式 —— 允许业务侧按自己的编号体系填。
     */
    private static final Pattern CONTRACT_NO_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final ContractRepository contractRepository;
    private final BuyerCompanyRepository buyerCompanyRepository;
    private final ContractTemplateService contractTemplateService;

    /** 统一保存合同主信息、折扣及商品明细，所有变更在同一事务提交。 */
    @Transactional
    public ContractUpdateResponse updateContract(Long contractId, ContractUpdateRequest request, String operatorEmail) {
        // 只改折扣的判断现在仅选择日志描述，不再分成第二套保存或 PDF 生成流程。
        String operationDesc = isDiscountOnlyRequest(request)
                ? "修改合同折扣" : "修改合同";
        return updateContractInternal(contractId, request, operatorEmail, operationDesc);
    }

    private boolean isDiscountOnlyRequest(ContractUpdateRequest request) {
        return request.getDiscountedAmount() != null
                && request.getOriginalAmount() == null
                && !StringUtils.hasText(request.getContractNo())
                && request.getContractType() == null
                && !StringUtils.hasText(request.getContractName())
                && request.getBuyerCompanyId() == null
                && !StringUtils.hasText(request.getSupplierName())
                && !StringUtils.hasText(request.getSupplierAddress())
                && !StringUtils.hasText(request.getContactPerson())
                && !StringUtils.hasText(request.getSupplierPhone())
                && !StringUtils.hasText(request.getSupplierCreditCode())
                && !StringUtils.hasText(request.getSupplierAccountName())
                && !StringUtils.hasText(request.getSupplierBankAccount())
                && !StringUtils.hasText(request.getSupplierBankName())
                && !StringUtils.hasText(request.getPrepayPercent())
                && !StringUtils.hasText(request.getSettlementMethod())
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
            // 业务问题（资源不存在）用 WARN，不打堆栈；系统故障才用 ERROR
            log.info("合同不存在，无法修改：contractId={}", contractId);
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                    "合同不存在：contractId=" + contractId);
        }
        Contract contract = contractOpt.get();
        String oldContractNo = contract.getContractNo();

        log.info("加载合同成功：contractNo={}, status={}", oldContractNo, contract.getStatus().getDesc());

        // 2. 仅“创建”状态允许修改合同及折扣，避免签署中的文件与已提交签署任务不一致。
        if (contract.isSignLaunching() || contract.getStatus() != ContractStatus.CREATED) {
            log.info("合同状态不允许修改：contractNo={}, status={}", oldContractNo, contract.getStatus().getDesc());
            throw new BusinessException(ResultCode.CONTRACT_STATUS_NOT_ALLOWED,
                    "合同状态为【" + contract.getStatus().getDesc() + "】，不允许修改");
        }

        // 3. 记录变更内容（用于操作日志）
        List<String> changeDetails = new ArrayList<>();

        BigDecimal oldOriginalAmount = contract.getOriginalAmount();
        BigDecimal oldDiscountedAmount = contract.getDiscountedAmount();
        BigDecimal oldContractAmount = contract.getContractAmount();
        boolean mainChanged = applyFieldUpdates(contract, request, changeDetails);
        List<ContractItem> changedItems = applyItemUpdates(contract, request, changeDetails);
        if (!changedItems.isEmpty() || !Objects.equals(oldOriginalAmount, contract.getOriginalAmount())
                || !Objects.equals(oldDiscountedAmount, contract.getDiscountedAmount())) {
            recalculateContractAmount(contract, changeDetails);
        }
        if (changeDetails.isEmpty()) {
            return new ContractUpdateResponse(contractId, contract.getContractNo(), contract.getContractPdfUrl(), "合同无变更");
        }
        String contractNo = contract.getContractNo();
        if (mainChanged) {
            contractRepository.updateMain(contract);
        } else if (!Objects.equals(oldOriginalAmount, contract.getOriginalAmount())
                || !Objects.equals(oldDiscountedAmount, contract.getDiscountedAmount())
                || !Objects.equals(oldContractAmount, contract.getContractAmount())) {
            contractRepository.updateAmounts(contract);
        }
        for (ContractItem item : changedItems) {
            contractRepository.updateItemPricing(item);
        }
        // 合同编号变了：主表之外的冗余引用（明细 contract_no、操作日志 data_name）必须一起改，
        // 否则明细检索和操作日志会挂着一个不存在的编号。
        if (!Objects.equals(oldContractNo, contractNo)) {
            contractRepository.updateContractNoReferences(contractId, contractNo);
            log.info("合同编号变更完成并已同步全部引用：contractId={}, {} → {}", contractId, oldContractNo, contractNo);
        }
        // 旧 PDF 与新数据已不一致：清空地址作为失效标记，而不是删除 S3 历史文件。
        contractRepository.updatePdfUrl(contractId, null);
        contract.setContractPdfUrl(null);
        log.info("合同修改已保存：contractNo={}, changeCount={}", contractNo, changeDetails.size());

        // 8. 记录操作日志
        saveOperationLog(contract, operatorEmail, operationDesc, changeDetails);

        // 9. 编辑阶段（折扣、明细及其他可编辑字段）只修改数据库，绝不生成合同文件。
        //    原始合同下载和发起签署均会从数据库读取当前值，生成并保存最新 PDF。
        String fileUrl = null;
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
    private boolean applyFieldUpdates(Contract contract, ContractUpdateRequest request, List<String> changeDetails) {
        String contractNo = contract.getContractNo();

        // 合同编号：主表 + 明细冗余列 + 操作日志快照三处都要一致。
        // 这里只做校验与内存赋值，冗余引用在保存后由 updateContractInternal 统一同步。
        if (StringUtils.hasText(request.getContractNo())) {
            String newContractNo = request.getContractNo().trim();
            if (!newContractNo.equals(contract.getContractNo())) {
                if (!CONTRACT_NO_PATTERN.matcher(newContractNo).matches()) {
                    throw new IllegalArgumentException("合同编号只能包含字母、数字、短横线和下划线，且不超过 32 位");
                }
                if (contractRepository.existsContractNo(newContractNo, contract.getId())) {
                    throw new IllegalArgumentException("合同编号已被其它合同占用：" + newContractNo);
                }
                log.info("修改合同编号：contractId={}, old={}, new={}",
                        contract.getId(), contract.getContractNo(), newContractNo);
                changeDetails.add("合同编号：" + contract.getContractNo() + " → " + newContractNo);
                contract.setContractNo(newContractNo);
                // 后续字段的日志统一用新编号
                contractNo = newContractNo;
                // 明细聚合里也冗余了合同编号，一并刷新，避免返回给调用方的聚合自相矛盾。
                if (contract.getItems() != null) {
                    contract.getItems().forEach(item -> item.setContractNo(newContractNo));
                }
            }
        }

        // 合同类型：类型决定用哪套模板，切换后必须按新类型重解析默认模板，否则会生成版式不符的合同文件。
        if (request.getContractType() != null && !request.getContractType().equals(contract.getContractType())) {
            if (!ContractType.supports(request.getContractType())) {
                throw new IllegalArgumentException("不支持的合同类型：" + request.getContractType());
            }
            changeDetails.add("合同类型：" + ContractType.descOf(contract.getContractType())
                    + " → " + ContractType.descOf(request.getContractType()));
            contract.setContractType(request.getContractType());
            applyDefaultTemplateForType(contract, changeDetails);
        }

        if (StringUtils.hasText(request.getContractName())
                && !request.getContractName().equals(contract.getContractName())) {
            changeDetails.add("合同名称：" + contract.getContractName() + " → " + request.getContractName());
            contract.setContractName(request.getContractName().trim());
        }

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

        if (StringUtils.hasText(request.getSupplierAccountName())
                && !request.getSupplierAccountName().equals(contract.getSupplierAccountName())) {
            changeDetails.add("供方收款人：" + contract.getSupplierAccountName()
                    + " → " + request.getSupplierAccountName());
            contract.setSupplierAccountName(request.getSupplierAccountName());
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

        if (StringUtils.hasText(request.getPrepayPercent())) {
            // 入库口径统一为两位小数（填 0 存 "0.00"），比较也用规范化后的值，避免 0 与 0.00 反复写成同一条变更。
            String prepayPercent = RatioText.of(request.getPrepayPercent());
            if (!prepayPercent.equals(contract.getPrepayPercent())) {
                changeDetails.add("预付款比例：" + contract.getPrepayPercent() + " → " + prepayPercent);
                contract.setPrepayPercent(prepayPercent);
            }
        }
        if (StringUtils.hasText(request.getSettlementMethod())
                && !request.getSettlementMethod().equals(contract.getSettlementMethod())) {
            changeDetails.add("结算方式：" + contract.getSettlementMethod() + " → " + request.getSettlementMethod());
            contract.setSettlementMethod(request.getSettlementMethod());
        }

        // 需方选择公司后，以 buyer_company 档案为准同步合同快照，避免需方名称与签订地址不一致。
        if (request.getBuyerCompanyId() != null) {
            BuyerCompany buyer = buyerCompanyRepository.findById(request.getBuyerCompanyId())
                    .orElseThrow(() -> new IllegalArgumentException("选择的需方公司不存在"));
            if (!Objects.equals(buyer.getIsActive(), 1)) {
                throw new IllegalArgumentException("选择的需方公司已停用，请选择启用中的公司");
            }
            if (!Objects.equals(contract.getBuyerCompanyId(), buyer.getId())) {
                changeDetails.add("需方公司ID：" + contract.getBuyerCompanyId() + " → " + buyer.getId());
            }
            addBuyerSnapshotChange(changeDetails, "需方公司", contract.getBuyerCompanyName(), buyer.getCompanyName());
            addBuyerSnapshotChange(changeDetails, "需方统一社会信用代码", contract.getBuyerCompanyCode(), buyer.getCreditCode());
            addBuyerSnapshotChange(changeDetails, "签订地点", contract.getBuyerAddress(), buyer.getAddress());
            addBuyerSnapshotChange(changeDetails, "需方邮编", contract.getPostCode(), buyer.getPostCode());
            addBuyerSnapshotChange(changeDetails, "需方电话", contract.getBuyerPhone(), buyer.getPhone());
            addBuyerSnapshotChange(changeDetails, "需方传真", contract.getFax(), buyer.getFax());
            contract.setBuyerCompanyId(buyer.getId());
            contract.setBuyerCompanyName(buyer.getCompanyName());
            contract.setBuyerCompanyCode(buyer.getCreditCode());
            contract.setBuyerAddress(buyer.getAddress());
            contract.setPostCode(buyer.getPostCode());
            contract.setBuyerPhone(buyer.getPhone());
            contract.setFax(buyer.getFax());
        } else {
        // 兼容既有调用方直接更新需方快照字段的请求。
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
        }

        boolean mainChanged = !changeDetails.isEmpty();
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

        int changesBeforeDates = changeDetails.size();
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
                log.info("签署日期格式错误，忽略：{}", request.getContractDate());
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
                log.info("交货日期格式错误，忽略：{}", request.getDeliveryDate());
            }
        }
        return mainChanged || changeDetails.size() > changesBeforeDates;
    }

    /**
     * 合同类型切换后，把模板换成该类型的启用默认模板。
     *
     * <p>该类型没配默认模板时不阻断修改：保留原模板并把「未切换模板」写进变更明细与日志，
     * 避免用户只是想改个类型标签却被卡住，同时留下可追查的记录。
     */
    private void applyDefaultTemplateForType(Contract contract, List<String> changeDetails) {
        String typeDesc = ContractType.descOf(contract.getContractType());
        try {
            Long templateId = contractTemplateService.resolveDefaultTemplateId(contract.getContractType());
            if (templateId != null && !templateId.equals(contract.getTemplateId())) {
                log.info("合同类型变更，模板随类型切换：contractId={}, contractType={}, templateId={} → {}",
                        contract.getId(), contract.getContractType(), contract.getTemplateId(), templateId);
                changeDetails.add("合同模板：切换为类型【" + typeDesc + "】的默认模板");
                contract.setTemplateId(templateId);
            }
        } catch (BusinessException ex) {
            log.info("合同类型变更但未找到启用默认模板，保留原模板：contractId={}, contractType={}, reason={}",
                    contract.getId(), contract.getContractType(), ex.getMessage());
            changeDetails.add("合同模板：类型【" + typeDesc + "】未配置启用默认模板，仍使用原模板");
        }
    }

    private void addBuyerSnapshotChange(List<String> changes, String label, String oldValue, String newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.add(label + "：" + oldValue + " → " + newValue);
        }
    }

    /**
     * 应用明细修改。
     */
    private List<ContractItem> applyItemUpdates(Contract contract, ContractUpdateRequest request, List<String> changeDetails) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return List.of();
        }

        List<ContractItem> changedItems = new ArrayList<>();
        String contractNo = contract.getContractNo();
        List<ContractItem> contractItems = contract.getItems();

        // 构建明细ID到明细对象的映射
        Map<Long, ContractItem> itemMap = contractItems.stream()
                .collect(Collectors.toMap(ContractItem::getId, item -> item));

        log.info("开始修改合同明细：contractNo={}, requestItemCount={}", contractNo, request.getItems().size());

        for (ContractItemUpdateRequest itemRequest : request.getItems()) {
            Long itemId = itemRequest.getId();
            if (itemId == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "明细缺少ID，无法定位要修改的明细：contractNo=" + contractNo);
            }
            ContractItem item = itemMap.get(itemId);
            if (item == null) {
                // 不能静默跳过：请求里的明细必须属于该合同，否则调用方以为改成功、实际没改任何数据
                log.info("明细不存在或不属于该合同，拒绝修改：contractNo={}, itemId={}", contractNo, itemId);
                throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                        "合同明细不存在或不属于该合同：itemId=" + itemId + "，contractNo=" + contractNo);
            }

            // 统一校验每条明细
            validateItemUpdate(itemRequest);

            int changesBefore = changeDetails.size();
            String itemDesc = "明细[" + item.getProductName() + "]";

            // 修改数量
            if (itemRequest.getQuantity() != null
                    && !itemRequest.getQuantity().equals(item.getQuantity())) {
                log.info("修改明细数量：itemId={}, old={}, new={}",
                        itemId, item.getQuantity(), itemRequest.getQuantity());
                changeDetails.add(itemDesc + "数量：" + item.getQuantity() + " → " + itemRequest.getQuantity());
                item.setQuantity(itemRequest.getQuantity());
            }

            // 修改单价（存量数据单价可能为空，空即视为有变化）
            if (itemRequest.getUnitPrice() != null
                    && (item.getUnitPrice() == null
                    || itemRequest.getUnitPrice().compareTo(item.getUnitPrice()) != 0)) {
                log.info("修改明细单价：itemId={}, old={}, new={}",
                        itemId, item.getUnitPrice(), itemRequest.getUnitPrice());
                changeDetails.add(itemDesc + "单价：" + item.getUnitPrice() + " → " + itemRequest.getUnitPrice());
                item.setUnitPrice(itemRequest.getUnitPrice());
            }

            // 重新计算明细金额（如果数量或单价改变了）
            if (changeDetails.size() > changesBefore) {
                if (item.getQuantity() == null || item.getUnitPrice() == null) {
                    // 存量数据缺数量或单价时无法重算金额：不猜值，保持原金额不动，只记录
                    log.info("明细缺少数量或单价，跳过金额重算：contractNo={}, itemId={}, quantity={}, unitPrice={}",
                            contractNo, itemId, item.getQuantity(), item.getUnitPrice());
                } else {
                    BigDecimal newAmount = new BigDecimal(item.getQuantity())
                            .multiply(item.getUnitPrice());
                    if (item.getAmount() == null || newAmount.compareTo(item.getAmount()) != 0) {
                        log.info("重新计算明细金额：itemId={}, old={}, new={}",
                                itemId, item.getAmount(), newAmount);
                        changeDetails.add(itemDesc + "金额：" + item.getAmount() + " → " + newAmount);
                        item.setAmount(newAmount);
                    }
                }
                changedItems.add(item);
            }

        }

        log.info("合同明细修改完成：contractNo={}, modifiedItemCount={}", contractNo, changedItems.size());
        return changedItems;
    }

    /**
     * 合同明细修改校验。
     *
     * <p>只允许改数量和不含税单价；金额一律由系统按 数量 × 单价 重算，不接受调用方传入；
     * 交货日期与备注暂不支持修改，传了就明确报错而不是悄悄忽略。</p>
     */
    private void validateItemUpdate(ContractItemUpdateRequest itemRequest) {
        if (itemRequest.getQuantity() != null && itemRequest.getQuantity() <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "明细数量必须大于0");
        }
        if (itemRequest.getUnitPrice() != null && itemRequest.getUnitPrice().signum() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "明细单价不能小于0");
        }
        if (itemRequest.getAmount() != null || StringUtils.hasText(itemRequest.getDeliveryDate())
                || StringUtils.hasText(itemRequest.getRemark())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "合同明细仅允许修改数量和不含税单价，金额将由系统自动计算");
        }
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

        if (contract.getContractAmount() == null || newContractAmount.compareTo(contract.getContractAmount()) != 0) {
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
