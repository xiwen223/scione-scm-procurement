package com.scione.scm.bill.application;

import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.posync.PoSyncRepository;
import com.scione.scm.bill.application.port.LingxingPurchaseOrderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 将领星采购单 status=9 同步为合同完成。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractCompletionSyncService {
    private final ContractRepository contractRepository;
    private final LingxingPurchaseOrderClient lingxingPurchaseOrderClient;
    public SyncResult sync() {
        int scanned = 0, completed = 0, failed = 0;
        for (Contract contract : contractRepository.findExecutingContracts()) {
            scanned++;
            if (contract.getPurchaseOrderNo() == null || contract.getPurchaseOrderNo().isBlank()) {
                log.warn("履行中合同缺少采购单号，跳过领星完成同步：contractId={}, contractNo={}",
                        contract.getId(), contract.getContractNo());
                continue;
            }
            try {
                // 每次任务都按合同 PO 单号实时查询领星，不依赖 po_sync_record 的同步窗口。
                var po = lingxingPurchaseOrderClient.findByOrderNo(contract.getPurchaseOrderNo()).orElse(null);
                if (po == null) {
                    log.warn("领星未查询到履行中合同采购单：contractNo={}, purchaseOrderNo={}",
                            contract.getContractNo(), contract.getPurchaseOrderNo());
                    continue;
                }
                log.info("领星完成状态核验：contractNo={}, purchaseOrderNo={}, poStatus={}, poStatusText={}",
                        contract.getContractNo(), contract.getPurchaseOrderNo(), po.status(), po.statusText());
                if (po.status() == null || po.status() != 9) continue;
                contractRepository.markCompleted(contract.getId());
                String text = po.statusText() == null ? "" : po.statusText();
                contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(contract.getId(), contract.getContractNo(), Contract.SYSTEM_OPERATOR, Contract.SYSTEM_OPERATOR, "领星采购单完成同步", "采购单状态=9(" + text + ")；合同状态：履行中 → 完成；同步来源=领星实时查询"));
                completed++;
            } catch (Exception ex) {
                failed++;
                log.error("领星完成状态同步失败：contractNo={}, purchaseOrderNo={}",
                        contract.getContractNo(), contract.getPurchaseOrderNo(), ex);
            }
        }
        log.info("领星采购单完成状态同步结束：scanned={}, completed={}, failed={}", scanned, completed, failed);
        return new SyncResult(scanned, completed, failed);
    }
    public record SyncResult(int scanned, int completed, int failed) { }
}
