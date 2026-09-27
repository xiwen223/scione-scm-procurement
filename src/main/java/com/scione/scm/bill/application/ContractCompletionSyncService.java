package com.scione.scm.bill.application;

import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.posync.PoSyncRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 将领星采购单 status=9 同步为合同完成。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractCompletionSyncService {
    private final ContractRepository contractRepository;
    private final PoSyncRepository poSyncRepository;
    public SyncResult sync() {
        int scanned = 0, completed = 0;
        for (Contract contract : contractRepository.findExecutingContracts()) {
            scanned++;
            var po = poSyncRepository.findByPurchaseOrderNo(contract.getPurchaseOrderNo()).orElse(null);
            if (po == null || po.getPoStatus() == null || po.getPoStatus() != 9) continue;
            contractRepository.markCompleted(contract.getId());
            String text = po.getPoStatusText() == null ? "" : po.getPoStatusText();
            contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(contract.getId(), contract.getContractNo(), Contract.SYSTEM_OPERATOR, Contract.SYSTEM_OPERATOR, "领星采购单完成同步", "采购单状态=9(" + text + ")；合同状态：履行中 → 完成；同步来源=领星"));
            completed++;
        }
        log.info("领星采购单完成状态同步结束：scanned={}, completed={}", scanned, completed);
        return new SyncResult(scanned, completed);
    }
    public record SyncResult(int scanned, int completed) { }
}
