package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.ContractCreateRequest;
import com.scione.scm.bill.application.dto.ContractCreateResponse;
import com.scione.scm.bill.application.port.ContractFileStore;
import com.scione.scm.bill.application.port.ContractTemplateService;
import com.scione.scm.bill.domain.company.BuyerCompany;
import com.scione.scm.bill.domain.company.BuyerCompanyRepository;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 合同自动创建应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractAutoCreateService {

    private final PoSyncRepository poSyncRepository;
    private final BuyerCompanyRepository buyerCompanyRepository;
    private final ContractRepository contractRepository;
    private final ContractTemplateService contractTemplateService;
    private final ContractFileStore contractFileStore;

    /**
     * 自动创建合同（为指定 PO 列表创建合同）。
     *
     * @param purchaseOrderNos 待建合同的采购单号列表（null 或空表示扫描全表）
     * @return 汇总结果
     */
    public AutoCreateResult autoCreate(List<String> purchaseOrderNos) {
        log.info("合同自动创建开始，指定 PO 数={}", purchaseOrderNos == null ? "全表" : purchaseOrderNos.size());

        // 1. 查询默认需方公司
        Optional<BuyerCompany> buyerOpt = buyerCompanyRepository.findDefault();
        if (buyerOpt.isEmpty()) {
            log.error("无默认需方公司（priority=1 且 is_active=1），合同自动创建跳过");
            return new AutoCreateResult(0, 0, 0, 0);
        }
        BuyerCompany buyer = buyerOpt.get();

        // 2. 查询待建合同的 PO（按指定列表过滤）
        List<PoSyncRecord> pendingPos = (purchaseOrderNos == null || purchaseOrderNos.isEmpty())
                ? poSyncRepository.findPendingForContract()
                : poSyncRepository.findPendingForContractByOrderNos(purchaseOrderNos);

        if (pendingPos.isEmpty()) {
            log.info("无待建合同的 PO，合同自动创建结束");
            return new AutoCreateResult(0, 0, 0, 0);
        }

        // 3. 逐条处理
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (PoSyncRecord po : pendingPos) {
            String poNo = po.getPurchaseOrderNo();
            try {
                boolean success = processOnePo(po, buyer);
                if (success) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.error("合同创建失败，poNo={}", poNo, ex);
            }
        }

        AutoCreateResult result = new AutoCreateResult(pendingPos.size(), created, skipped, failed);
        log.info("合同自动创建结束：拉取 {}，创建 {}，跳过 {}，失败 {}",
                result.total(), result.created(), result.skipped(), result.failed());
        return result;
    }

    /**
     * 处理一条 PO（加事务：合同创建 + PO 标记原子）。
     *
     * @return true=创建，false=跳过
     */
    @Transactional
    protected boolean processOnePo(PoSyncRecord po, BuyerCompany buyer) {
        String poNo = po.getPurchaseOrderNo();

        // 校验 1：supplier_name 必填
        if (!StringUtils.hasText(po.getSupplierName())) {
            log.warn("跳过建合同（supplier_name 为空）：poNo={}", poNo);
            return false;
        }

        // 校验 2：supplier_phone 必填（签署必需）
        if (!StringUtils.hasText(po.getSupplierPhone())) {
            log.warn("跳过建合同（supplier_phone 为空）：poNo={}", poNo);
            return false;
        }

        // 校验 3：contact_person 必填
        if (!StringUtils.hasText(po.getContactPerson())) {
            log.warn("跳过建合同（contact_person 为空）：poNo={}", poNo);
            return false;
        }

        // 校验 4：必须有明细
        if (po.getItems() == null || po.getItems().isEmpty()) {
            log.warn("跳过建合同（无明细）：poNo={}", poNo);
            return false;
        }

        // 校验 5：需方公司地址必填（用于签订地点）
        if (!StringUtils.hasText(buyer.getAddress())) {
            log.warn("跳过建合同（需方公司地址为空）：poNo={}, buyerCompanyId={}", poNo, buyer.getId());
            return false;
        }

        // 校验 6：唯一性兜底
        if (contractRepository.existsActiveByPurchaseOrderNo(poNo)) {
            log.warn("跳过建合同（已存在非取消合同）：poNo={}", poNo);
            return false;
        }

        // 生成合同编号
        String contractNo = generateContractNo();

        // 创建合同聚合
        Contract contract = Contract.createFromPo(po, buyer, contractNo);

        // 原子保存：合同主表 + 明细 + CREATE 日志
        long contractId = contractRepository.create(contract);

        // 回写 PO 标记
        poSyncRepository.markContractCreated(poNo, contractId);

        log.info("合同创建成功：poNo={}, contractNo={}, contractId={}", poNo, contractNo, contractId);

        // 填充 Excel 模板并上传到 S3
        try {
            log.info("开始生成合同文件：contractNo={}", contractNo);

            // 1. 填充模板，生成 Excel 字节流
            byte[] fileBytes = contractTemplateService.fillTemplate(contract);

            // 2. 上传到 S3，返回访问 URL
            String fileUrl = contractFileStore.store(contractNo, fileBytes, "xlsx");

            // 3. 更新合同表的 contract_pdf_url 字段
            contractRepository.updatePdfUrl(contractId, fileUrl);

            log.info("合同文件生成成功：contractNo={}, url={}", contractNo, fileUrl);

        } catch (Exception ex) {
            // 文件生成失败不影响合同记录（已保存），只记录日志
            log.error("合同文件生成失败（合同记录已创建）：contractNo={}, contractId={}",
                    contractNo, contractId, ex);
            // 不抛异常，允许继续处理下一条 PO
        }

        return true;
    }

    /**
     * 生成合同编号（"HT" + yyyyMMdd + 4位随机 + 去重重试）。
     */
    private String generateContractNo() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int attempts = 0; attempts < 50; attempts++) {
            String contractNo = "HT" + date + ThreadLocalRandom.current().nextInt(1000, 10000);
            if (contractRepository.findByContractNo(contractNo).isEmpty()) {
                return contractNo;
            }
        }
        throw new RuntimeException("合同编号生成失败，50 次重试均冲突");
    }

    /**
     * 手动创建单个合同（指定采购单号）。
     *
     * @param request 创建合同请求，包含采购单号
     * @return 创建结果（包含合同ID、合同编号、文件URL） 响应对象
     */
    public ContractCreateResponse createContract(ContractCreateRequest request) {
        String purchaseOrderNo = request.getPurchaseOrderNo();
        log.info("手动创建合同开始：purchaseOrderNo={}", purchaseOrderNo);
        // 1. 查询默认需方公司
        Optional<BuyerCompany> buyerOpt = buyerCompanyRepository.findDefault();
        if (buyerOpt.isEmpty()) {
            throw new RuntimeException("无默认需方公司（priority=1 且 is_active=1）");
        }
        BuyerCompany buyer = buyerOpt.get();

        // 2. 查询指定的 PO（带明细）
        Optional<PoSyncRecord> poOpt = poSyncRepository.findByPurchaseOrderNo(purchaseOrderNo);
        if (poOpt.isEmpty()) {
            throw new RuntimeException("采购单不存在：purchaseOrderNo=" + purchaseOrderNo);
        }
        PoSyncRecord po = poOpt.get();

        // 3. 校验 PO 状态
        if (po.getPoStatus() == null || po.getPoStatus() != 1) {
            throw new RuntimeException("采购单状态不是待下单（po_status != 1）：purchaseOrderNo=" + purchaseOrderNo);
        }

        // 4. 校验必填字段
        if (!StringUtils.hasText(po.getSupplierName())) {
            throw new RuntimeException("供应商名称为空");
        }
        if (!StringUtils.hasText(po.getSupplierPhone())) {
            throw new RuntimeException("供应商电话为空（签署必需）");
        }
        if (!StringUtils.hasText(po.getContactPerson())) {
            throw new RuntimeException("联系人为空");
        }
        if (po.getItems() == null || po.getItems().isEmpty()) {
            throw new RuntimeException("采购单无明细");
        }
        if (!StringUtils.hasText(buyer.getAddress())) {
            throw new RuntimeException("需方公司地址为空");
        }

        // 5. 唯一性检查
        if (contractRepository.existsActiveByPurchaseOrderNo(purchaseOrderNo)) {
            throw new RuntimeException("该采购单已存在合同（非取消状态）");
        }

        // 6. 生成合同编号
        String contractNo = generateContractNo();

        // 7. 创建合同聚合
        Contract contract = Contract.createFromPo(po, buyer, contractNo);

        // 8. 手动补充字段（覆盖领星和默认数据）
        applyManualOverrides(contract, request);

        // 8. 原子保存：合同主表 + 明细 + CREATE 日志
        long contractId = contractRepository.create(contract);

        // 9. 回写 PO 标记
        poSyncRepository.markContractCreated(purchaseOrderNo, contractId);

        log.info("合同创建成功：poNo={}, contractNo={}, contractId={}", purchaseOrderNo, contractNo, contractId);

        // 10. 填充 Excel 模板并上传到 S3
        String fileUrl = null;
        try {
            log.info("开始生成合同文件：contractNo={}", contractNo);

            // 填充模板，生成 Excel 字节流
            byte[] fileBytes = contractTemplateService.fillTemplate(contract);

            // 上传到 S3，返回访问 URL
            fileUrl = contractFileStore.store(contractNo, fileBytes, "xlsx");

            // 更新合同表的 contract_pdf_url 字段
            contractRepository.updatePdfUrl(contractId, fileUrl);

            log.info("合同文件生成成功：contractNo={}, url={}", contractNo, fileUrl);

        } catch (Exception ex) {
            // 文件生成失败不影响合同记录（已保存），只记录日志
            log.error("合同文件生成失败（合同记录已创建）：contractNo={}, contractId={}",
                    contractNo, contractId, ex);
            fileUrl = null;  // 文件生成失败，URL 为空
        }

        // 11. 返回结果
        return new ContractCreateResponse(
                contractId,
                contractNo,
                purchaseOrderNo,
                fileUrl,
                "合同创建成功"
        );
    }

    /**
     * 应用手动补充的字段（覆盖领星和默认数据）。
     */
    private void applyManualOverrides(Contract contract, ContractCreateRequest request) {
        // 供方信息覆盖
        if (StringUtils.hasText(request.getSupplierName())) {
            contract.setSupplierName(request.getSupplierName());
            log.info("手动覆盖供方名称：{}", request.getSupplierName());
        }
        if (StringUtils.hasText(request.getSupplierAddress())) {
            contract.setSupplierAddress(request.getSupplierAddress());
            log.info("手动覆盖供方地址：{}", request.getSupplierAddress());
        }
        if (StringUtils.hasText(request.getContactPerson())) {
            contract.setContactPerson(request.getContactPerson());
            log.info("手动覆盖联系人：{}", request.getContactPerson());
        }
        if (StringUtils.hasText(request.getSupplierPhone())) {
            contract.setSupplierPhone(request.getSupplierPhone());
            log.info("手动覆盖供方电话：{}", request.getSupplierPhone());
        }

        // 需方信息覆盖
        if (StringUtils.hasText(request.getBuyerCompanyName())) {
            contract.setBuyerCompanyName(request.getBuyerCompanyName());
            log.info("手动覆盖需方名称：{}", request.getBuyerCompanyName());
        }
        if (StringUtils.hasText(request.getBuyerAddress())) {
            contract.setBuyerAddress(request.getBuyerAddress());
            log.info("手动覆盖需方地址：{}", request.getBuyerAddress());
        }
        if (StringUtils.hasText(request.getPostCode())) {
            contract.setPostCode(request.getPostCode());
            log.info("手动覆盖邮编：{}", request.getPostCode());
        }
        if (StringUtils.hasText(request.getBuyerPhone())) {
            contract.setBuyerPhone(request.getBuyerPhone());
            log.info("手动覆盖需方电话：{}", request.getBuyerPhone());
        }
        if (StringUtils.hasText(request.getFax())) {
            contract.setFax(request.getFax());
            log.info("手动覆盖传真：{}", request.getFax());
        }

        // 合同金额覆盖
        if (request.getContractAmount() != null) {
            contract.setContractAmount(request.getContractAmount());
            contract.setOriginalAmount(request.getContractAmount());
            log.info("手动覆盖合同金额：{}", request.getContractAmount());
        }

        // 合同日期覆盖
        if (StringUtils.hasText(request.getContractDate())) {
            try {
                LocalDate date = LocalDate.parse(request.getContractDate());
                contract.setContractDate(date);
                log.info("手动覆盖合同日期：{}", date);
            } catch (Exception ex) {
                log.warn("合同日期格式错误，使用默认值：{}", request.getContractDate());
            }
        }

        // 交货日期覆盖
        if (StringUtils.hasText(request.getDeliveryDate())) {
            try {
                LocalDate date = LocalDate.parse(request.getDeliveryDate());
                contract.setDeliveryDate(date);
                log.info("手动覆盖交货日期：{}", date);
            } catch (Exception ex) {
                log.warn("交货日期格式错误，忽略：{}", request.getDeliveryDate());
            }
        }
    }

    /**
     * 单次自动创建汇总。
     */
    public record AutoCreateResult(int total, int created, int skipped, int failed) {
    }
}