package com.scione.scm.bill.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 合同与领星数据按字段比对、按用户选择应用的传输对象。 */
public final class ContractLingxingSyncDTO {
    private ContractLingxingSyncDTO() {
    }

    public record Difference(String fieldKey, String fieldLabel, String category,
                             String contractValue, String lingxingValue, boolean defaultSelected) {
    }

    public record CompareResponse(Long contractId, String contractNo, LocalDateTime lingxingQueryTime,
                                  List<Difference> differences) {
    }

    public record ApplyRequest(List<String> selectedFieldKeys) {
    }

    public record ApplyResponse(Long contractId, String contractNo, int updatedCount,
                                List<String> appliedFieldKeys) {
    }
}
