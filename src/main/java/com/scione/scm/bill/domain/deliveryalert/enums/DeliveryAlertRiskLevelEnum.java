package com.scione.scm.bill.domain.deliveryalert.enums;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/** 跟单预警看板主事项风险等级，落库 delivery_alert_group.risk_level。 */
@Getter
@RequiredArgsConstructor
public enum DeliveryAlertRiskLevelEnum {

    /** 已完成：组内采购单全部交付，risk_hours 记为 -1。 */
    DONE("done", "已完成"),

    /** 正常：risk_hours 小于 240。 */
    NORMAL("normal", "正常"),

    /** 即将超期：risk_hours 在 240 ~ 359 之间。 */
    DUE_SOON("due-soon", "即将超期"),

    /** 超期：risk_hours 大于等于 360。 */
    OVERDUE("overdue", "超期");

    /** 落库值，写入 delivery_alert_group.risk_level（varchar）。 */
    private final String code;

    /** 前端展示文案。 */
    private final String label;

    /** 已完成在 risk_hours 上的取值。 */
    private static final int DONE_HOURS = -1;

    /** 正常与即将超期的分界小时数，与 DeliveryAlertMapper.xml 的 riskLevelFilter 保持一致。 */
    private static final int NORMAL_HOURS = 240;

    /** 即将超期与超期的分界小时数，与 DeliveryAlertMapper.xml 的 riskLevelFilter 保持一致。 */
    private static final int DUE_SOON_HOURS = 360;

    public static DeliveryAlertRiskLevelEnum of(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ResultCode.PARAM_ERROR, "未知的风险等级：" + code));
    }

    /** 按剩余风险小时数换算等级，快照刷新时使用。 */
    public static DeliveryAlertRiskLevelEnum ofRiskHours(int riskHours) {
        if (riskHours == DONE_HOURS) {
            return DONE;
        }
        if (riskHours < NORMAL_HOURS) {
            return NORMAL;
        }
        if (riskHours < DUE_SOON_HOURS) {
            return DUE_SOON;
        }
        return OVERDUE;
    }
}
