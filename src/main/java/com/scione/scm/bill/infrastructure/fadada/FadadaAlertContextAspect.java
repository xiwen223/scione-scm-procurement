package com.scione.scm.bill.infrastructure.fadada;

import com.scione.scm.bill.domain.company.BuyerCompanyRepository;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 拦截带 FadadaAlertContext 注解的业务入口，统一准备告警所需的公司和合同信息。
 * <p>负责上下文生命周期；响应码判断及告警发送仍由 FadadaOpenApiClient 完成。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class FadadaAlertContextAspect {
    private final ContractRepository contractRepository;
    private final BuyerCompanyRepository companyRepository;
    private final FadadaAlertContextHolder contextHolder;

    /**
     * 在业务方法执行期间设置上下文，结束或抛出异常时恢复、清理。
     * <p>按 ID 查询业务信息；参数已是合同对象时直接复用，避免批量下载重复查询。
     * 上下文查询失败只记录日志，继续执行原业务方法。</p>
     *
     * @param joinPoint 被拦截的业务方法及其参数
     * @param annotation 业务类型和参数位置配置
     * @return 原业务方法的返回值
     * @throws Throwable 原业务方法抛出的异常，保持原样向上传递
     */
    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint joinPoint, FadadaAlertContext annotation) throws Throwable {
        FadadaAlertContextHolder.Context context = null;
        try {
            Object argument = joinPoint.getArgs()[annotation.argumentIndex()];
            if (annotation.value() == FadadaAlertContext.Type.CONTRACT) {
                Contract contract = argument instanceof Contract existing ? existing
                        : contractRepository.findById((Long) argument).orElse(null);
                if (contract != null) {
                    context = new FadadaAlertContextHolder.Context(
                            contract.getBuyerCompanyName(), contract.getContractNo());
                }
            } else {
                context = companyRepository.findById((Long) argument)
                        .map(company -> new FadadaAlertContextHolder.Context(company.getCompanyName(), null))
                        .orElse(null);
            }
        } catch (RuntimeException exception) {
            // 补充告警信息失败不改变业务方法的正常执行或原有异常。
            log.info("获取法大大告警上下文失败：method={}, exception={}",
                    joinPoint.getSignature().toShortString(), exception.getClass().getSimpleName());
        }
        try (FadadaAlertContextHolder.Scope ignored = contextHolder.open(context)) {
            return joinPoint.proceed();
        }
    }
}
