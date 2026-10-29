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
import com.scione.scm.bill.domain.sop.enums.Decision;
import com.scione.scm.bill.infrastructure.wecom.WeComWebhookClient.SendOutcome;
import com.scione.scm.bill.infrastructure.wecom.enums.SendState;
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
 * 催办发送：preview → 待办落库 → 去重 → 复核 → 组装消息 → 发渠道 → 记录结果 → 失败补发
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SopReminderSendService {

    private final SopReminderService ruleService;//算该催谁
    private final SopReminderProperties properties;//nacos配置
    private final SopReminderRepository repository;//读写代办/推送记录+加锁
    private final WeComWebhookClient webhookClient;//发链接给企微
    private final PackingMailClient packingMailClient;//发邮件（装箱节点用）
    private final SopReminderStatusVerifier statusVerifier;//发送前实时状态复核
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

    /*SendResult（一轮）
            └─ details: List<GroupResult>   ← 每个收件人一条 GroupResult
                ├─ GroupResult（人员A的发送结果）
                ├─ GroupResult（人员B的发送结果）
                └─ GroupResult（人员C的发送结果）
*/
    /**
     * 一整轮发送汇总，details按实际企微收件人划分（每个收件人一条GroupResult）。
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

    /** 定时任务多节点正式入口，正式发送的通用入口,一个时点可能多个节点同时到期,一次发多个,直接转 dispatch */
    public SendResult sendNodes(List<String> nodeCodes, LocalDateTime scheduledAt, String onlyBusinessSn) {
        return dispatch(nodeCodes,scheduledAt,onlyBusinessSn);
    }

    /** 发送主流程：取锁 → 算候选 → 按收件人汇总 → 发送前复核 → 组消息落库 → 发渠道，返回一轮发送结果。 */
    private SendResult dispatch(List<String> nodeCodes,LocalDateTime scheduledAt,String onlyBusinessSn) {
        if (scheduledAt == null) throw new BusinessException(ResultCode.PARAM_ERROR, "计划催办时间不能为空");//计划时点必填
        List<String> codes = nodeCodes.stream().distinct().toList();//节点列表去重，防重复传同一个节点
        if (codes.contains("PACKING_RELATE") && codes.size() > 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "普通催办与装箱催办必须分别调用");//装箱不能和普通节点混在一轮
        }
        if (codes.contains("PACKING_RELATE") && !packingMailClient.configured()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "关联货件SMTP邮件配置未完成，请配置发件邮箱、授权码和TLS服务器参数");//装箱要发邮件，先确认SMTP配好了
        }
        return repository.withDispatchLock(codes.contains("PACKING_RELATE"), () -> {//装箱走装箱锁，普通走普通锁，两把锁互不干扰
            reconcileInterruptedSends(codes.contains("PACKING_RELATE"));//先把上次"发送中"但没结果的记录处理掉
            // 取得对应锁后才查询候选；装箱每次直接查询领星，不等待Python同步，也不复用旧候选。
            for (String code : codes) requireEnabled(code);//逐个节点确认开关是开着的
            long started = System.nanoTime();//计时起点，后面算候选查询耗时
            log.info("催办已取得发送锁，开始查询候选：nodes={}", codes);
            // 第1步：算候选。previewNodes 对普通节点共享一次快照，装箱/PO创建走独立入口。
            Map<String, SopReminderPreview> previews = ruleService.previewNodes(codes);//key=节点码，value=该节点的候选清单
            log.info("催办候选查询完成：nodes={}, elapsedMs={}", codes,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            // 第2步：把候选按实际收件人汇总成 pending（同人多节点合并成一封）。
            Map<String, PendingMessage> pending = new LinkedHashMap<>();//key=收件人标识，value=该收件人的待发消息
            List<String> skipped = new ArrayList<>();//本轮跳过的原因列表
            int groups = 0;//预览分组数（含因缺账号跳过的）
            for (String code : codes) {
                PreparedNode prepared = prepare(code, scheduledAt, onlyBusinessSn, previews.get(code), pending);//每个节点分别准备
                groups += prepared.candidateGroups();
                skipped.addAll(prepared.skipped());
            }
            // 第3步：逐条消息做发送前复核 → 组正文 → 落库 → 发渠道。
            List<GroupResult> details = new ArrayList<>();//每个收件人的发送结果
            int unresolved = 0;//结果未知(UNKNOWN)的消息条数，用于轮次级补发判断
            for (PendingMessage message : pending.values()) {
                // 等待其他节点期间可能被关停，发送前再次检查整条消息。
                message.nodes.forEach(this::requireEnabled);
                boolean packing=message.nodes.contains("PACKING_RELATE");//这条消息是不是装箱邮件
                if(packing && message.packingTriggerIds.isEmpty()) {
                    skipped.add(message.person.getName()+"：当前有"+message.stages.size()+"条关联货件待办，无可触发发送的新增单据（或新增单据结果待确认/已有重试计划）");
                    log.info("关联货件本轮不发送：userid={}, currentTodos={}, newTodos=0",message.person.getUserid(),message.stages.size());
                    continue;//没有新单触发，装箱不主动发
                }
                // 发送前状态复核：把已完成/退出节点的待办从消息里摘掉，只发仍该催的。
                verifyPending(message, skipped);
                if (message.stages.isEmpty()) continue;//复核后一条待办都不剩，这条消息作废
                if(packing && message.packingTriggerIds.stream().noneMatch(message.stages::containsKey)) {
                    skipped.add(message.person.getName()+"：新增单据复核后已完成或状态不明，本轮不发送邮件");
                    log.info("关联货件新增单据复核后不可推送：userid={}",message.person.getUserid());
                    continue;//触发发送的新单复核后都没了，不发
                }
                // 第N次提醒：该收件人名下有发送结果未知的记录时，正文/主题标注重新发送的序号。
                int attemptNo = message.unknownCount + 1;//第几次提醒 = 历史未知次数 + 1
                // 每条消息独立访问令牌，页面按该批次明细读取，避免其他时点的节点混入。
                String viewToken = java.util.UUID.randomUUID().toString().replace("-", "");//生成32位随机token作为外链访问凭据
                String body = packing ? properties.getSend().getFrontBaseUrl() + PACKING_LINK_PATH + viewToken
                        : compose(viewToken, properties.getSend().getFrontBaseUrl(), attemptNo);//装箱直接拼链接，普通拼"提示+链接"正文
                PushContext context = new PushContext(String.join(",", message.nodes), message.person.getUserid(), body,
                        packing ? List.of() : List.of(message.person.getMobile()), 1,
                        now().plus(properties.getSend().getRetryMaxAge()).toString(), null,
                        packing ? "EMAIL" : "WEBHOOK", packing ? message.person.getEmail() : null,
                        packing ? PackingMailClient.subject(attemptNo) : null, viewToken);//把这条消息的完整上下文打包，后续发送/补发都用它
                // 先落库（批次+明细），再发渠道；落库失败就不调用渠道。
                List<Long> todoIds = new ArrayList<>(message.stages.keySet());//这条消息包含的所有待办id
                long batchId = repository.startPush(message.person.getUserid(), todoIds,
                        new ArrayList<>(message.stages.values()), scheduledAt, json(context));//写批次+明细，拿到批次id
                WeComWebhookClient.SendOutcome outcome = deliver(batchId, context);//真正发渠道，返回发送结果
                boolean unknown = !outcome.success()
                        && outcome.state() == SendState.UNKNOWN;//发出去但结果未知（没拿到明确回执）
                if (unknown) unresolved++;//未知消息数+1，供轮次级补发
                String error = outcome.success() ? null : unknown
                        ? "发送结果未知，将在补发轮次重试（最多3次）" : "本次未发送成功，已按重试资格记录";
                details.add(new GroupResult(0, message.person.getName(), message.person.getUserid(),
                        message.stages.size(), outcome.success(), body, error));//记录这个收件人的发送结果
            }
            return new SendResult(String.join(",", codes), groups, details.stream().mapToInt(GroupResult::todos).sum(),
                    details.size(), skipped, unresolved, details);//汇总本轮发送结果返回
        });
    }

    /** 一个实际收件人的一次待发消息（可能含多个节点），发送前攒在这里，最后统一发。 */
    private static final class PendingMessage {
        final SopReminderProperties.Person person;//实际收件人（已解析出的企微账号/姓名/手机号/邮箱）
        final String token;//该收件人的稳定外链token
        final Set<String> nodes = new java.util.LinkedHashSet<>();//这条消息包含哪些节点（同人多节点汇总）
        final Map<Long, Integer> stages = new LinkedHashMap<>();//待办id → 提醒阶段(1或2)
        /** 新单只负责触发；stages保留该收件人的完整关联货件清单。 */
        final Set<Long> packingTriggerIds=new java.util.LinkedHashSet<>();//装箱：哪些待办是"触发发送的新单"
        final Map<Long, Candidate> candidates = new LinkedHashMap<>();//待办id → 候选
        final Map<Long, String> todoNodes = new LinkedHashMap<>();//待办id → 节点码
        /** 该收件人本轮待发单据里历史上 UNKNOWN 的最大次数；0 表示首次发送。 */
        int unknownCount = 0;
        PendingMessage(SopReminderProperties.Person person, String token) { this.person = person; this.token = token; }//构造时先定下收件人和token
    }

    /** prepare的返回：candidateGroups预览分组数、skipped本轮跳过原因列表。 */
    private record PreparedNode(int candidateGroups, List<String> skipped) { }

    /** 发送前最后一道状态校验：对整条消息的候选做实时复核，把已完成/退出节点的待办摘掉并关闭。如果不复核就会催已经办完的事 */
    private void verifyPending(PendingMessage message, List<String> skipped) {
        Map<String,List<Candidate>> expected=new LinkedHashMap<>();
        // 按节点把候选重新归类，交给 Verifier 逐个判定。
        message.candidates.forEach((id,c)->expected.computeIfAbsent(message.todoNodes.get(id),ignored->new ArrayList<>()).add(c));
        long started=System.nanoTime();
        log.info("发送前开始实时状态复核：nodes={}, todos={}",message.nodes,message.stages.size());
        var states=statusVerifier.verify(message.person.getUserid(),expected);
        Map<String,List<Long>> completed=new LinkedHashMap<>();
        for(Long id:new ArrayList<>(message.stages.keySet())) {
            String code=message.todoNodes.get(id);Candidate candidate=message.candidates.get(id);
            var decision=states.getOrDefault(SopReminderStatusVerifier.Key.of(code,candidate),Decision.UNKNOWN);
            // 仍该发（ELIGIBLE）的保留；其余从消息里摘掉。
            if(decision==Decision.ELIGIBLE)continue;
            message.stages.remove(id);
            if(decision==Decision.EXITED) {
                // 已退出节点的待办：摘掉并关闭，避免下轮再算出来。
                completed.computeIfAbsent(code,ignored->new ArrayList<>()).add(id);
                log.info("发送前单据已完成或退出节点，取消本次推送：node={}, businessSn={}",code,candidate.businessSn());
            } else {
                // 状态无法确认：不发送也不关闭，记 skipped 供人工排查。
                skipped.add(code+"/"+candidate.businessSn()+"：最新状态无法确认，本次未发送");
                log.error("发送前最新状态无法确认，本次不发送：node={}, businessSn={}",code,candidate.businessSn());
            }
        }
        completed.forEach((code,ids)->repository.closeVerifiedTodos(code,message.person.getUserid(),ids));
        // 复核后某个节点一条待办都不剩了，就把该节点从消息里去掉。
        message.nodes.removeIf(code->message.stages.keySet().stream().noneMatch(id->code.equals(message.todoNodes.get(id))));
        log.info("发送前实时状态复核完成：remainingTodos={}, elapsedMs={}",message.stages.size(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
    }

    /** 把预览分组按“实际企微账号(userid)”合并，同一个人可能命中多个业务分组,合并成一条消息,并且检测“同一账号配了不同联系方式”的配置错误。 */
    private List<RecipientGroup> mergeActualRecipients(List<RecipientGroup> groups, boolean packing) {
        Map<String, RecipientGroup> merged = new LinkedHashMap<>();//按"实际企微账号"合并后的分组
        Map<String, String> mobiles = new LinkedHashMap<>();//记录每个账号的联系方式，检测同一账号配了不同手机/邮箱的冲突
        for (RecipientGroup group : groups) {
            SopReminderProperties.Person person = personFor(group);//解析出实际收件人
            if (recipientProblem(person, packing) != null) {//收件人不可用，先单独放一个invalid占位，等prepare阶段再记跳过
                merged.put("invalid:" + merged.size(), group);
                continue;
            }
            String key = "person:" + person.getUserid();//以企微userid为合并key，同一个人的多个业务分组合到一起
            String address = packing ? person.getEmail() : person.getMobile();//装箱看邮箱，普通看手机号
            if (mobiles.containsKey(key) && !java.util.Objects.equals(mobiles.get(key), address)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "同一接收账号配置了不同联系方式，停止本轮发送：" + person.getUserid());//同一账号出现两个不同联系方式=配置错误，停止
            }
            mobiles.put(key, address);
            RecipientGroup previous = merged.get(key);
            if (previous == null) merged.put(key, group);//第一次遇到这个账号，直接放进去
            else {//已经存在，把候选合并进去（去重）
                List<Candidate> rows = new ArrayList<>(previous.candidates()); rows.addAll(group.candidates());
                merged.put(key, new RecipientGroup(previous.recipientUid(), previous.recipientName(),
                        rows.stream().distinct().toList(), previous.recipientKey()));
            }
        }
        return new ArrayList<>(merged.values());
    }
    //对一个节点:校验开关/渠道/重试参数 → 处理「缺收件人」的排除项 → 把候选建成待办(prepareGroup)→ 查历史做去重判断(dispatchDecisions)→ 把该发的候选按收件人汇总进 pending。
    private PreparedNode prepare(String nodeCode, LocalDateTime scheduledAt, String onlyBusinessSn,
                               SopReminderPreview preview, Map<String, PendingMessage> pending) {
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
        List<String> skipped = new ArrayList<>();//记录本轮跳过的原因（供返回和日志）
        List<Long> activeIds = new ArrayList<>();//本轮建出来的待办id，用于最后关闭不在候选里的旧待办
        List<String> uncertainSns=new ArrayList<>(preview.excluded().stream().map(SopReminderPreview.Excluded::businessSn).toList());//不确定的单号，暂不关闭其旧待办
        if ("RF_CREATE".equals(nodeCode)) {
            // RF_CREATE：起算时间缺失的单，取消它的旧待办（不再显示旧创建时间兜底值）。
            List<String> missingStarts = preview.excluded().stream()
                    .filter(row -> row.reason().equals("节点起算时间缺失：没有明确进入待到货的日志"))
                    .map(SopReminderPreview.Excluded::businessSn).distinct().toList();
            repository.cancelMissingRfStartTodos(missingStarts);
            // 请款池在PO创建时就有记录，PO还没到货属正常情况：只跳过，不当作异常。
            missingStarts.forEach(sn -> log.info("RF_CREATE本期不催办：businessSn={}, reason=尚未进入待到货，起算时间未到", sn));
        }
        List<SopReminderRepository.SkippedRecipient> missingRecipients = new ArrayList<>();//因收件人缺失而跳过的记录，最后统一写skip表
        for (SopReminderPreview.Excluded excluded : preview.excluded()) {
            // 只挑"缺采购员/创建人/跟单员/审批责任人"这类收件人问题的排除项，记成跳过记录。
            if ((onlyBusinessSn == null || onlyBusinessSn.equals(excluded.businessSn()))
                    && (excluded.reason().contains("采购员") || excluded.reason().contains("创建人")
                    || excluded.reason().contains("跟单员") || excluded.reason().contains("审批责任人"))) {
                missingRecipients.add(new SopReminderRepository.SkippedRecipient(excluded.businessSn(), null,
                        "SOURCE_RECIPIENT_UNKNOWN", excluded.reason(), "", 0));
                skipped.add(excluded.businessSn() + "：" + excluded.reason());
                log.error("催办未发送：node={}, businessSn={}, reason={}", nodeCode, excluded.businessSn(), excluded.reason());
            }
        }

        // 把预览分组按"实际企微收件人"合并（同人多业务分组只处理一次）。
        List<RecipientGroup> actualGroups = mergeActualRecipients(preview.groups(), packing);
        long prepareStarted = System.nanoTime();
        log.info("催办待办处理开始：node={}, businessGroups={}, actualGroups={}, candidates={}",
                nodeCode, preview.groups().size(), actualGroups.size(), preview.candidateCount());
        for (RecipientGroup group : actualGroups) {
            List<Candidate> groupCandidates = group.candidates();
            if (groupCandidates.isEmpty()) continue;//空分组跳过
            SopReminderProperties.Person person = personFor(group);//把recipientKey解析成具体收件人
            // 普通节点校验手机号，关联货件校验个人邮箱。
            String problem = recipientProblem(person, packing);
            if (problem != null) {//收件人不可用（缺手机号/邮箱/账号无效），跳过并记录
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
            List<SopReminderPreview.Candidate> candidates=new ArrayList<>();//最终要发的候选
            List<Long> deliverIds=new ArrayList<>();List<Integer> stages=new ArrayList<>();//对应候选的待办id和阶段
            boolean capped="GOODS_READY".equals(nodeCode);//货物备齐节点有"最多两次"额度
            // 查历史推送决定每条该不该发（去重）；装箱不查历史，只发新单。
            List<SopReminderRepository.DispatchDecision> decisions=packing?List.of():repository.dispatchDecisions(
                    prepared.todoIds(),prepared.stages(),scheduledAt,capped);//查历史推送决定每条该不该发
            Set<Long> newPackingIds=packing?new java.util.LinkedHashSet<>(repository.newPackingTodoIds(prepared.todoIds(),scheduledAt)):Set.of();//装箱只有新单才触发
            if(capped) repository.suppressGoodsReadyTodos(person.getUserid(),
                    java.util.stream.IntStream.range(0,decisions.size()).filter(i->decisions.get(i).hideFromPage())
                            .mapToObj(prepared.todoIds()::get).toList());//用完额度的货物备齐待办从页面隐藏
            // 该收件人本轮待发单据里，历史上发送结果未知的最大次数，用于第N次提醒标注。
            int unknownCount = 0;
            for (int i=0;i<groupCandidates.size();i++) {
                SopReminderPreview.Candidate candidate=groupCandidates.get(i);
                // 指定单号时只发这一张，其余候选只建待办不发送。
                if (onlyBusinessSn != null && !onlyBusinessSn.equals(candidate.businessSn())) continue;
                if (packing || decisions.get(i).sendable()) {//该发：加入发送清单
                    candidates.add(candidate);stages.add(candidate.stage());
                    deliverIds.add(prepared.todoIds().get(i));
                    if (!packing) unknownCount=Math.max(unknownCount,decisions.get(i).unknownCount());
                } else {//被去重挡掉，记跳过原因
                    String reason=decisions.get(i).reason();
                    skipped.add(nodeCode+"/"+candidate.businessSn()+"："+reason);
                    log.info("催办本条不发送：node={}, businessSn={}, reason={}",nodeCode,candidate.businessSn(),reason);
                }
            }
            if (candidates.isEmpty()) continue;//这个收件人本轮没有可发的，跳过
            // 装箱关联货件独立发送，其他节点仍按实际收件人汇总。
            String messageKey = person.getUserid() + ("PACKING_RELATE".equals(nodeCode) ? "|packing" : "|standard");//装箱和普通分开组消息
            PendingMessage message = pending.computeIfAbsent(messageKey,
                    ignored -> new PendingMessage(person, prepared.token()));
            if (!java.util.Objects.equals(packing ? message.person.getEmail() : message.person.getMobile(),
                        packing ? person.getEmail() : person.getMobile())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "同一接收账号配置了不同联系方式，停止本轮发送：" + person.getUserid());
            }
            message.nodes.add(nodeCode);//把该节点加进这条消息的节点集合（同人多节点汇总）
            // 同一人多节点汇总时取最大次数：只要有单子反复结果未知，整封邮件都按第N次提醒标注。
            message.unknownCount=Math.max(message.unknownCount,unknownCount);
            for (int i = 0; i < deliverIds.size(); i++) {
                long id=deliverIds.get(i);message.stages.put(id,stages.get(i));//待办id→阶段
                if(packing && newPackingIds.contains(id)) message.packingTriggerIds.add(id);//装箱记录哪些待办是"触发发送的新单"
                message.candidates.put(id,candidates.get(i));message.todoNodes.put(id,nodeCode);//待办id→候选、待办id→节点
            }
        }
        // 与定时任务保持一致：本轮不在候选里的待办关闭；测试链路也关，才能验证定时任务效果。
        repository.recordSkippedRecipients(nodeCode, scheduledAt, missingRecipients);//把因缺收件人跳过的记录写进skip表（record_type=3）
        repository.closeAbsentTodos(nodeCode,activeIds,uncertainSns.stream().distinct().toList());//关闭"本轮候选里已经没了"的旧待办，不确定的单号不关
        log.info("催办待办处理完成：node={}, activeTodos={}, elapsedMs={}", nodeCode, activeIds.size(),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - prepareStarted));
        return new PreparedNode(preview.groups().size(), skipped);//返回预览分组数 + 跳过原因列表
    }

    /** 展示名：优先配置里的人名，其次预览分组名，最后退回分组键。 */
    private static String recipientName(RecipientGroup group, SopReminderProperties.Person person) {
        if (person != null && hasText(person.getName())) return person.getName();//有配置人名就用配置人名
        return hasText(group.recipientName()) ? group.recipientName() : group.recipientKey();//其次用预览分组名，都没有就退回分组键
    }

    /** 跳过提示的标签：名字（分组键），两者相同时只显示一次，保证能对上Nacos的key。 */
    private static String recipientLabel(RecipientGroup group, SopReminderProperties.Person person) {
        String name = recipientName(group, person);//先取展示名
        return name.equals(group.recipientKey()) ? name : name + "（" + group.recipientKey() + "）";//名字和key不同时，把key括在后面方便定位
    }

    /** 发送前校验节点开关：Nacos里没配或enabled=false都直接报错停止。 */
    private void requireEnabled(String nodeCode) {
        SopReminderProperties.Node node = properties.getNodes().get(nodeCode);//取该节点的Nacos配置
        if (node == null || !Boolean.TRUE.equals(node.getEnabled())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该催办节点未开启");//没配或开关关闭，报错
        }
    }

    /** 普通节点使用群机器人，关联货件使用独立SMTP渠道。 */
    private boolean channelConfigured(boolean packing) {
        return packing ? packingMailClient.configured() : hasText(properties.getSend().getWebhookKey());//装箱看SMTP配好没，普通看webhook key有没有
    }

    /** 把分组的recipientKey解析成Nacos里配置的具体收件人（Person）。 */
    private SopReminderProperties.Person personFor(RecipientGroup group) {
        return properties.resolvePerson(group.recipientKey(),group.recipientUid());//用recipientKey(uid:/role:/name:)去Nacos查人
    }

    /** 收件人不可用的原因；返回null表示可以发送。群消息必须有手机号，否则@不到人。 */
    private static String recipientProblem(SopReminderProperties.Person person, boolean packing) {
        if (person == null || !hasText(person.getUserid())) return "未配置企微账号";//没解析到人或没userid
        if ("@all".equals(person.getUserid()) || person.getUserid().contains("|")) return "企微账号无效";//userid是@all或含竖线，非法
        if (packing) return PackingMailClient.validAddress(person.getEmail()) ? null : "未配置有效的个人邮箱";//装箱校验邮箱
        if (!hasText(person.getMobile())) return "未配置手机号，无法@到本人";//普通校验手机号
        return null;//都合法，返回null表示可发送
    }

    /**
     * 消息的完整上下文JSON：一条消息的完整信息，record表中type=1的ReminderPushBatchPO的send_result 字段
     * reminder_push_record 表
     * ├─ record_type=1 行  ← ReminderPushBatchPO（批次），它的 send_result 字段里存的是 PushContext 的 JSON
     * ├─ record_type=2 行  ← PushDetailInsert（明细，多个），parent_id 指向上面那行
     * └─ record_type=3 行  ← SkippedRow（未发送异常，多个）
     *
     * @param nodeCode       节点编码（多节点逗号分隔）
     * @param userid         实际收件人企微userid
     * @param message        消息正文（提示+外链）
     * @param mobiles        普通节点@人的手机号列表，装箱为空
     * @param attempts       第几次尝试（1=首次）
     * @param expiresAt      消息过期时间，超过就不再补发
     * @param outcome        上次发送结果
     * @param channel        发送渠道（WEBHOOK/EMAIL）
     * @param recipientEmail 装箱收件邮箱，普通为null
     * @param subject        装箱邮件主题，普通为null
     * @param viewToken      外链访问token（32位）
     */
    public record PushContext(String nodeCode, String userid, String message, List<String> mobiles,
                              int attempts, String expiresAt, WeComWebhookClient.SendOutcome outcome,
                              String channel, String recipientEmail, String subject, String viewToken) {
        /** 兼容构造器：无viewToken（老数据/非外链场景），其余字段完整。 */
        public PushContext(String nodeCode, String userid, String message, List<String> mobiles,
                           int attempts, String expiresAt, WeComWebhookClient.SendOutcome outcome,
                           String channel, String recipientEmail, String subject) {
            this(nodeCode, userid, message, mobiles, attempts, expiresAt, outcome, channel, recipientEmail, subject, null);
        }
        /** 兼容构造器：无渠道/邮箱/主题/viewToken（最老的推送记录），只保留基础字段。 */
        public PushContext(String nodeCode, String userid, String message, List<String> mobiles,
                           int attempts, String expiresAt, WeComWebhookClient.SendOutcome outcome) {
            this(nodeCode, userid, message, mobiles, attempts, expiresAt, outcome, null, null, null);
        }
    }

    /** 真正调用渠道发消息：装箱走邮件，普通走企微webhook；发完按结果写回批次状态和重试时间。 */
    private WeComWebhookClient.SendOutcome deliver(long batchId, PushContext context) {
        SopReminderProperties.Send send=properties.getSend();//发送配置
        boolean packing = "PACKING_RELATE".equals(context.nodeCode());//是不是装箱消息
        WeComWebhookClient.SendOutcome outcome;//发送结果
        log.info("催办渠道发送开始：batchId={}, nodes={}, userid={}, channel={}, attempt={}",
                batchId,context.nodeCode(),context.userid(),context.channel(),context.attempts());
        // 装箱走 SMTP 邮件；普通走企微群机器人（@手机号）。
        if (packing) {
            outcome = "EMAIL".equals(context.channel())
                    ? packingMailClient.send(context.recipientEmail(), context.subject(), context.message())//渠道是EMAIL，发邮件
                    : new WeComWebhookClient.SendOutcome(SendState.UNSENT,null,null,"CHANNEL",false,"LEGACY_PACKING_WEBHOOK_DISABLED");//旧版装箱走webhook的，禁用返回未发送
        } else {
            outcome = context.mobiles().isEmpty()
                    ? new WeComWebhookClient.SendOutcome(SendState.FAILED,null,null,"CONFIG",false,"RECIPIENT_MOBILE_MISSING")//没手机号，无法@，失败
                    : webhookClient.sendText(send.getWebhookKey(),context.message(),context.mobiles());//有手机号，发企微群消息
        }
        // 把发送结果回填到上下文，连同尝试次数一起持久化，供补偿重试使用。
        PushContext finished = new PushContext(context.nodeCode(), context.userid(), context.message(), context.mobiles(),
                context.attempts(), context.expiresAt(), outcome, context.channel(), context.recipientEmail(), context.subject(), context.viewToken());
        LocalDateTime now = now();
        LocalDateTime next = null;
        // 失败且可重试：排一个「现在+重试延迟」的下次重试时间，但不能超过该消息的过期时间。
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

    /** 两类补偿分别调度并持有各自的锁；装箱补偿同样实时重查。 */
    public int retryDue(boolean packing) {
        SopReminderProperties.Send send = properties.getSend();
        return repository.withDispatchLock(packing, () -> {//装箱和普通各拿各的锁
            // 核查独立于重发开关：关闭补偿发送也不能让中断的状态1一直悬挂。
            reconcileInterruptedSends(packing);//先处理上次发一半没结果的悬挂记录
            if (!Boolean.TRUE.equals(send.getRetryEnabled())) return 0;//补偿开关关了，直接返回0
            validateRetryPolicy();//校验重试参数配得对不对
            int attempts = 0;//本次补发的消息条数
            for (SopReminderRepository.RetryBatch batch : repository.dueRetries(now(), packing)) {//查出所有到期该补发的失败批次
                PushContext context;//从库里还原出来的消息上下文
                try {
                    context = objectMapper.readValue(batch.contextJson(), PushContext.class);//把存进去的JSON反序列化回上下文对象
                } catch (JsonProcessingException | IllegalArgumentException exception) {
                    if (!packing) repository.stopRetry(batch.id());//上下文解析失败，普通节点停止重试
                    continue;
                }
                boolean packingContext = "PACKING_RELATE".equals(context.nodeCode());//这条消息是不是装箱消息
                if (packingContext != packing) continue;//消息类型和当前补偿通道不一致，跳过
                if (!retryable(context) || !channelConfigured(packing) || !stillEligible(batch.id(), context)) {
                    repository.stopRetry(batch.id());//不可重试、渠道没配、或资格复核不通过，都停止
                    continue;
                }
                // 校验候选可能等待同步，回来后重新检查截止时间，不能把过时消息补发出去。
                if (!retryable(context) || (!packing && !repository.retryWithinGoodsReadyQuota(batch.id()))) {
                    repository.stopRetry(batch.id());//过期或货物备齐额度用完，停止
                    log.info("补偿前停止过期或节点9成功额度已用完的批次：batchId={}",batch.id());
                    continue;
                }
                PushContext next = new PushContext(context.nodeCode(), context.userid(), context.message(), context.mobiles(),
                        context.attempts() + 1, context.expiresAt(), null, context.channel(), context.recipientEmail(), context.subject(), context.viewToken());//把尝试次数+1，生成新的上下文
                if (!repository.claimRetry(batch.id(), json(next), now())) continue;//条件UPDATE抢占，抢不到说明别人抢先了，跳过
                deliver(batch.id(), next);//真正重发
                attempts++;//补发数+1
            }
            return attempts;
        });
    }

    /** 每分钟及手动执行前核查；状态1超5分钟且已取得同类发送锁，转未知并禁止盲目重发。 */
    private void reconcileInterruptedSends(boolean packing) {
        LocalDateTime before = now().minusMinutes(5);//5分钟前的时间线，超过它仍在"发送中"就视为悬挂
        for (Long id : repository.staleSending(packing, before)) {//查出所有悬挂的发送中记录
            var outcome = new WeComWebhookClient.SendOutcome(SendState.UNKNOWN,
                    null, null, "INTERRUPTED_RECONCILIATION", false,
                    "发送中记录超过5分钟，已取得同类发送锁但无最终结果；请人工核对群消息或邮件，禁止直接重发");//构造一个"未知"结果，标记为人工核对
            String result;//序列化后的结果JSON
            try { result = objectMapper.writeValueAsString(outcome); }
            catch (JsonProcessingException exception) { throw new BusinessException(ResultCode.SYSTEM_ERROR, "中断发送核查结果序列化失败"); }
            if (repository.markSendingUnknown(id, packing, before, result)) {//把悬挂记录标记为UNKNOWN，禁止自动重发
                log.error("催办发送中断待核查：batchId={}, packing={}, status=UNKNOWN；已停止自动重试，请核对实际消息或邮件", id, packing);
            }
        }
    }

    /** 判断一条消息是否还具备重发资格：上下文完整、结果可重试、次数未超上限、未过期、渠道信息齐全。 */
    private boolean retryable(PushContext context) {
        if (context == null || !hasText(context.nodeCode()) || !hasText(context.userid())
                || !hasText(context.message()) || context.mobiles() == null
                || context.outcome() == null || !context.outcome().retryable()
                || (context.outcome().state() != SendState.UNSENT
                    && context.outcome().state() != SendState.FAILED)
                || context.attempts() < 1 || context.attempts() >= properties.getSend().getMaxAttempts()) return false;//上下文缺字段、结果明确不可重试、或次数已到上限
        if ("PACKING_RELATE".equals(context.nodeCode()) && (!"EMAIL".equals(context.channel())
                || !PackingMailClient.validAddress(context.recipientEmail()) || !PackingMailClient.SUBJECT.equals(context.subject()))) return false;//装箱必须是EMAIL渠道且邮箱、主题都合法
        try { return now().isBefore(LocalDateTime.parse(context.expiresAt())); }//没过期才允许重发
        catch (RuntimeException exception) { return false; }
    }

    /** 补发前复核：重新算一遍候选，确认原消息里的待办现在还在候选名单里、还归这个收件人。 */
    private boolean stillEligible(long batchId, PushContext context) {
        Map<String, List<Candidate>> eligible = new LinkedHashMap<>();//当前仍该催的候选，按节点归类
        for (String code : context.nodeCode().split(",")) {//原消息可能含多个节点，逐个复核
            SopReminderProperties.Node node = properties.getNodes().get(code);
            if (node == null || !Boolean.TRUE.equals(node.getEnabled()) || !ruleService.supportsNode(code)) return false;//节点已关停或不存在，不再补发
            List<Candidate> candidates = new ArrayList<>();
            for (RecipientGroup group : ruleService.preview(code).groups()) {//重算该节点的候选
                SopReminderProperties.Person person = personFor(group);
                if (person != null && context.userid().equals(person.getUserid())) {//只认发给原收件人的那部分
                    if ("PACKING_RELATE".equals(code)) {
                        if (!java.util.Objects.equals(person.getEmail(), context.recipientEmail())) return false;//装箱邮箱变了，不补发
                    } else if (!hasText(person.getMobile()) || !List.of(person.getMobile()).equals(context.mobiles())) return false;//普通手机号变了，不补发
                    candidates.addAll(group.candidates());
                }
            }
            eligible.put(code, candidates);
        }
        List<SopReminderRepository.RetryTodo> original = repository.retryTodos(batchId, context.userid());//原批次里的待办明细
        if (original.isEmpty() || original.size() != repository.retryTodoCount(batchId)) return false;//明细缺失或不完整，不补发
        Map<String,List<Candidate>> expected = new LinkedHashMap<>();
        for (var todo : original) {//逐条核对：原待办是否还能在"当前候选"里找到完全匹配的那条
            Candidate match = eligible.getOrDefault(todo.nodeCode(),List.of()).stream()
                    .filter(candidate -> repository.matchesRetry(candidate,todo,context.userid())).findFirst().orElse(null);
            if (match == null) return false;//有一条对不上，整批不补发
            expected.computeIfAbsent(todo.nodeCode(),ignored -> new ArrayList<>()).add(match);
        }
        var states = statusVerifier.verify(context.userid(),expected);//再做一次实时状态复核
        boolean valid = expected.entrySet().stream().allMatch(entry -> entry.getValue().stream().allMatch(candidate ->
                states.getOrDefault(SopReminderStatusVerifier.Key.of(entry.getKey(),candidate),Decision.UNKNOWN)
                        == Decision.ELIGIBLE));//全部候选仍为ELIGIBLE才补发
        // 原失败批次保留完整历史，不将已完成单据计入成功次数；余下单据由下一轮重新建批。
        if (!valid) log.info("重试前实时状态复核未全部通过，停止原批次重发：batchId={}",batchId);
        return valid;
    }

    /** 校验Nacos里的重试参数是否合理：次数1~5、间隔≥1分钟、有效期大于间隔且≤1小时。 */
    private void validateRetryPolicy() {
        SopReminderProperties.Send send = properties.getSend();
        if (send.getMaxAttempts() < 1 || send.getMaxAttempts() > 5 || send.getRetryDelay() == null
                || send.getRetryDelay().compareTo(java.time.Duration.ofMinutes(1)) < 0
                || send.getRetryMaxAge() == null || send.getRetryMaxAge().compareTo(send.getRetryDelay()) <= 0
                || send.getRetryMaxAge().compareTo(java.time.Duration.ofHours(1)) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "重试总次数须1~5，间隔至少1分钟，有效期大于间隔且不超过1小时");
        }
    }

    /** 把PushContext序列化成JSON存进推送记录，供补发时还原消息上下文。 */
    private String json(PushContext context) {
        try { return objectMapper.writeValueAsString(context); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "推送记录序列化失败，停止发送");
        }
    }

    /** 统一取当前北京时间。 */
    private static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }

    /** 拼普通催办消息正文：一句提示 + 带token的外链；第N次提醒时在开头标注「第N次提醒」。 */
    private String compose(String token, String frontBaseUrl, int attemptNo) {
        String head = attemptNo > 1
                ? "今日催办（第" + attemptNo + "次提醒，此前发送结果未确认），请尽快完成。\n"
                : "今日催办，请尽快完成。\n";//第N次提醒时，开头加"第N次提醒"说明
        return head + frontBaseUrl + REMINDER_LINK_PATH + token;//提示 + 外链地址 + token
    }

    /** 判断字符串是否非空（非null且去掉首尾空白后仍有内容）。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
