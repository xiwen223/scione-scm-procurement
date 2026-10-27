package com.scione.scm.bill.application;

import com.scione.scm.bill.infrastructure.lingxing.LingxingWebSellerClient;
import com.scione.scm.bill.infrastructure.lingxing.LingxingOpenApiClient;
import com.scione.scm.bill.config.SopReminderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.domain.sop.SopReminderPreview.NodeCode;
import com.scione.scm.bill.domain.sop.SopReminderSourceRepository.NodeData;
import com.scione.scm.bill.domain.sop.SopReminderSourceRepository.Event;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import java.time.LocalDate;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.sop.SopReminderPreview;
import com.scione.scm.bill.domain.sop.SopReminderPreview.Candidate;
import com.scione.scm.bill.domain.sop.SopReminderPreview.Excluded;
import com.scione.scm.bill.domain.sop.SopReminderPreview.PlanRow;
import com.scione.scm.bill.domain.sop.SopReminderPreview.RecipientGroup;
import com.scione.scm.bill.domain.sop.SopReminderSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** 催办业务统一入口；标准节点共用已发布快照，关联货件节点直接读取接口。 */
@Service
@RequiredArgsConstructor
public class SopReminderService {
    // 数据来源一：调用领星网页接口取得当前账号可见的店铺ID、店铺名称。
    private final LingxingWebSellerClient sellerClient;
    // 数据来源二：读取配置指定的_h来源库及现有合同、跟单信息，不固定使用本地库。
    private final SopReminderSourceRepository repository;
    // 数据来源三：Nacos统一催办配置：公共来源/时效与各节点规则。
    private final SopReminderProperties properties;
    private final ObjectMapper objectMapper;
    private final LingxingOpenApiClient openApiClient;

    public boolean supportsNode(String nodeCode) {
        try { NodeCode.valueOf(nodeCode); return true; }
        catch (IllegalArgumentException | NullPointerException exception) { return false; }
    }

    /** 统一业务入口。后续增加节点时在这里分派，不为每个节点再建一个Controller。 */
    public SopReminderPreview preview(String nodeCode) {
        if (!supportsNode(nodeCode)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "尚未实现的催办节点：" + nodeCode);
        }
        if ("PO_CREATE".equals(nodeCode)) return previewPoCreate();
        if ("PACKING_RELATE".equals(nodeCode)) return previewPacking();
        return previewStandard(NodeCode.valueOf(nodeCode));
    }

    /** 一次发送调用内复用来源快照；不跨请求缓存，装箱始终走独立实时入口。 */
    public Map<String, SopReminderPreview> previewNodes(List<String> codes) {
        Map<String, SopReminderPreview> previews = new LinkedHashMap<>();
        NodeData shared = null;
        LocalDateTime evaluatedAt = null;
        for (String code : codes) {
            if (!supportsNode(code)) throw new BusinessException(ResultCode.PARAM_ERROR, "尚未实现的催办节点：" + code);
            if ("PO_CREATE".equals(code) || "PACKING_RELATE".equals(code)) {
                previews.put(code, preview(code));
            } else {
                if (shared == null) {
                    shared = repository.readNodeData(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
                    evaluatedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                }
                previews.put(code, evaluateStandard(NodeCode.valueOf(code), shared, evaluatedAt));
            }
        }
        return previews;
    }

    private SopReminderPreview previewPoCreate() {
        // 第1步：检查配置。店铺前缀为空时必须停止，不能意外扩大成全部店铺。
        SopReminderProperties.Node node = properties.getNodes().get("PO_CREATE");
        String prefix = node == null ? null : node.getShopNamePrefix();
        if (prefix == null || prefix.isBlank() || properties.getPpStartDate() == null) {
            throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "节点1店铺前缀和PP范围日期不能为空");
        }
        // 第2步：调用网页店铺列表，使用同步库的Cookie；权限或登录失效时停止。
        // 使用采购筛选下拉接口，包含NA/EU共享店铺；仍以当前网页账号可见范围为准。
        // 仅节点1使用杭州名称筛选，通用同步和其他节点不加这个条件。
        Map<Long, String> shops = new LinkedHashMap<>();
        for (LingxingWebSellerClient.Seller seller : sellerClient.findSellers()) {
            if (seller.name().startsWith(prefix)) shops.put(seller.sid(), seller.name());
        }
        List<Long> shopIds = shops.keySet().stream().sorted().toList();
        // 第3步：固定本次计算时间，整张清单用同一个北京时间，避免逐行计算时基准不同。
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        // 查询层负责检查同步状态、数据是否过期，并筛选杭州店铺的待采购PP。
        SopReminderPreview.Snapshot snapshot = repository.readPoCreate(shopIds, now);
        // 如因同步而等待，卡滞时长和报告时间按实际开始计算的时刻统一计算。
        now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        // excluded保存不能参与本次计算的单据及原因，供联调核对，不当成正常候选。
        List<Excluded> excluded = new ArrayList<>();
        // grouped示例：采购员UID 1001 → [PP001、PP002]；同一个人名不一定唯一，所以用UID分组。
        Map<Long, List<Candidate>> grouped = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        // 一个PP的多个SKU/店铺归同一采购员时只催一次；不同采购员分别收到自己的候选。
        Set<String> acceptedPlans = new HashSet<>();
        for (PlanRow row : snapshot.plans()) {
            // 第4步：逐单检查数据。创建时间不明或采购员不明时，不猜时间、不拿创建人代替采购员。
            if (row.createdAt() == null) {
                excluded.add(new Excluded(row.planSn(), "接口创建时间及创建日志均缺失"));
            } else if (row.createdAt().isAfter(now)) {
                excluded.add(new Excluded(row.planSn(), "创建时间晚于当前时间"));
            } else if (row.createdAt().isBefore(properties.getPpStartDate().atStartOfDay())) {
                excluded.add(new Excluded(row.planSn(), "PP创建时间不在范围内"));
            } else if (row.purchaserUid() == null || row.purchaserUid() <= 0) {
                excluded.add(new Excluded(row.planSn(), "采购员领星UID缺失，不能用创建人代替"));
            } else if (!shops.containsKey(row.sid())) {
                excluded.add(new Excluded(row.planSn(), "不属于本次杭州店铺范围"));
            } else {
                if (!acceptedPlans.add(row.purchaserUid() + ":" + row.planSn())) continue;
                // 第5步：节点1按PRD使用“当前时间－PP创建时间”，单位是小时。
                // 例如相差19296秒：19296÷3600=5.36小时；HALF_UP表示四舍五入保留两位。
                BigDecimal hours = BigDecimal.valueOf(Duration.between(row.createdAt(), now).getSeconds())
                        .divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
                // 该采购员首次出现时先创建一个空列表，再把当前PP加入其候选列表。
                grouped.computeIfAbsent(row.purchaserUid(), ignored -> new ArrayList<>())
                        .add(new Candidate(row.planSn(), row.sid(), shops.get(row.sid()), row.createdAt(), hours));
                // 名称只用于展示；名称缺失但UID明确时，显示UID，不能据此伪造企微userid。
                names.putIfAbsent(row.purchaserUid(), row.purchaserName() == null || row.purchaserName().isBlank()
                        ? "领星UID:" + row.purchaserUid() : row.purchaserName());
            }
        }
        // 第6步：生成每位采购员的清单；组内按PP创建时间从早到晚排列，便于先处理卡得久的单据。
        List<RecipientGroup> groups = grouped.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new RecipientGroup(entry.getKey(), names.get(entry.getKey()),
                        entry.getValue().stream().sorted(Comparator.comparing(Candidate::ppCreatedAt)
                                .thenComparing(Candidate::businessSn)).toList()))
                .toList();
        // 第7步：返回预览结果。candidateCount是各采购员候选数之和，不包含excluded。
        // 本阶段到这里结束，不创建待办、不记发送次数，也不发送企微消息。
        return new SopReminderPreview("PO_CREATE", "PO创建未完成", now, snapshot.taskId(), snapshot.syncedAt(), shopIds,
                groups.stream().mapToInt(group -> group.candidates().size()).sum(), groups, excluded.stream().distinct().toList());
    }

    private SopReminderPreview previewPacking() {
        // 预览允许复用一小时内的批次详情；发送前复核由 SopReminderStatusVerifier 再做一次，
        // 它只认一分钟内的缓存，指纹一变同样重拉，因此预览复用旧数据不会带进发送结果。
        LingxingOpenApiClient.PackingSource source=openApiClient.fetchReminderPackingForPreview(
                properties.getPackingStartDate());
        return evaluatePacking(source);
    }

    /** RP维度：存在已完成装箱任务，且任意子计划明确未关联货件，即提醒批次创建人。 */
    public SopReminderPreview evaluatePacking(LingxingOpenApiClient.PackingSource source) {
        List<Excluded> excluded=new ArrayList<>();Map<String,JsonNode> batches=new LinkedHashMap<>();
        for (JsonNode row:source.batches()) {
            String sn=text(row,"seq");
            if (sn.isBlank() || batches.putIfAbsent(sn,row)!=null)
                throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE,"发货计划父批次缺失或重复");
        }
        Map<String,List<JsonNode>> tasks=new LinkedHashMap<>();
        for (JsonNode task : source.tasks()) {
            if (task.path("relateSnType").asInt() != 1) continue;
            String batchSn = text(task, "relateSn");
            if (!batchSn.startsWith("RP") || !task.path("relateShipment").isArray()) {
                throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "装箱任务的父批次或货件列表结构异常");
            }
            tasks.computeIfAbsent(batchSn, ignored -> new ArrayList<>()).add(task);
        }
        Map<String,List<Candidate>> grouped=new LinkedHashMap<>();
        for (var entry:tasks.entrySet()) {
            String sn=entry.getKey();
            if (entry.getValue().stream().noneMatch(task->task.path("taskStatus").asInt()==3)) continue;
            JsonNode batch=batches.get(sn);
            if (batch==null) {excluded.add(new Excluded(sn,"未找到父批次，不能用子计划时间或装箱员代替"));continue;}
            LocalDateTime created=dateTime(text(batch,"create_time"));
            if (created==null) {excluded.add(new Excluded(sn,"父批次创建时间缺失"));continue;}
            if (created.toLocalDate().isBefore(properties.getPackingStartDate())) continue;
            List<JsonNode> children=array(batch,"list");boolean unlinked=false,invalid=children.isEmpty();
            // 未关联子计划的集合就是这批货件的"状态"：集合一变，就应当再提醒一次。
            for (JsonNode child:children) {
                String flag=text(child,"is_relate_mws");
                if (!Set.of("0","1").contains(flag)) invalid=true;
                if ("0".equals(flag)) {
                    unlinked=true;
                    JsonNode shipments = child.path("mws_relate");
                    if (text(child,"order_sn").isBlank() || !shipments.isArray() || !shipments.isEmpty()) invalid=true;
                }
            }
            if (invalid) {excluded.add(new Excluded(sn,"子计划关联状态缺失或与货件列表矛盾"));continue;}
            if (!unlinked) continue;
            String creator=text(batch,"create_user");
            if (creator.isBlank() || created.isAfter(source.checkedAt())) {excluded.add(new Excluded(sn,"父批次创建人或时间异常"));continue;}
            BigDecimal hours=BigDecimal.valueOf(Duration.between(created,source.checkedAt()).getSeconds())
                    .divide(BigDecimal.valueOf(3600),2,RoundingMode.HALF_UP);
            grouped.computeIfAbsent(creator,ignored->new ArrayList<>()).add(new Candidate(sn,0,null,created,hours,
                    "RP","","BATCH:"+sn,1));
        }
        List<RecipientGroup> groups=grouped.entrySet().stream().map(entry->new RecipientGroup(0,entry.getKey(),
                List.copyOf(entry.getValue()),"name:"+entry.getKey())).toList();
        return new SopReminderPreview("PACKING_RELATE",NodeCode.PACKING_RELATE.title(),source.checkedAt(),0,
                source.checkedAt(),List.of(),groups.stream().mapToInt(group->group.candidates().size()).sum(),groups,excluded);
    }

    private record Entry(LocalDateTime time, String key) { }
    private record Recipient(long uid, String name, String key) { }
    private record RuleHit(Candidate candidate, Recipient recipient) { }
    private static final Pattern STATE_CHANGE = Pattern.compile(
            "(?:单据)?状态(?:变更为|变为|调整为|修改为|改为)\\s*[:：]?\\s*【?([^\\s，,。;；<】]+)");
    // 真实领星日志示例：提交采购单【PO...】，单据状态为【待审批】。
    // 与自由文本备注区分，只识别独立的“单据状态”段；编辑事件不使用这条兜底。
    private static final Pattern STATE_DECLARATION = Pattern.compile(
            "(?:^|[，,。;；\\n])\\s*单据状态为\\s*【([^】]+)】");

    private SopReminderPreview previewStandard(NodeCode node) {
        NodeData data = repository.readNodeData(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        return evaluateStandard(node, data, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /** 纯规则计算入口，便于用边界样本核对，不在循环里请求数据库或领星。 */
    public SopReminderPreview evaluateStandard(NodeCode node, NodeData data, LocalDateTime now) {
        if (node == NodeCode.PO_CREATE || node == NodeCode.PACKING_RELATE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该节点使用独立来源入口");
        }
        List<Excluded> excluded = new ArrayList<>();
        List<RuleHit> hits = new ArrayList<>();
        Map<String, JsonNode> orders = scopedOrders(data, excluded, now);
        // 财务节点先找RF，再沿items.business_sn核对PO是否属于本次PP范围。
        // 审批责任人依审批日志顺序确定；付款责任人使用配置中的固定角色。
        if (node == NodeCode.RF_APPROVAL || node == NodeCode.RF_PAYMENT) {
            for (Map.Entry<String, JsonNode> document : documents(data, "1_RF").entrySet()) {
                JsonNode rf = document.getValue();
                int expected = node == NodeCode.RF_APPROVAL ? 121 : 1;
                // PRD只要求“待审批/待付款+现结”，未限定请款单类型，因此不按type过滤。
                if (rf.path("status").asInt(-999) != expected || !"现结".equals(text(rf,"settlement_method")))
                    continue;
                List<JsonNode> related = new ArrayList<>();
                for (JsonNode item : array(rf,"items")) {
                    JsonNode po = orders.get(text(item,"business_sn"));
                    if (po != null && allowedCreator(data, text(po,"order_sn"))) related.add(po);
                }
                if (related.isEmpty()) {
                    boolean unknown=false;
                    for (JsonNode item:array(rf,"items")) if (orders.containsKey(text(item,"business_sn")) && creator(data,text(item,"business_sn"))==null) unknown=true;
                    if (unknown) excluded.add(new Excluded(document.getKey(),"关联PO创建人不明确"));
                    continue;
                }
                String sn = document.getKey();
                Entry entry = node == NodeCode.RF_APPROVAL ? creation(data,sn,rf) : entered(data,sn,"待付款",node);
                if (node==NodeCode.RF_APPROVAL && entry!=null) {
                    for (Event event:events(data,sn)) if (Set.of("提交审批","提交审核","重新提交").contains(event.type()))
                        entry=new Entry(entry.time(),fingerprint(event));
                }
                Recipient recipient = node == NodeCode.RF_APPROVAL ? currentFinanceApprover(data,sn) : fixed(node);
                add(hits, excluded, node, sn, "RF", entry, recipient, 1, now);
            }
        } else if (node == NodeCode.CR_RECEIPT) {
            // CR以收货单号催办，创建时间只用明确字段或创建日志，不套用PO时间。
            for (Map.Entry<String, JsonNode> document : documents(data,"sync_receipt:").entrySet()) {
                JsonNode cr = document.getValue();
                String poSn = text(cr,"business_order_sn");
                if (cr.path("status").asInt()!=10 || !orders.containsKey(poSn)) continue;
                if (creator(data,poSn)==null) {excluded.add(new Excluded(document.getKey(),"关联PO创建人缺失"));continue;}
                if (!allowedCreator(data,poSn)) continue;
                add(hits,excluded,node,document.getKey(),"CR",creation(data,document.getKey(),cr),fixed(node),1,now);
            }
        } else {
            // 请款池允许同一PO多行，只把“现结、未申请”的PO放入集合，不重复生成待办。
            Set<String> unapplied = new HashSet<>();
            for (var document : data.documents().entrySet()) {
                if (!document.getKey().startsWith("sync_funds_pool:")) continue;
                for (JsonNode pool : document.getValue()) {
                if (pool.path("pay_status").asInt(-999)==0 && "现结".equals(text(pool,"settlement_method"))) {
                    unapplied.add(text(pool,"order_sn"));
                }
                }
            }
            Set<String> withReceipt = new HashSet<>();
            for (JsonNode cr : documents(data,"sync_receipt:").values()) {
                if (Set.of(10,20,30,40).contains(cr.path("status").asInt())) withReceipt.add(text(cr,"business_order_sn"));
            }
            for (Map.Entry<String,JsonNode> document : orders.entrySet()) {
                String sn = document.getKey(); JsonNode po = document.getValue();
                if (node != NodeCode.CONTRACT_SIGN && creator(data,sn)==null) {
                    excluded.add(new Excluded(sn,"缺少明确PO创建人日志"));continue;
                }
                if (node != NodeCode.CONTRACT_SIGN && !allowedCreator(data,sn)) continue;
                int status = po.path("status").asInt(-999);
                switch (node) {
                    case PO_APPROVAL -> {
                        if (status==121) add(hits,excluded,node,sn,"PO",entered(data,sn,"待审批",node),fixed(node),1,now);
                    }
                    case PO_ORDER -> {
                        // 已有创建中或签署中的合同，交给合同节点催办，避免同一事项两头推送。
                        if (status==1 && !data.contracts().containsKey(sn))
                            add(hits,excluded,node,sn,"PO",entered(data,sn,"待下单",node),creator(data,sn),1,now);
                    }
                    case CONTRACT_SIGN -> {
                        var contract = data.contracts().get(sn);
                        if (contract != null) add(hits,excluded,node,sn,"PO",
                                new Entry(contract.createdAt(),"CONTRACT:"+contract.id()),creator(data,sn),1,now);
                    }
                    case RF_CREATE -> {
                        // PRD只有两个条件：请款池现结未申请 + PO创建人在白名单，不判断PO当前状态。
                        // 只用最近一次明确进入待到货的时间；离开后保留起点，日志缺失不以PO创建时间代替。
                        if (unapplied.contains(sn)) {
                            Entry entry=lastEntered(data,sn,"待到货",node);
                            if (entry == null) {
                                excluded.add(new Excluded(sn,"节点起算时间缺失：没有明确进入待到货的日志"));
                                continue;
                            }
                            add(hits,excluded,node,sn,"PO",entry,creator(data,sn),1,now);
                        }
                    }
                    case DELIVERY_CONFIRM, GOODS_READY -> {
                        if (status==2) deliveryHits(node,data,sn,po,withReceipt.contains(sn),hits,excluded,now);
                    }
                    default -> throw new BusinessException(ResultCode.PARAM_ERROR,"节点规则未登记");
                }
            }
        }
        Map<String,List<Candidate>> grouped = new LinkedHashMap<>();
        Map<String,Recipient> recipients = new LinkedHashMap<>();
        Set<String> unique = new HashSet<>();
        for (RuleHit hit : hits) {
            String key = hit.recipient().key(); Candidate candidate = hit.candidate();
            if (!unique.add(key+"|"+candidate.businessSn()+"|"+candidate.roundKey()+"|"+candidate.stage())) continue;
            grouped.computeIfAbsent(key, ignored->new ArrayList<>()).add(candidate);recipients.put(key,hit.recipient());
        }
        List<RecipientGroup> groups = grouped.entrySet().stream().map(entry->{
            Recipient person=recipients.get(entry.getKey());
            return new RecipientGroup(person.uid(),person.name(),List.copyOf(entry.getValue()),person.key());
        }).toList();
        return new SopReminderPreview(node.name(),node.title(),now,data.published().taskId(),data.published().syncedAt(),
                List.of(),groups.stream().mapToInt(group->group.candidates().size()).sum(),groups,excluded.stream().distinct().toList());
    }

    private void deliveryHits(NodeCode node, NodeData data, String sn, JsonNode po, boolean hasCr,
                              List<RuleHit> hits,List<Excluded> excluded,LocalDateTime now) {
        Entry entry=entered(data,sn,"待到货",node);
        if (entry==null) { excluded.add(new Excluded(sn,"缺少本轮进入待到货的明确日志"));return; }
        // 同一PO的每个有效SKU都要检查人工导入结果；不能只用第一行SKU决定整单。
        Set<String> skus=new HashSet<>();
        for (JsonNode item:array(po,"item_list")) {
            if (item.path("is_delete").asInt()==0 && !text(item,"sku").isBlank()) skus.add(text(item,"sku"));
        }
        if (skus.isEmpty()) { excluded.add(new Excluded(sn,"没有有效采购SKU"));return; }
        List<DeliveryFollowUp> rows=new ArrayList<>();
        for (String sku:skus) {
            DeliveryFollowUp follow=data.followUps().get(sn+"|"+sku);
            if (follow==null || follow.followerName()==null || follow.followerName().isBlank()) {
                //未导入跟单员的SKU只跳过自己：既不阻断该PO的其他跟单员，也不单独催办。
                excluded.add(new Excluded(sn,"SKU "+sku+"缺少跟单员，本SKU不催办"));continue;
            }
            rows.add(follow);
        }
        if (rows.isEmpty()) return;
        //同一PO可能由多人分头负责不同SKU，按跟单员拆开，各自只收自己那部分，消息里带同一个PO单号。
        Map<String,List<DeliveryFollowUp>> byFollower=new LinkedHashMap<>();
        for (DeliveryFollowUp row:rows) byFollower.computeIfAbsent(row.followerName(),key->new ArrayList<>()).add(row);
        // 天数门槛全部来自Nacos，节点未配置时直接报错，不用代码默认值兜底。
        SopReminderProperties.Node rule=properties.getNodes().get(node.name());
        if (rule==null) throw new BusinessException(ResultCode.PARAM_ERROR,"节点未配置："+node.name());
        if (node==NodeCode.DELIVERY_CONFIRM) {
            int waitDays=rule.getDeliveryDueDays();
            // 进入待到货满N天才提醒；只有缺交期的SKU对应跟单员收到交期确认提醒，同一人只发一条。
            if (Duration.between(entry.time(),now).compareTo(Duration.ofDays(waitDays))<0) return;
            for (Map.Entry<String,List<DeliveryFollowUp>> group:byFollower.entrySet()) {
                if (group.getValue().stream().anyMatch(row->row.expectedDeliveryDate()==null))
                    addFollower(hits,excluded,sn,entry,group.getKey(),1,now);
            }
            return;
        }
        int firstDays=rule.getFirstDueDays();
        int secondDays=rule.getSecondDueDays();
        for (Map.Entry<String,List<DeliveryFollowUp>> group:byFollower.entrySet()) {
            List<DeliveryFollowUp> own=group.getValue();
            //该跟单员名下还有SKU没填交期，无法判断阶段：本轮跳过，等交期补齐再催。
            if (own.stream().anyMatch(row->row.expectedDeliveryDate()==null)) continue;
            // 同一人多个SKU有不同交期时取最早的交期，避免较晚的一行掩盖已经临近交期的SKU。
            LocalDate earliest=own.stream().map(DeliveryFollowUp::expectedDeliveryDate).min(Comparator.naturalOrder()).orElse(null);
            if (earliest==null) continue;
            Duration remaining=Duration.between(now,earliest.atStartOfDay());
            if (remaining.compareTo(Duration.ofDays(firstDays))>0) continue;
            // ≤secondDays（包含已逾期）直接进入第二阶段；不在同一轮补发第一阶段。
            // 是否已发过由发送仓储按阶段判断，不在这里修改数据库。
            int stage=remaining.compareTo(Duration.ofDays(secondDays))<=0?2:1;
            if (stage==2 && hasCr) continue;
            addFollower(hits,excluded,sn,entry,group.getKey(),stage,now);
        }
    }

    /** 同一PO按跟单员拆成多条待办：item_key加F:前缀区分，避免唯一键互相覆盖。 */
    private void addFollower(List<RuleHit> hits,List<Excluded> excluded,String sn,
                             Entry entry,String follower,int stage,LocalDateTime now) {
        if (entry==null || entry.time()==null || entry.time().isAfter(now)) {
            excluded.add(new Excluded(sn,"缺少明确的本轮节点时间或时间晚于当前"));return;
        }
        if (follower==null || follower.isBlank()) { excluded.add(new Excluded(sn,"跟单员不明确"));return; }
        BigDecimal hours=BigDecimal.valueOf(Duration.between(entry.time(),now).getSeconds())
                .divide(BigDecimal.valueOf(3600),2,RoundingMode.HALF_UP);
        hits.add(new RuleHit(new Candidate(sn,0,null,entry.time(),hours,"PO","F:"+follower,entry.key(),stage),
                new Recipient(0,follower,"name:"+follower)));
    }

    private Map<String,JsonNode> scopedOrders(NodeData data,List<Excluded> excluded,LocalDateTime now) {
        Set<String> roots=new HashSet<>();
        Set<String> uncertainRoots=new HashSet<>();
        Map<String,JsonNode> planDocuments=documents(data,"1_PP");
        for (var document:planDocuments.entrySet()) {
            Entry created=creation(data,document.getKey(),document.getValue());
            if (created==null || created.time().isAfter(now)) {
                uncertainRoots.add(document.getKey());
                excluded.add(new Excluded(document.getKey(),"PP创建时间缺失或晚于当前时间"));
            }
            else if (!created.time().toLocalDate().isBefore(properties.getPpStartDate())) roots.add(document.getKey());
        }
        Map<String,JsonNode> result=new LinkedHashMap<>();
        for (var document:documents(data,"1_PO").entrySet()) {
            JsonNode po=document.getValue();boolean related=false,uncertain=false,foundPlan=false;
            for (JsonNode item:array(po,"item_list")) {
                String planSn=text(item,"plan_sn");
                if (planDocuments.containsKey(planSn)) foundPlan=true;
                if (roots.contains(planSn)) related=true;
                if (!planSn.isBlank() && (!planDocuments.containsKey(planSn) || uncertainRoots.contains(planSn))) uncertain=true;
                JsonNode plans=item.path("relation_purchase_plan");
                if (plans.isTextual()) {
                    try { plans=objectMapper.readTree(plans.asText()); }
                    catch (java.io.IOException exception) {throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE,"PO关联PP格式异常");}
                }
                if (plans.isArray()) for (JsonNode plan:plans) {
                    String linked=plan.asText();
                    if (planDocuments.containsKey(linked)) foundPlan=true;
                    if (roots.contains(linked)) related=true;
                    if (!linked.isBlank() && (!data.documents().containsKey("1_"+linked) || uncertainRoots.contains(linked))) uncertain=true;
                }
            }
            if (!related && foundPlan) {
                if (uncertain) excluded.add(new Excluded(document.getKey(),"关联PP创建时间不明，暂不关闭原待办"));
                continue;
            }
            // 无关联PP或关联PP无法找到时，使用PO自己的明确创建时间判断范围。
            // 即使找到了范围内PP，PO自身也必须在起始日当天及以后创建。
            Entry poCreated=creation(data,document.getKey(),po);
            if (poCreated==null || poCreated.time().isAfter(now)) {
                excluded.add(new Excluded(document.getKey(),"PO创建时间缺失或晚于当前时间，暂不关闭原待办"));
                continue;
            }
            if (!poCreated.time().toLocalDate().isBefore(properties.getPpStartDate()))
                result.put(document.getKey(),po);
        }
        return result;
    }

    private Map<String,JsonNode> documents(NodeData data,String prefix) {
        Map<String,JsonNode> result=new LinkedHashMap<>();
        data.documents().forEach((key,rows)->{
            if (!key.startsWith(prefix)) return;
            for (JsonNode row:rows) {
                String sn=text(row,prefix.equals("1_PP")?"plan_sn":"order_sn");
                if (sn.isBlank()) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE,"单据缺少业务单号");
                result.putIfAbsent(sn,row);
            }
        });
        return result;
    }

    private boolean allowedCreator(NodeData data,String sn) {
        Recipient person=creator(data,sn);
        if (person == null) return false;
        // 领星姓名可能是Caigou-李静、HZ-CG-李静ABC等；忽略字母、数字、空格和分隔符。
        // 中文姓名仍须完整相等，不能把“李静静”误认成白名单中的“李静”。
        String chineseName = person.name().replaceAll("[^\\p{IsHan}]", "");
        return properties.getAllowedPoCreators().stream()
                .anyMatch(name -> name != null && !name.isBlank() && chineseName.equals(name.trim()));
    }
    private Recipient creator(NodeData data,String sn) {
        List<Event> created=events(data,sn).stream().filter(event->"创建".equals(event.type())).toList();
        if (created.isEmpty()) return null;
        Event first=created.get(0);
        if (first.uid()==null || first.uid()<=0 || first.actor()==null || first.actor().isBlank()) return null;
        if (created.stream().anyMatch(event->!java.util.Objects.equals(event.uid(),first.uid()))) return null;
        return new Recipient(first.uid(),first.actor(),"uid:"+first.uid());
    }
    private Recipient fixed(NodeCode node) {
        SopReminderProperties.Node rule=properties.getNodes().get(node.name());
        return rule==null || rule.getReceiverKey()==null?null:new Recipient(0,rule.getReceiverKey(),"role:"+rule.getReceiverKey());
    }
    private Entry creation(NodeData data,String sn,JsonNode row) {
        LocalDateTime explicit=dateTime(text(row,"create_time"));
        if (explicit!=null) return new Entry(explicit,"CREATE:"+explicit);
        return events(data,sn).stream().filter(event->"创建".equals(event.type()) && event.time()!=null)
                .findFirst().map(event->new Entry(event.time(),fingerprint(event))).orElse(null);
    }
    private Entry entered(NodeData data,String sn,String target,NodeCode node) {
        String current=null;Entry entered=null;
        SopReminderProperties.Node rule=properties.getNodes().get(node.name());
        for (Event event:events(data,sn)) {
            if (event.time()==null) continue;
            String detail=plain(event.detail());Matcher matcher=STATE_CHANGE.matcher(detail);
            boolean stateFound=matcher.find();
            if (!stateFound && !"编辑".equals(event.type())) {
                matcher=STATE_DECLARATION.matcher(detail);
                stateFound=matcher.find();
            }
            if (stateFound) {
                String to=matcher.group(1).trim();if ("待审核".equals(to)) to="待审批";
                if (target.equals(to) && !target.equals(current)) {
                    // “审批通过、仍待审批”只代表中间审批人通过，不能重置进入审批起点。
                    entered="待审批".equals(target) && "审批".equals(event.type())?null:new Entry(event.time(),fingerprint(event));
                }
                if (!target.equals(to)) entered=null;
                current=to;
            } else if (rule!=null && rule.getEntryLogTypes().contains(event.type())) {
                entered=new Entry(event.time(),fingerprint(event));current=target;
            }
        }
        return entered;
    }
    /** 最近一次进入目标状态的时间：PO后续离开该状态也不清空，只用于计算卡滞起点。 */
    private Entry lastEntered(NodeData data,String sn,String target,NodeCode node) {
        Entry latest=null;
        SopReminderProperties.Node rule=properties.getNodes().get(node.name());
        for (Event event:events(data,sn)) {
            if (event.time()==null) continue;
            String detail=plain(event.detail());Matcher matcher=STATE_CHANGE.matcher(detail);
            boolean stateFound=matcher.find();
            if (!stateFound && !"编辑".equals(event.type())) {
                matcher=STATE_DECLARATION.matcher(detail);
                stateFound=matcher.find();
            }
            if (stateFound) {
                String to=matcher.group(1).trim();if ("待审核".equals(to)) to="待审批";
                if (target.equals(to)) latest=new Entry(event.time(),fingerprint(event));
            } else if (rule!=null && rule.getEntryLogTypes().contains(event.type())) {
                latest=new Entry(event.time(),fingerprint(event));
            }
        }
        return latest;
    }
    private Recipient currentFinanceApprover(NodeData data,String sn) {
        // 配置只说明审批顺序，不能代替“前一人已经审批通过”的业务证据。
        // 乱序、驳回或审批人不在已确认链中时，返回缺失交给预览列出，不猜下一人。
        List<Long> chain=properties.getFinanceApprovalUids();
        if (chain.size()!=3 || new HashSet<>(chain).size()!=3 || chain.stream().anyMatch(uid->uid==null || uid<=0)) return null;
        Set<Long> passed=new HashSet<>();boolean invalid=false;
        for (Event event:events(data,sn)) {
            String detail=plain(event.detail());
            if ("创建".equals(event.type()) || Set.of("提交审批","提交审核","重新提交").contains(event.type())) {passed.clear();invalid=false;}
            if (!"审批".equals(event.type())) continue;
            if (!detail.matches("(?s).*审批结果\\s*[:：]\\s*通过.*") || !chain.contains(event.uid())) {invalid=true;continue;}
            int index=chain.indexOf(event.uid());
            for (int i=0;i<index;i++) if (!passed.contains(chain.get(i))) invalid=true;
            passed.add(event.uid());
        }
        if (invalid) return null;
        for (Long uid:chain) if (!passed.contains(uid)) return new Recipient(uid,"审批人UID:"+uid,"uid:"+uid);
        return null;
    }
    private void add(List<RuleHit> hits,List<Excluded> excluded,NodeCode node,String sn,String kind,
                     Entry entry,Recipient recipient,int stage,LocalDateTime now) {
        if (entry==null || entry.time()==null || entry.time().isAfter(now)) {
            excluded.add(new Excluded(sn,"缺少明确的本轮节点时间或时间晚于当前"));return;
        }
        if (recipient==null) { excluded.add(new Excluded(sn,"创建人、跟单员或当前审批责任人不明确"));return; }
        BigDecimal hours=BigDecimal.valueOf(Duration.between(entry.time(),now).getSeconds()).divide(BigDecimal.valueOf(3600),2,RoundingMode.HALF_UP);
        hits.add(new RuleHit(new Candidate(sn,0,null,entry.time(),hours,kind,"",entry.key(),stage),recipient));
    }
    private List<Event> events(NodeData data,String sn) { return data.logs().getOrDefault("1_"+sn,List.of()); }
    private static String text(JsonNode value,String field) { return value.path(field).isNull()?"":value.path(field).asText(""); }
    private static List<JsonNode> array(JsonNode value,String field) {
        JsonNode rows=value.path(field);if (!rows.isArray()) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE,"单据明细缺失："+field);
        List<JsonNode> result=new ArrayList<>();rows.forEach(result::add);return result;
    }
    private static LocalDateTime dateTime(String text) {
        if (text.isBlank() || "-".equals(text)) return null;
        try {return LocalDateTime.parse(text.replace(' ','T'));}
        catch (java.time.format.DateTimeParseException exception) {return null;}
    }
    private static String plain(String html) {return html==null?"":html.replaceAll("(?i)<br\\s*/?>","\n").replaceAll("<[^>]+>","").replace("&nbsp;"," ").trim();}
    private static String fingerprint(Event event) {
        try {return "LOG:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch (java.security.NoSuchAlgorithmException exception) {throw new IllegalStateException(exception);}
    }
}
