package com.scione.scm.bill.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.config.SopReminderProperties;
import com.scione.scm.bill.domain.sop.SopReminderPreview;
import com.scione.scm.bill.domain.sop.SopReminderPreview.RecipientGroup;
import com.scione.scm.bill.domain.sop.SopReminderPreview.Candidate;
import com.scione.scm.bill.domain.sop.SopReminderRepository;
import com.scione.scm.bill.domain.sop.SopReminderRepository.PreparedGroup;
import com.scione.scm.bill.infrastructure.wecom.WeComWebhookClient;
import com.scione.scm.bill.infrastructure.wecom.PackingMailClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 催办发送：预览候选 → 生成待办 → 发企微群消息（@人）→ 写推送记录入库。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SopReminderSendService {

    private final SopReminderService ruleService;//算该催谁
    private final SopReminderProperties properties;//nacos配置
    private final SopReminderRepository repository;//读写代办/推送记录+加锁
    private final WeComWebhookClient webhookClient;//发链接给企微
    private final PackingMailClient packingMailClient;
    private final SopReminderStatusVerifier statusVerifier;
    private final ObjectMapper objectMapper;//上下文->JSON

    /**
     * 免登录外链路径，与前端 app/reminder、app/packing 两个路由一一对应。
     * 前端是静态导出（out/ + nginx），不能有动态路由；token 以查询参数传递。
     * 链接直接写 .html，nginx 当普通文件返回，不需要 try_files 配置。
     */
    private static final String REMINDER_LINK_PATH = "/reminder.html?t=";
    private static final String PACKING_LINK_PATH = "/packing.html?t=";

    /**
     * 一个实际收件人的一次汇总消息，可包含多个节点和多个业务责任人的待办。
     *
     * @param recipientUid   业务责任人的领星UID；按角色或姓名分组的节点为0
     * @param recipientName  业务责任人显示名：优先配置里的姓名，其次预览分组名，最后分组键
     * @param assigneeUserid 实际接收消息的企微userid，可能被fallback-person兜底改写
     * @param todos          本次消息包含的待办条数，不是明细本身
     * @param sent           企微返回errcode=0才为true
     * @param message        实际发出的正文：催办提示+带token的外链
     * @param error          失败原因，成功为null
     */
    public record GroupResult(long recipientUid, String recipientName, String assigneeUserid,
                              int todos, boolean sent, String message, String error) {
    }

    /**
     * 一轮发送汇总，details按实际企微收件人划分。
     *
     * @param nodeCode        本次执行的节点编码，多个节点用逗号分隔
     * @param candidateGroups 预览分组数，含因缺少账号被跳过的组
     * @param todos           本轮累计待办条数
     * @param pushes          本轮实际请求企微的次数，含失败次数
     * @param skipped         被跳过的责任人及原因
     * @param unresolvedCount 发送结果未知（UNKNOWN）的批次数，用于轮次级补发判断
     * @param details         每个责任人的发送结果
     */
    public record SendResult(String nodeCode,
                             int candidateGroups, int todos, int pushes, List<String> skipped,
                             int unresolvedCount, List<GroupResult> details) {
    }

    public SendResult send(String nodeCode, LocalDateTime scheduledAt) {
        if (!ruleService.supportsNode(nodeCode)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "尚未实现的催办节点：" + nodeCode);
        }
        if (scheduledAt == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "计划催办时间不能为空");
        }
        LocalDateTime slot = scheduledAt.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        return sendNodes(List.of(nodeCode), slot, null);
    }

    /**
     * 手动触发一次，触发时机与定时任务无关，其余行为完全一致：同样校验节点enabled、同样关单。
     * businessSn为null时发送该节点全部候选，传了就只发这一张（用于定位单条问题）。
     * 受 send.test-enabled 开关保护，上线前必须置false。
     */
    public SendResult sendForTest(String nodeCode, String businessSn) {
        return sendForTest(nodeCode,businessSn,false);
    }

    /** 人工验收可明确要求发送当前装箱完整清单；自动轮询仍仅由新单触发。 */
    public SendResult sendPackingForTest(boolean force) {
        return sendForTest("PACKING_RELATE",null,force);
    }

    private SendResult sendForTest(String nodeCode,String businessSn,boolean forcePacking) {
        if (!Boolean.TRUE.equals(properties.getSend().getTestEnabled())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "测试发送未开启，请先配置 sop.reminder.send.test-enabled=true");
        }
        if (!ruleService.supportsNode(nodeCode)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "尚未实现的催办节点：" + nodeCode);
        }
        String sn = hasText(businessSn) ? businessSn.trim() : null;
        LocalDateTime slot = now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return dispatch(List.of(nodeCode),slot,sn,forcePacking);
    }

    /** 手动汇总已开启的普通节点，排除装箱；调用会真实发送。force=true 时不看历史去重。 */
    public SendResult sendStandardForTest(boolean force) {
        if (!Boolean.TRUE.equals(properties.getSend().getTestEnabled())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "测试发送未开启");
        }
        List<String> codes = properties.getNodes().entrySet().stream()
                .filter(e -> !"PACKING_RELATE".equals(e.getKey()) && Boolean.TRUE.equals(e.getValue().getEnabled()))
                .map(Map.Entry::getKey).toList();
        return dispatch(codes, now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), null, force);
    }

    /** 普通节点按实际企微用户汇总；关联货件独立成消息并使用专属页面。 */
    public SendResult sendNodes(List<String> nodeCodes, LocalDateTime scheduledAt, String onlyBusinessSn) {
        return dispatch(nodeCodes,scheduledAt,onlyBusinessSn,false);
    }

    private SendResult dispatch(List<String> nodeCodes,LocalDateTime scheduledAt,String onlyBusinessSn,boolean force) {
        if (scheduledAt == null) throw new BusinessException(ResultCode.PARAM_ERROR, "计划催办时间不能为空");
        List<String> codes = nodeCodes.stream().distinct().toList();
        if (codes.contains("PACKING_RELATE") && codes.size() > 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "普通催办与装箱催办必须分别调用");
        }
        if (codes.contains("PACKING_RELATE") && !packingMailClient.configured()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "关联货件SMTP邮件配置未完成，请配置发件邮箱、授权码和TLS服务器参数");
        }
        return repository.withDispatchLock(codes.contains("PACKING_RELATE"), () -> {
            reconcileInterruptedSends(codes.contains("PACKING_RELATE"));
            // 取得对应锁后才查询候选；装箱每次直接查询领星，不等待Python同步，也不复用旧候选。
            for (String code : codes) requireEnabled(code);
            long started = System.nanoTime();
            log.info("催办已取得发送锁，开始查询候选：nodes={}", codes);
            Map<String, SopReminderPreview> previews = ruleService.previewNodes(codes);
            log.info("催办候选查询完成：nodes={}, elapsedMs={}", codes,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            Map<String, PendingMessage> pending = new LinkedHashMap<>();
            List<String> skipped = new ArrayList<>();
            int groups = 0;
            for (String code : codes) {
                PreparedNode prepared = prepare(code, scheduledAt, onlyBusinessSn, previews.get(code), pending, force);
                groups += prepared.candidateGroups();
                skipped.addAll(prepared.skipped());
            }
            List<GroupResult> details = new ArrayList<>();
            int unresolved = 0;
            for (PendingMessage message : pending.values()) {
                // 等待其他节点期间可能被关停，发送前再次检查整条消息。
                message.nodes.forEach(this::requireEnabled);
                boolean packing=message.nodes.contains("PACKING_RELATE");
                if(packing && force) {
                    message.packingTriggerIds.addAll(message.stages.keySet());
                    log.info("人工验收明确要求发送关联货件完整清单：userid={}, todos={}",message.person.getUserid(),message.stages.size());
                }
                if(packing && message.packingTriggerIds.isEmpty()) {
                    skipped.add(message.person.getName()+"：当前有"+message.stages.size()+"条关联货件待办，无可触发发送的新增单据（或新增单据结果待确认/已有重试计划）");
                    log.info("关联货件本轮不发送：userid={}, currentTodos={}, newTodos=0",message.person.getUserid(),message.stages.size());
                    continue;
                }
                verifyPending(message, skipped);
                if (message.stages.isEmpty()) continue;
                if(packing && message.packingTriggerIds.stream().noneMatch(message.stages::containsKey)) {
                    skipped.add(message.person.getName()+"：新增单据复核后已完成或状态不明，本轮不发送邮件");
                    log.info("关联货件新增单据复核后不可推送：userid={}",message.person.getUserid());
                    continue;
                }
                // 第N次提醒：该收件人名下有发送结果未知的记录时，正文/主题标注重新发送的序号。
                int attemptNo = message.unknownCount + 1;
                // 每条消息独立访问令牌，页面按该批次明细读取，避免其他时点的节点混入。
                String viewToken = java.util.UUID.randomUUID().toString().replace("-", "");
                String body = packing ? properties.getSend().getFrontBaseUrl() + PACKING_LINK_PATH + viewToken
                        : compose(viewToken, properties.getSend().getFrontBaseUrl(), attemptNo);
                PushContext context = new PushContext(String.join(",", message.nodes), message.person.getUserid(), body,
                        packing ? List.of() : List.of(message.person.getMobile()), 1,
                        now().plus(properties.getSend().getRetryMaxAge()).toString(), null,
                        packing ? "EMAIL" : "WEBHOOK", packing ? message.person.getEmail() : null,
                        packing ? PackingMailClient.subject(attemptNo) : null, viewToken);
                List<Long> todoIds = new ArrayList<>(message.stages.keySet());
                long batchId = repository.startPush(message.person.getUserid(), todoIds,
                        new ArrayList<>(message.stages.values()), scheduledAt, json(context));
                WeComWebhookClient.SendOutcome outcome = deliver(batchId, context);
                boolean unknown = !outcome.success()
                        && outcome.state() == WeComWebhookClient.SendState.UNKNOWN;
                if (unknown) unresolved++;
                String error = outcome.success() ? null : unknown
                        ? "发送结果未知，将在补发轮次重试（最多3次）" : "本次未发送成功，已按重试资格记录";
                details.add(new GroupResult(0, message.person.getName(), message.person.getUserid(),
                        message.stages.size(), outcome.success(), body, error));
            }
            return new SendResult(String.join(",", codes), groups, details.stream().mapToInt(GroupResult::todos).sum(),
                    details.size(), skipped, unresolved, details);
        });
    }

    private static final class PendingMessage {
        final SopReminderProperties.Person person;
        final String token;
        final Set<String> nodes = new java.util.LinkedHashSet<>();
        final Map<Long, Integer> stages = new LinkedHashMap<>();
        /** 新单只负责触发；stages保留该收件人的完整关联货件清单。 */
        final Set<Long> packingTriggerIds=new java.util.LinkedHashSet<>();
        final Map<Long, Candidate> candidates = new LinkedHashMap<>();
        final Map<Long, String> todoNodes = new LinkedHashMap<>();
        /** 该收件人本轮待发单据里历史上 UNKNOWN 的最大次数；0 表示首次发送。 */
        int unknownCount = 0;
        PendingMessage(SopReminderProperties.Person person, String token) { this.person = person; this.token = token; }
    }

    private record PreparedNode(int candidateGroups, List<String> skipped) { }

    private void verifyPending(PendingMessage message, List<String> skipped) {
        Map<String,List<Candidate>> expected=new LinkedHashMap<>();
        message.candidates.forEach((id,c)->expected.computeIfAbsent(message.todoNodes.get(id),ignored->new ArrayList<>()).add(c));
        long started=System.nanoTime();
        log.info("发送前开始实时状态复核：nodes={}, todos={}",message.nodes,message.stages.size());
        var states=statusVerifier.verify(message.person.getUserid(),expected);
        Map<String,List<Long>> completed=new LinkedHashMap<>();
        for(Long id:new ArrayList<>(message.stages.keySet())) {
            String code=message.todoNodes.get(id);Candidate candidate=message.candidates.get(id);
            var decision=states.getOrDefault(SopReminderStatusVerifier.Key.of(code,candidate),SopReminderStatusVerifier.Decision.UNKNOWN);
            if(decision==SopReminderStatusVerifier.Decision.ELIGIBLE)continue;
            message.stages.remove(id);
            if(decision==SopReminderStatusVerifier.Decision.EXITED) {
                completed.computeIfAbsent(code,ignored->new ArrayList<>()).add(id);
                log.info("发送前单据已完成或退出节点，取消本次推送：node={}, businessSn={}",code,candidate.businessSn());
            } else {
                skipped.add(code+"/"+candidate.businessSn()+"：最新状态无法确认，本次未发送");
                log.error("发送前最新状态无法确认，本次不发送：node={}, businessSn={}",code,candidate.businessSn());
            }
        }
        completed.forEach((code,ids)->repository.closeVerifiedTodos(code,message.person.getUserid(),ids));
        message.nodes.removeIf(code->message.stages.keySet().stream().noneMatch(id->code.equals(message.todoNodes.get(id))));
        log.info("发送前实时状态复核完成：remainingTodos={}, elapsedMs={}",message.stages.size(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
    }

    /** 实际收件人相同的业务分组只做一次事务和历史查询，不改变人员映射和消息范围。 */
    private List<RecipientGroup> mergeActualRecipients(List<RecipientGroup> groups, boolean packing) {
        Map<String, RecipientGroup> merged = new LinkedHashMap<>();
        Map<String, String> mobiles = new LinkedHashMap<>();
        for (RecipientGroup group : groups) {
            SopReminderProperties.Person person = personFor(group);
            if (recipientProblem(person, packing) != null) {
                merged.put("invalid:" + merged.size(), group);
                continue;
            }
            String key = "person:" + person.getUserid();
            String address = packing ? person.getEmail() : person.getMobile();
            if (mobiles.containsKey(key) && !java.util.Objects.equals(mobiles.get(key), address)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "同一接收账号配置了不同联系方式，停止本轮发送：" + person.getUserid());
            }
            mobiles.put(key, address);
            RecipientGroup previous = merged.get(key);
            if (previous == null) merged.put(key, group);
            else {
                List<Candidate> rows = new ArrayList<>(previous.candidates()); rows.addAll(group.candidates());
                merged.put(key, new RecipientGroup(previous.recipientUid(), previous.recipientName(),
                        rows.stream().distinct().toList(), previous.recipientKey()));
            }
        }
        return new ArrayList<>(merged.values());
    }

    private PreparedNode prepare(String nodeCode, LocalDateTime scheduledAt, String onlyBusinessSn,
                               SopReminderPreview preview, Map<String, PendingMessage> pending, boolean force) {
        // 定时候选可能在等锁期间被关闭，真正查询和发送前校验开关；两条链路同一规则。
        requireEnabled(nodeCode);
        SopReminderProperties.Send send = properties.getSend();
        boolean packing = "PACKING_RELATE".equals(nodeCode);
        if (!channelConfigured(packing) || !hasText(send.getFrontBaseUrl())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "缺少当前节点发送渠道及frontBaseUrl配置");
        }
        // 重试参数只与配置有关，进入循环前校验一次，配错直接停。
        validateRetryPolicy();
        List<String> skipped = new ArrayList<>();
        List<Long> activeIds = new ArrayList<>();
        List<String> uncertainSns=new ArrayList<>(preview.excluded().stream().map(SopReminderPreview.Excluded::businessSn).toList());
        if ("RF_CREATE".equals(nodeCode)) {
            List<String> missingStarts = preview.excluded().stream()
                    .filter(row -> row.reason().equals("节点起算时间缺失：没有明确进入待到货的日志"))
                    .map(SopReminderPreview.Excluded::businessSn).distinct().toList();
            repository.cancelMissingRfStartTodos(missingStarts);
            // 请款池在PO创建时就有记录，PO还没到货属正常情况：只跳过，不当作异常。
            missingStarts.forEach(sn -> log.info("RF_CREATE本期不催办：businessSn={}, reason=尚未进入待到货，起算时间未到", sn));
        }
        List<SopReminderRepository.SkippedRecipient> missingRecipients = new ArrayList<>();
        for (SopReminderPreview.Excluded excluded : preview.excluded()) {
            if ((onlyBusinessSn == null || onlyBusinessSn.equals(excluded.businessSn()))
                    && (excluded.reason().contains("采购员") || excluded.reason().contains("创建人")
                    || excluded.reason().contains("跟单员") || excluded.reason().contains("审批责任人"))) {
                missingRecipients.add(new SopReminderRepository.SkippedRecipient(excluded.businessSn(), null,
                        "SOURCE_RECIPIENT_UNKNOWN", excluded.reason(), "", 0));
                skipped.add(excluded.businessSn() + "：" + excluded.reason());
                log.error("催办未发送：node={}, businessSn={}, reason={}", nodeCode, excluded.businessSn(), excluded.reason());
            }
        }

        List<RecipientGroup> actualGroups = mergeActualRecipients(preview.groups(), packing);
        long prepareStarted = System.nanoTime();
        log.info("催办待办处理开始：node={}, businessGroups={}, actualGroups={}, candidates={}",
                nodeCode, preview.groups().size(), actualGroups.size(), preview.candidateCount());
        for (RecipientGroup group : actualGroups) {
            List<Candidate> groupCandidates = group.candidates();
            if (groupCandidates.isEmpty()) continue;
            SopReminderProperties.Person person = personFor(group);
            // 普通节点校验手机号，关联货件校验个人邮箱。
            String problem = recipientProblem(person, packing);
            if (problem != null) {
                skipped.add(recipientLabel(group, person) + problem);
                groupCandidates.forEach(candidate->uncertainSns.add(candidate.businessSn()));
                for (Candidate candidate : groupCandidates) {
                    if (onlyBusinessSn == null || onlyBusinessSn.equals(candidate.businessSn())) {
                        missingRecipients.add(new SopReminderRepository.SkippedRecipient(candidate.businessSn(), group.recipientKey(),
                                "RECIPIENT_MAPPING_INVALID", problem, candidate.itemKey(), candidate.stage()));
                    }
                }
                log.error("催办未发送：node={}, receiverKey={}, candidates={}, reason={}",
                        nodeCode, group.recipientKey(), groupCandidates.size(), problem);
                continue;
            }
            // 全量候选都建待办：关单要按本轮完整候选判断，只发指定单号时也不能少建。
            PreparedGroup prepared = repository.prepareGroup(nodeCode, person.getUserid(), groupCandidates);
            activeIds.addAll(prepared.todoIds());
            List<SopReminderPreview.Candidate> candidates=new ArrayList<>();
            List<Long> deliverIds=new ArrayList<>();List<Integer> stages=new ArrayList<>();
            boolean capped="GOODS_READY".equals(nodeCode);
            // force（人工验收接口）跳过历史去重：每个候选都发，其余流程一律照旧。
            List<SopReminderRepository.DispatchDecision> decisions=(packing||force)?List.of():repository.dispatchDecisions(
                    prepared.todoIds(),prepared.stages(),scheduledAt,capped);
            Set<Long> newPackingIds=packing&&!force?new java.util.LinkedHashSet<>(repository.newPackingTodoIds(prepared.todoIds(),scheduledAt)):Set.of();
            if(capped&&!force) repository.suppressGoodsReadyTodos(person.getUserid(),
                    java.util.stream.IntStream.range(0,decisions.size()).filter(i->decisions.get(i).hideFromPage())
                            .mapToObj(prepared.todoIds()::get).toList());
            // 该收件人本轮待发单据里，历史上发送结果未知的最大次数，用于第N次提醒标注。
            int unknownCount = 0;
            for (int i=0;i<groupCandidates.size();i++) {
                SopReminderPreview.Candidate candidate=groupCandidates.get(i);
                // 指定单号时只发这一张，其余候选只建待办不发送。
                if (onlyBusinessSn != null && !onlyBusinessSn.equals(candidate.businessSn())) continue;
                if (packing || force || decisions.get(i).sendable()) {
                    candidates.add(candidate);stages.add(candidate.stage());
                    deliverIds.add(prepared.todoIds().get(i));
                    if (!packing && !force) unknownCount=Math.max(unknownCount,decisions.get(i).unknownCount());
                } else {
                    String reason=decisions.get(i).reason();
                    skipped.add(nodeCode+"/"+candidate.businessSn()+"："+reason);
                    log.info("催办本条不发送：node={}, businessSn={}, reason={}",nodeCode,candidate.businessSn(),reason);
                }
            }
            if (candidates.isEmpty()) continue;
            // 装箱关联货件独立发送，其他节点仍按实际收件人汇总。
            String messageKey = person.getUserid() + ("PACKING_RELATE".equals(nodeCode) ? "|packing" : "|standard");
            PendingMessage message = pending.computeIfAbsent(messageKey,
                    ignored -> new PendingMessage(person, prepared.token()));
            if (!java.util.Objects.equals(packing ? message.person.getEmail() : message.person.getMobile(),
                        packing ? person.getEmail() : person.getMobile())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "同一接收账号配置了不同联系方式，停止本轮发送：" + person.getUserid());
            }
            message.nodes.add(nodeCode);
            // 同一人多节点汇总时取最大次数：只要有单子反复结果未知，整封邮件都按第N次提醒标注。
            message.unknownCount=Math.max(message.unknownCount,unknownCount);
            for (int i = 0; i < deliverIds.size(); i++) {
                long id=deliverIds.get(i);message.stages.put(id,stages.get(i));
                if(packing && (force || newPackingIds.contains(id))) message.packingTriggerIds.add(id);
                message.candidates.put(id,candidates.get(i));message.todoNodes.put(id,nodeCode);
            }
        }
        // 与定时任务保持一致：本轮不在候选里的待办关闭；测试链路也关，才能验证定时任务效果。
        repository.recordSkippedRecipients(nodeCode, scheduledAt, missingRecipients);
        repository.closeAbsentTodos(nodeCode,activeIds,uncertainSns.stream().distinct().toList());
        log.info("催办待办处理完成：node={}, activeTodos={}, elapsedMs={}", nodeCode, activeIds.size(),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - prepareStarted));
        return new PreparedNode(preview.groups().size(), skipped);
    }

    /** 展示名：优先配置里的人名，其次预览分组名，最后退回分组键。 */
    private static String recipientName(RecipientGroup group, SopReminderProperties.Person person) {
        if (person != null && hasText(person.getName())) return person.getName();
        return hasText(group.recipientName()) ? group.recipientName() : group.recipientKey();
    }

    /** 跳过提示的标签：名字（分组键），两者相同时只显示一次，保证能对上Nacos的key。 */
    private static String recipientLabel(RecipientGroup group, SopReminderProperties.Person person) {
        String name = recipientName(group, person);
        return name.equals(group.recipientKey()) ? name : name + "（" + group.recipientKey() + "）";
    }

    private void requireEnabled(String nodeCode) {
        SopReminderProperties.Node node = properties.getNodes().get(nodeCode);
        if (node == null || !Boolean.TRUE.equals(node.getEnabled())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该催办节点未开启");
        }
    }

    /** 普通节点使用群机器人，关联货件使用独立SMTP渠道。 */
    private boolean channelConfigured(boolean packing) {
        return packing ? packingMailClient.configured() : hasText(properties.getSend().getWebhookKey());
    }

    private SopReminderProperties.Person personFor(RecipientGroup group) {
        return properties.resolvePerson(group.recipientKey(),group.recipientUid());
    }

    /** 收件人不可用的原因；返回null表示可以发送。群消息必须有手机号，否则@不到人。 */
    private static String recipientProblem(SopReminderProperties.Person person, boolean packing) {
        if (person == null || !hasText(person.getUserid())) return "未配置企微账号";
        if ("@all".equals(person.getUserid()) || person.getUserid().contains("|")) return "企微账号无效";
        if (packing) return PackingMailClient.validAddress(person.getEmail()) ? null : "未配置有效的个人邮箱";
        if (!hasText(person.getMobile())) return "未配置手机号，无法@到本人";
        return null;
    }

    /** 不保存webhook key；补偿使用当前Nacos凭据，正文和尝试次数保存在原批次JSON中。 */
    public record PushContext(String nodeCode, String userid, String message, List<String> mobiles,
                              int attempts, String expiresAt, WeComWebhookClient.SendOutcome outcome,
                              String channel, String recipientEmail, String subject, String viewToken) {
        public PushContext(String nodeCode, String userid, String message, List<String> mobiles,
                           int attempts, String expiresAt, WeComWebhookClient.SendOutcome outcome,
                           String channel, String recipientEmail, String subject) {
            this(nodeCode, userid, message, mobiles, attempts, expiresAt, outcome, channel, recipientEmail, subject, null);
        }
        public PushContext(String nodeCode, String userid, String message, List<String> mobiles,
                           int attempts, String expiresAt, WeComWebhookClient.SendOutcome outcome) {
            this(nodeCode, userid, message, mobiles, attempts, expiresAt, outcome, null, null, null);
        }
    }

    private WeComWebhookClient.SendOutcome deliver(long batchId, PushContext context) {
        SopReminderProperties.Send send=properties.getSend();
        boolean packing = "PACKING_RELATE".equals(context.nodeCode());
        WeComWebhookClient.SendOutcome outcome;
        log.info("催办渠道发送开始：batchId={}, nodes={}, userid={}, channel={}, attempt={}",
                batchId,context.nodeCode(),context.userid(),context.channel(),context.attempts());
        if (packing) {
            outcome = "EMAIL".equals(context.channel())
                    ? packingMailClient.send(context.recipientEmail(), context.subject(), context.message())
                    : new WeComWebhookClient.SendOutcome(WeComWebhookClient.SendState.UNSENT,null,null,"CHANNEL",false,"LEGACY_PACKING_WEBHOOK_DISABLED");
        } else {
            outcome = context.mobiles().isEmpty()
                    ? new WeComWebhookClient.SendOutcome(WeComWebhookClient.SendState.FAILED,null,null,"CONFIG",false,"RECIPIENT_MOBILE_MISSING")
                    : webhookClient.sendText(send.getWebhookKey(),context.message(),context.mobiles());
        }
        PushContext finished = new PushContext(context.nodeCode(), context.userid(), context.message(), context.mobiles(),
                context.attempts(), context.expiresAt(), outcome, context.channel(), context.recipientEmail(), context.subject(), context.viewToken());
        LocalDateTime now = now();
        LocalDateTime next = null;
        if (Boolean.TRUE.equals(properties.getSend().getRetryEnabled()) && retryable(finished)) {
            LocalDateTime planned = now.plus(properties.getSend().getRetryDelay());
            if (planned.isBefore(LocalDateTime.parse(context.expiresAt()))) next = planned;
        }
        repository.finishPush(batchId, outcome.state().databaseValue(), json(finished), next);
        if(outcome.success()) log.info("催办渠道已接受消息：batchId={}, channel={}, nextRetryAt={}",batchId,context.channel(),next);
        else log.error("催办渠道未确认成功：batchId={}, channel={}, state={}, stage={}, nextRetryAt={}",
                batchId,context.channel(),outcome.state(),outcome.stage(),next);
        return outcome;
    }

    /** 两类补偿分别调度并持有各自的锁；装箱补偿同样实时重查资格。 */
    public int retryDue(boolean packing) {
        SopReminderProperties.Send send = properties.getSend();
        return repository.withDispatchLock(packing, () -> {
            // 核查独立于重发开关：关闭补偿发送也不能让中断的状态1一直悬挂。
            reconcileInterruptedSends(packing);
            if (!Boolean.TRUE.equals(send.getRetryEnabled())) return 0;
            validateRetryPolicy();
            int attempts = 0;
            for (SopReminderRepository.RetryBatch batch : repository.dueRetries(now(), packing)) {
                PushContext context;
                try {
                    context = objectMapper.readValue(batch.contextJson(), PushContext.class);
                } catch (JsonProcessingException | IllegalArgumentException exception) {
                    if (!packing) repository.stopRetry(batch.id());
                    continue;
                }
                boolean packingContext = "PACKING_RELATE".equals(context.nodeCode());
                if (packingContext != packing) continue;
                // 不允许旧的混合消息借普通补偿通道发送装箱数据。
                if (context.nodeCode() != null && context.nodeCode().contains("PACKING_RELATE") && !packingContext) {
                    repository.stopRetry(batch.id());
                    continue;
                }
                if (!retryable(context) || !channelConfigured(packing) || !stillEligible(batch.id(), context)) {
                    repository.stopRetry(batch.id());
                    continue;
                }
                // 校验候选可能等待同步，回来后重新检查截止时间，不能把过时消息补发出去。
                if (!retryable(context) || (!packing && !repository.retryWithinGoodsReadyQuota(batch.id()))) {
                    repository.stopRetry(batch.id());
                    log.info("补偿前停止过期或节点9成功额度已用完的批次：batchId={}",batch.id());
                    continue;
                }
                PushContext next = new PushContext(context.nodeCode(), context.userid(), context.message(), context.mobiles(),
                        context.attempts() + 1, context.expiresAt(), null, context.channel(), context.recipientEmail(), context.subject(), context.viewToken());
                if (!repository.claimRetry(batch.id(), json(next), now())) continue;
                deliver(batch.id(), next);
                attempts++;
            }
            return attempts;
        });
    }

    /** 每分钟及手动执行前核查；状态1超5分钟且已取得同类发送锁，转未知并禁止盲目重发。 */
    private void reconcileInterruptedSends(boolean packing) {
        LocalDateTime before = now().minusMinutes(5);
        for (Long id : repository.staleSending(packing, before)) {
            var outcome = new WeComWebhookClient.SendOutcome(WeComWebhookClient.SendState.UNKNOWN,
                    null, null, "INTERRUPTED_RECONCILIATION", false,
                    "发送中记录超过5分钟，已取得同类发送锁但无最终结果；请人工核对群消息或邮件，禁止直接重发");
            String result;
            try { result = objectMapper.writeValueAsString(outcome); }
            catch (JsonProcessingException exception) { throw new BusinessException(ResultCode.SYSTEM_ERROR, "中断发送核查结果序列化失败"); }
            if (repository.markSendingUnknown(id, packing, before, result)) {
                log.error("催办发送中断待核查：batchId={}, packing={}, status=UNKNOWN；已停止自动重试，请核对实际消息或邮件", id, packing);
            }
        }
    }

    private boolean retryable(PushContext context) {
        if (context == null || !hasText(context.nodeCode()) || !hasText(context.userid())
                || !hasText(context.message()) || context.mobiles() == null
                || context.outcome() == null || !context.outcome().retryable()
                || (context.outcome().state() != WeComWebhookClient.SendState.UNSENT
                    && context.outcome().state() != WeComWebhookClient.SendState.FAILED)
                || context.attempts() < 1 || context.attempts() >= properties.getSend().getMaxAttempts()) return false;
        if ("PACKING_RELATE".equals(context.nodeCode()) && (!"EMAIL".equals(context.channel())
                || !PackingMailClient.validAddress(context.recipientEmail()) || !PackingMailClient.SUBJECT.equals(context.subject()))) return false;
        try { return now().isBefore(LocalDateTime.parse(context.expiresAt())); }
        catch (RuntimeException exception) { return false; }
    }

    private boolean stillEligible(long batchId, PushContext context) {
        Map<String, List<Candidate>> eligible = new LinkedHashMap<>();
        for (String code : context.nodeCode().split(",")) {
            SopReminderProperties.Node node = properties.getNodes().get(code);
            if (node == null || !Boolean.TRUE.equals(node.getEnabled()) || !ruleService.supportsNode(code)) return false;
            List<Candidate> candidates = new ArrayList<>();
            for (RecipientGroup group : ruleService.preview(code).groups()) {
                SopReminderProperties.Person person = personFor(group);
                if (person != null && context.userid().equals(person.getUserid())) {
                    if ("PACKING_RELATE".equals(code)) {
                        if (!java.util.Objects.equals(person.getEmail(), context.recipientEmail())) return false;
                    } else if (!hasText(person.getMobile()) || !List.of(person.getMobile()).equals(context.mobiles())) return false;
                    candidates.addAll(group.candidates());
                }
            }
            eligible.put(code, candidates);
        }
        List<SopReminderRepository.RetryTodo> original = repository.retryTodos(batchId, context.userid());
        if (original.isEmpty() || original.size() != repository.retryTodoCount(batchId)) return false;
        Map<String,List<Candidate>> expected = new LinkedHashMap<>();
        for (var todo : original) {
            Candidate match = eligible.getOrDefault(todo.nodeCode(),List.of()).stream()
                    .filter(candidate -> repository.matchesRetry(candidate,todo,context.userid())).findFirst().orElse(null);
            if (match == null) return false;
            expected.computeIfAbsent(todo.nodeCode(),ignored -> new ArrayList<>()).add(match);
        }
        var states = statusVerifier.verify(context.userid(),expected);
        boolean valid = expected.entrySet().stream().allMatch(entry -> entry.getValue().stream().allMatch(candidate ->
                states.getOrDefault(SopReminderStatusVerifier.Key.of(entry.getKey(),candidate),SopReminderStatusVerifier.Decision.UNKNOWN)
                        == SopReminderStatusVerifier.Decision.ELIGIBLE));
        // 原失败批次保留完整历史，不将已完成单据计入成功次数；余下单据由下一轮重新建批。
        if (!valid) log.info("重试前实时状态复核未全部通过，停止原批次重发：batchId={}",batchId);
        return valid;
    }

    private void validateRetryPolicy() {
        SopReminderProperties.Send send = properties.getSend();
        if (send.getMaxAttempts() < 1 || send.getMaxAttempts() > 5 || send.getRetryDelay() == null
                || send.getRetryDelay().compareTo(java.time.Duration.ofMinutes(1)) < 0
                || send.getRetryMaxAge() == null || send.getRetryMaxAge().compareTo(send.getRetryDelay()) <= 0
                || send.getRetryMaxAge().compareTo(java.time.Duration.ofHours(1)) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "重试总次数须1~5，间隔至少1分钟，有效期大于间隔且不超过1小时");
        }
    }

    private String json(PushContext context) {
        try { return objectMapper.writeValueAsString(context); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "推送记录序列化失败，停止发送");
        }
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }

    /** 群消息正文：只有催办提示和外链，明细在链接页面查看；@人由 mentioned 字段实现，不写进正文。 */
    /** 普通节点邮件正文：重新发送时标注第几次提醒，首次保持原文案不放任何额外字样。 */
    private String compose(String token, String frontBaseUrl, int attemptNo) {
        String head = attemptNo > 1
                ? "今日催办（第" + attemptNo + "次提醒，此前发送结果未确认），请尽快完成。\n"
                : "今日催办，请尽快完成。\n";
        return head + frontBaseUrl + REMINDER_LINK_PATH + token;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
