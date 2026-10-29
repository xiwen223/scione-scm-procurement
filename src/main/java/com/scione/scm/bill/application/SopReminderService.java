package com.scione.scm.bill.application;

import com.scione.scm.bill.infrastructure.lingxing.LingxingWebSellerClient;
import com.scione.scm.bill.infrastructure.lingxing.LingxingOpenApiClient;
import com.scione.scm.bill.config.SopReminderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.domain.sop.enums.NodeCode;
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
    // JSON解析工具：解析PO里"关联采购计划"这类JSON字符串字段。
    private final ObjectMapper objectMapper;
    // 数据来源四：调用领星开放API实时拉装箱任务数据（装箱节点实时查询，不读同步快照）。
    private final LingxingOpenApiClient openApiClient;

    /** 判断节点码是否合法：能转成NodeCode枚举就是已实现的节点，否则false。 */
    public boolean supportsNode(String nodeCode) {//这个方法就是去校验一下nacos中配的nodes名和枚举名也没有对上
        try { NodeCode.valueOf(nodeCode); return true; }//能转成枚举，说明是合法节点
        catch (IllegalArgumentException | NullPointerException exception) { return false; }//转不了或为null，非法
    }

    /** 两种场景：1.补发单条消息前复核旧消息还该不该发。2.po未创建和装箱这两个特例节点由preview计算候选节点。 */
    public SopReminderPreview preview(String nodeCode) {
        if (!supportsNode(nodeCode)) {//复核节点是否非法
            throw new BusinessException(ResultCode.PARAM_ERROR, "尚未实现的催办节点：" + nodeCode);
        }
        //其中11个节点要分成三类，1.po单创建未完成节点要查杭州店铺。2.装箱任务节点要实时查领星。3.其他普通节点。三类要分别处理
        if ("PO_CREATE".equals(nodeCode)) return previewPoCreate();
        if ("PACKING_RELATE".equals(nodeCode)) return previewPacking();
        return previewStandard(NodeCode.valueOf(nodeCode));
    }

    /** 一次发送调用内复用来源快照；不跨请求缓存，装箱始终走独立实时入口。 */
    public Map<String, SopReminderPreview> previewNodes(List<String> codes) {
        Map<String, SopReminderPreview> previews = new LinkedHashMap<>();//为了组装消息时顺序和配置一致用LinkedHashMap
        NodeData shared = null;//共享快照，读完之后有值，这个值普通节点共用
        LocalDateTime evaluatedAt = null;//统一时间基准
        for (String code : codes) {
            if (!supportsNode(code)) throw new BusinessException(ResultCode.PARAM_ERROR, "尚未实现的催办节点：" + code);//对逐个节点进行合法性校验
            if ("PO_CREATE".equals(code) || "PACKING_RELATE".equals(code)) {//po未创建和装箱任务节点走preview(code) 分派到各自的专属方法
                previews.put(code, preview(code));
            } else {
                if (shared == null) {//只有第一个普通节点去读库并且定下evaluatedAt，后面其他节点就不读库，直接用现成的shared 和 evaluatedAt
                    shared = repository.readNodeData(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
                    evaluatedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                }
                previews.put(code, evaluateStandard(NodeCode.valueOf(code), shared, evaluatedAt));//把共享数据和时间作为参数传进去，算这个节点的候选。
            }
        }
        return previews;//把算好的Map<节点, 候选> 交给调用方
    }

    /** 节点1专用入口：实时查领星杭州店铺，读PP快照，算出"PO还没创建"的候选。 */
    private SopReminderPreview previewPoCreate() {
        //从 Nacos 拿po单未创建节点的配置，取 shop-name-prefix（即"杭州"）和 pp-start-date（2026-09-01）；
        SopReminderProperties.Node node = properties.getNodes().get("PO_CREATE");
        String prefix = node == null ? null : node.getShopNamePrefix();
        if (prefix == null || prefix.isBlank() || properties.getPpStartDate() == null) {
            throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "节点1店铺前缀和PP范围日期不能为空");
        }
        //findSellers()实时调领星网页接口拿全量店铺；筛选杭州开头的，存成 sid → 店铺名；然后将shopIds排序后转列表
        Map<Long, String> shops = new LinkedHashMap<>();
        for (LingxingWebSellerClient.Seller seller : sellerClient.findSellers()) {
            if (seller.name().startsWith(prefix)) shops.put(seller.sid(), seller.name());
        }
        List<Long> shopIds = shops.keySet().stream().sorted().toList();
        //先取一次当前时间，拿杭州店铺的 sid 列表去查 PP 快照（内部会等同步锁、校验数据时效）
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        SopReminderPreview.Snapshot snapshot = repository.readPoCreate(shopIds, now);
        //重新取now计算卡滞时间
        now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        // excluded保存不能参与本次计算的单据及原因，供联调核对，不当成正常候选。
        List<Excluded> excluded = new ArrayList<>();
        // grouped示例：采购员UID 1001 → [PP001、PP002]；同一个人名不一定唯一，所以用UID分组。
        Map<Long, List<Candidate>> grouped = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        // 一个PP的多个SKU/店铺归同一采购员时只催一次；不同采购员分别收到自己的候选。
        Set<String> acceptedPlans = new HashSet<>();
        for (PlanRow row : snapshot.plans()) {
            // 逐单检查数据。创建时间为null，>now,<2026-09-01的，采购员不明，不是杭州店铺的排除。
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
                //去重集合，同一采购员 + 同一 PP 的多个 SKU/店铺，只催一次。key 是 采购员UID:PP号，重复的直接 continue
                if (!acceptedPlans.add(row.purchaserUid() + ":" + row.planSn())) continue;
                // 卡滞时长=now-pp创建时间，按小时两位小数
                BigDecimal hours = BigDecimal.valueOf(Duration.between(row.createdAt(), now).getSeconds())
                        .divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
                //grouped：采购员UID->候选列表，names:UID->展示名
                grouped.computeIfAbsent(row.purchaserUid(), ignored -> new ArrayList<>())
                        .add(new Candidate(row.planSn(), row.sid(), shops.get(row.sid()), row.createdAt(), hours));
                names.putIfAbsent(row.purchaserUid(), row.purchaserName() == null || row.purchaserName().isBlank()
                        ? "领星UID:" + row.purchaserUid() : row.purchaserName());
            }
        }
        //采购员按照UID排序，每个采购员内部按创建时间从早到晚排。
        List<RecipientGroup> groups = grouped.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new RecipientGroup(entry.getKey(), names.get(entry.getKey()),
                        entry.getValue().stream().sorted(Comparator.comparing(Candidate::ppCreatedAt)
                                .thenComparing(Candidate::businessSn)).toList()))
                .toList();//把每个采购员的一组候选，转成RecipientGroup(uid=采购员领星UID，name=展示名，candidates=该采购员的候选，key=uid:xxx)
        //返回计算结果，一次性拼出 SopReminderPreview：节点码、中文名、计算基准时间、同步任务 id/完成时间、杭州店铺列表、候选总数（各采购员候选之和，不含 excluded）、分组清单、排除清单；
        return new SopReminderPreview("PO_CREATE", "PO创建未完成", now, snapshot.taskId(), snapshot.syncedAt(), shopIds,
                groups.stream().mapToInt(group -> group.candidates().size()).sum(), groups, excluded.stream().distinct().toList());
    }

    //实时调领星拉装箱数据，创建批次号时间从packing-start-date（2026-10-01）开始；拿到 PackingSource（含 batches 批次列表 + tasks 装箱任务列表）后转给 evaluatePacking 真正算。
    private SopReminderPreview previewPacking() {
        LingxingOpenApiClient.PackingSource source=openApiClient.fetchReminderPackingForPreview(
                properties.getPackingStartDate());
        return evaluatePacking(source);
    }

    /** 计算装箱任务节点的候选 */
    public SopReminderPreview evaluatePacking(LingxingOpenApiClient.PackingSource source) {
        List<Excluded> excluded=new ArrayList<>();//排除的批次及原因
        Map<String,JsonNode> batches=new LinkedHashMap<>();//父批次map：key=批次号seq，value=批次JSON，用于按批次号查批次详情
        //把父批次按 seq（批次号）放进 map，同时用putIfAbsent查重，有重复就停
        for (JsonNode row:source.batches()) {
            String sn=text(row,"seq");//取父批次的批次号
            if (sn.isBlank() || batches.putIfAbsent(sn,row)!=null)
                throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE,"发货计划父批次缺失或重复");//批次号为空或重复，数据异常直接停
        }
        Map<String,List<JsonNode>> tasks=new LinkedHashMap<>();//装箱任务map：key=关联的父批次号，value=该批次下的装箱任务列表
        //领星的装箱任务有多种类型，relateSnType != 1 的（不是关联到发货计划的）跳过不管，剩下的按关联的父批次号 relateSn 分组，得到 tasks: 批次号 → 该批次下的装箱任务列表
        for (JsonNode task : source.tasks()) {
            if (task.path("relateSnType").asInt() != 1) continue;//relateSnType不是1，说明关联的不是发货计划，跳过
            String batchSn = text(task, "relateSn");//取这个装箱任务关联的发货计划批次号
            if (!batchSn.startsWith("RP") || !task.path("relateShipment").isArray()) {
                throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "装箱任务的父批次或货件列表结构异常");//批次号不是RP开头或货件列表不是数组，结构异常停
            }
            tasks.computeIfAbsent(batchSn, ignored -> new ArrayList<>()).add(task);//把任务归到它关联的批次号下
        }
        //循环遍历每个批次，查有没有已完成装箱的，只要有一个已完成就行。1.去查找发货计划批次号详情并校验批次号创建时间
        Map<String,List<Candidate>> grouped=new LinkedHashMap<>();//按创建人分组的结果：key=创建人姓名，value=该创建人的待催批次列表
        for (var entry:tasks.entrySet()) {
            String sn=entry.getKey();//当前批次的批次号
            if (entry.getValue().stream().noneMatch(task->task.path("taskStatus").asInt()==3)) continue;//这批任务里一个已完成(taskStatus==3)的都没有，跳过
            JsonNode batch=batches.get(sn);//按批次号取父批次详情
            if (batch==null) {excluded.add(new Excluded(sn,"未找到发货计划批次号详情"));continue;}//找不到父批次，记排除
            LocalDateTime created=dateTime(text(batch,"create_time"));//取父批次创建时间
            if (created==null) {excluded.add(new Excluded(sn,"父批次创建时间缺失"));continue;}//创建时间缺失，记排除
            if (created.toLocalDate().isBefore(properties.getPackingStartDate())) continue;//创建时间早于packing-start-date，太老不催
            //取批次号下的子计划列表，看有没有未关联货件的子计划
            List<JsonNode> children=array(batch,"list");//父批次的子计划列表
            boolean unlinked=false;//有没有未关联货件的子计划
            boolean invalid=children.isEmpty();//数据是否矛盾；子计划为空本身就是异常
            // 遍历子计划，看它是否关联货件
            for (JsonNode child:children) {
                String flag=text(child,"is_relate_mws");//子计划的"是否关联货件"标记：0未关联 1已关联
                if (!Set.of("0","1").contains(flag)) invalid=true;//标记不是0也不是1，脏数据
                if ("0".equals(flag)) {//这条子计划没关联货件
                    unlinked=true;//记下"有未关联的"
                    JsonNode shipments = child.path("mws_relate");//它关联的货件列表
                    if (text(child,"order_sn").isBlank() || !shipments.isArray() || !shipments.isEmpty()) invalid=true;//订单号为空、货件列表不是数组、或标未关联却挂着货件，都是矛盾
                }
            }
            if (invalid) {excluded.add(new Excluded(sn,"子计划关联状态缺失或与货件列表矛盾"));continue;}//标着未关联却又挂着货件，数据矛盾排除
            if (!unlinked) continue;//所有子计划都关联好了，不催
            //找发货计划批次号的创建人并且校验创建时间
            String creator=text(batch,"create_user");//父批次创建人（最终收件人的姓名）
            if (creator.isBlank() || created.isAfter(source.checkedAt())) {excluded.add(new Excluded(sn,"父批次创建人或时间异常"));continue;}//创建人为空或创建时间晚于检查时刻，排除
            //计算卡滞时间=检查时刻-批次创建时间，两位小数，按创建人creator分组，一个创建人名下挂所有他的催办批次
            BigDecimal hours=BigDecimal.valueOf(Duration.between(created,source.checkedAt()).getSeconds())
                    .divide(BigDecimal.valueOf(3600),2,RoundingMode.HALF_UP);
            grouped.computeIfAbsent(creator,ignored->new ArrayList<>()).add(new Candidate(sn,0,null,created,hours,
                    "RP","","BATCH:"+sn,1));//按创建人分组，把该批次作为一个候选塞进去
        }
        //按创建人转成 RecipientGroup 列表(装箱没有领星UID，用0占位；创建人姓名；创建人的候选列表；name：人名)
        List<RecipientGroup> groups=grouped.entrySet().stream().map(entry->new RecipientGroup(0,entry.getKey(),
                List.copyOf(entry.getValue()),"name:"+entry.getKey())).toList();
        //拼成SopReminderPreview返回(节点编码，节点中文名，基准时间，同步任务id，同步任务成功结束的时间，杭州店铺sid，有效候选总数，按责任人汇总后的候选清单，排除的问题单据+原因)
        return new SopReminderPreview("PACKING_RELATE",NodeCode.PACKING_RELATE.title(),source.checkedAt(),0,
                source.checkedAt(),List.of(),groups.stream().mapToInt(group->group.candidates().size()).sum(),groups,excluded);
    }

    private record Entry(LocalDateTime time, String key) { }//time是卡滞时间的起算点，key表示这条待办是哪份合同/哪个批次/哪个阶段的
    /** 规则计算中间态的待定收件人：uid是领星UID（装箱/角色没有就传0），name是姓名，key是找人的键（uid:xxx/role:xxx/name:xxx）。 */
    private record Recipient(long uid, String name, String key) { }
    /** 一条候选与它的收件人的配对：candidate是该催的单，recipient是该发给谁，存进hits列表后再按收件人分组。 */
    private record RuleHit(Candidate candidate, Recipient recipient) { }
    /** 从领星操作日志文字里解析单据状态变更为【xxx】的状态名，用于确定单据进入节点的时间。 */
    private static final Pattern STATE_CHANGE = Pattern.compile(
            "(?:单据)?状态(?:变更为|变为|调整为|修改为|改为)\\s*[:：]?\\s*【?([^\\s，,。;；<】]+)");
    // 真实领星日志示例：提交采购单【PO...】，单据状态为【待审批】。
    private static final Pattern STATE_DECLARATION = Pattern.compile(
            "(?:^|[，,。;；\\n])\\s*单据状态为\\s*【([^】]+)】");

    /** 普通节点入口：读一次共享快照，交给纯计算方法evaluateStandard算候选。 */
    private SopReminderPreview previewStandard(NodeCode node) {
        NodeData data = repository.readNodeData(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));//读一次共享快照（内部会等同步锁）
        return evaluateStandard(node, data, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));//把快照和当前时间交给纯计算方法evaluateStandard
    }

    /** 纯规则计算入口 */
    public SopReminderPreview evaluateStandard(NodeCode node, NodeData data, LocalDateTime now) {
        if (node == NodeCode.PO_CREATE || node == NodeCode.PACKING_RELATE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该节点使用独立来源入口");//这两个特例节点有独立入口，不能走这里
        }
        List<Excluded> excluded = new ArrayList<>();//排除的问题单据
        List<RuleHit> hits = new ArrayList<>();//命中的候选（中间态，最后按收件人分组）
        Map<String, JsonNode> orders = scopedOrders(data, excluded, now);//筛出PP范围内的PO单，key=PO单号 value=PO的JSON
        // 财务节点先找RF，再沿items.business_sn核对PO是否属于本次PP范围。审批责任人依审批日志顺序确定；付款责任人使用配置中的固定角色。
        if (node == NodeCode.RF_APPROVAL || node == NodeCode.RF_PAYMENT) {
            //遍历领星同步过来的所有RF请款单，逐个判断是否符合该财务节点的催办条件。
            for (Map.Entry<String, JsonNode> document : documents(data, "1_RF").entrySet()) {
                JsonNode rf = document.getValue();
                int expected = node == NodeCode.RF_APPROVAL ? 121 : 1;//审批节点要状态121(待审批)，付款节点要状态1(待付款)
                // 要求“待审批/待付款+现结”
                if (rf.path("status").asInt(-999) != expected || !"现结".equals(text(rf,"settlement_method")))
                    continue;//状态不对或不是现结，跳过这条RF
                //顺着RF的items逐条找它关联的PO，只保留"在本次PP范围内且创建人在白名单"的PO。
                List<JsonNode> related = new ArrayList<>();//该RF关联到的、有效范围内的PO列表
                for (JsonNode item : array(rf,"items")) {
                    JsonNode po = orders.get(text(item,"business_sn"));//按items里的business_sn去orders里找对应的PO
                    if (po != null && allowedCreator(data, text(po,"order_sn"))) related.add(po);//PO存在且创建人在白名单，才纳入related
                }
                //一条RF关联不到任何有效PO时，不催；但若因"创建人不明"导致，要记excluded供排查。
                if (related.isEmpty()) {
                    boolean unknown=false;//是否因为"创建人不明确"导致的空
                    for (JsonNode item:array(rf,"items")) if (orders.containsKey(text(item,"business_sn")) && creator(data,text(item,"business_sn"))==null) unknown=true;//PO在范围但创建人查不到，标记unknown
                    if (unknown) excluded.add(new Excluded(document.getKey(),"关联PO创建人不明确"));//记排除原因供排查
                    continue;
                }
                String sn = document.getKey();
                //审批节点按"创建时间"起算，付款节点按"进入待付款"起算。
                Entry entry = node == NodeCode.RF_APPROVAL ? creation(data,sn,rf) : entered(data,sn,"待付款",node);
                //审批节点：若有"审批/重新提交"日志，用该日志指纹作为待办唯一标识（roundKey）。
                if (node==NodeCode.RF_APPROVAL && entry!=null) {
                    for (Event event:events(data,sn)) if (Set.of("审批","重新提交").contains(event.type()))
                        entry=new Entry(entry.time(),fingerprint(event));
                }
                //审批节点找"当前该轮到的审批人"，付款节点用固定角色。
                Recipient recipient = node == NodeCode.RF_APPROVAL ? currentFinanceApprover(data,sn) : fixed(node);
                add(hits, excluded, node, sn, "RF", entry, recipient, 1, now);
            }
        } else if (node == NodeCode.CR_RECEIPT) {
            // CR以收货单号催办，创建时间只用明确字段或创建日志。
            for (Map.Entry<String, JsonNode> document : documents(data,"sync_receipt:").entrySet()) {
                JsonNode cr = document.getValue();
                String poSn = text(cr,"business_order_sn");//CR关联的PO单号
                if (cr.path("status").asInt()!=10 || !orders.containsKey(poSn)) continue;//状态不是10(待收货)或关联PO不在范围，跳过
                if (creator(data,poSn)==null) {excluded.add(new Excluded(document.getKey(),"关联PO创建人缺失"));continue;}//创建人不明记excluded
                if (!allowedCreator(data,poSn)) continue;//创建人不在白名单，跳过
                add(hits,excluded,node,document.getKey(),"CR",creation(data,document.getKey(),cr),fixed(node),1,now);//收件人是固定收货角色
            }
        } else {
            // 请款池允许同一PO多行，只把“现结、未申请”的PO放入集合，不重复生成待办。
            Set<String> unapplied = new HashSet<>();//存"现结且未申请"的PO单号，给RF_CREATE节点用
            for (var document : data.documents().entrySet()) {
                if (!document.getKey().startsWith("sync_funds_pool:")) continue;//只处理请款池数据
                for (JsonNode pool : document.getValue()) {
                if (pool.path("pay_status").asInt(-999)==0 && "现结".equals(text(pool,"settlement_method"))) {
                    unapplied.add(text(pool,"order_sn"));//付款状态0(未付款)且现结，视为"未申请请款"
                }
                }
            }
            Set<String> withReceipt = new HashSet<>();//存"已经有收货单"的PO单号，给GOODS_READY判断是否已建CR用
            for (JsonNode cr : documents(data,"sync_receipt:").values()) {
                if (Set.of(10,20,30,40).contains(cr.path("status").asInt())) withReceipt.add(text(cr,"business_order_sn"));//收货单状态10（待收货）/20（中间状态）/30（中间状态）/40（中间状态）都算"已建CR"
            }
            //遍历每个范围内的PO单，按节点类型走不同的case判断。
            for (Map.Entry<String,JsonNode> document : orders.entrySet()) {
                String sn = document.getKey(); JsonNode po = document.getValue();
                //合同节点不要求创建人，其余节点必须先有明确创建人日志。
                if (node != NodeCode.CONTRACT_SIGN && creator(data,sn)==null) {
                    excluded.add(new Excluded(sn,"缺少明确PO创建人日志"));continue;
                }
                //合同节点不要求白名单，其余节点的创建人必须在白名单内。
                if (node != NodeCode.CONTRACT_SIGN && !allowedCreator(data,sn)) continue;
                int status = po.path("status").asInt(-999);//PO当前状态
                switch (node) {
                    case PO_APPROVAL -> {
                        if (status==121) add(hits,excluded,node,sn,"PO",entered(data,sn,"待审批",node),fixed(node),1,now);//状态121=待审批，收件人是固定审批角色
                    }
                    case PO_ORDER -> {
                        // 已有创建中或签署中的合同，交给合同节点催办，避免同一事项两头推送。
                        if (status==1 && !data.contracts().containsKey(sn))//状态1=待下单，且还没有合同
                            add(hits,excluded,node,sn,"PO",entered(data,sn,"待下单",node),creator(data,sn),1,now);//收件人是PO创建人
                    }
                    case CONTRACT_SIGN -> {
                        var contract = data.contracts().get(sn);//这边通过SQL去筛选创建/签署中两种状态的合同
                        if (contract != null) add(hits,excluded,node,sn,"PO",
                                new Entry(contract.createdAt(),"CONTRACT:"+contract.id()),creator(data,sn),1,now);//起算点是合同创建时间，roundKey是合同id
                    }
                    case RF_CREATE -> {
                        // 请款池现结未申请 + PO创建人在白名单，不判断PO当前状态。
                        // 只用最近一次明确进入待到货的时间；离开后保留起点。
                        if (unapplied.contains(sn)) {//该PO在请款池里"现结未申请"
                            Entry entry=lastEntered(data,sn,"待到货",node);//取最近一次进入待到货的时间作为起算点
                            if (entry == null) {
                                excluded.add(new Excluded(sn,"节点起算时间缺失：没有明确进入待到货的日志"));//没有明确的待到货日志，不猜，排除
                                continue;
                            }
                            add(hits,excluded,node,sn,"PO",entry,creator(data,sn),1,now);
                        }
                    }
                    case DELIVERY_CONFIRM, GOODS_READY -> {
                        if (status==2) deliveryHits(node,data,sn,po,withReceipt.contains(sn),hits,excluded,now);//状态2=待到货，交给跟单拆分逻辑
                    }
                    default -> throw new BusinessException(ResultCode.PARAM_ERROR,"节点规则未登记");//配置里出现没实现的节点就报错
                }
            }
        }
        //把前面 switch 各分支算出来的一堆 hits（候选），按收件人分组、去重，组装成最终的 SopReminderPreview 返回。
        Map<String,List<Candidate>> grouped = new LinkedHashMap<>();
        Map<String,Recipient> recipients = new LinkedHashMap<>();
        Set<String> unique = new HashSet<>();//四元组(收件人+单号+roundKey+阶段)去重，防同人同单重复进候选
        for (RuleHit hit : hits) {
            String key = hit.recipient().key(); Candidate candidate = hit.candidate();
            if (!unique.add(key+"|"+candidate.businessSn()+"|"+candidate.roundKey()+"|"+candidate.stage())) continue;//重复的跳过
            grouped.computeIfAbsent(key, ignored->new ArrayList<>()).add(candidate);recipients.put(key,hit.recipient());
        }
        //每个收件人key转成一个RecipientGroup，带上uid、姓名、候选列表。
        List<RecipientGroup> groups = grouped.entrySet().stream().map(entry->{
            Recipient person=recipients.get(entry.getKey());
            return new RecipientGroup(person.uid(),person.name(),List.copyOf(entry.getValue()),person.key());
        }).toList();
        //组装最终预览结果：候选总数=各分组候选数之和，excluded去重。
        return new SopReminderPreview(node.name(),node.title(),now,data.published().taskId(),data.published().syncedAt(),
                List.of(),groups.stream().mapToInt(group->group.candidates().size()).sum(),groups,excluded.stream().distinct().toList());
    }

    /** 跟单两个节点（交期确认/货物备齐）的候选：按跟单员拆 SKU，缺跟单员的SKU只跳过自己，不阻断整单。 */
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
        // 天数门槛全部来自Nacos，节点未配置时直接报错。
        SopReminderProperties.Node rule=properties.getNodes().get(node.name());
        if (rule==null) throw new BusinessException(ResultCode.PARAM_ERROR,"节点未配置："+node.name());
        if (node==NodeCode.DELIVERY_CONFIRM) {
            int waitDays=rule.getDeliveryDueDays();
            // 进入待到货满2天才提醒；只有缺交期的SKU对应跟单员收到交期确认提醒，同一人只发一条。
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
            // 同一人多个SKU有不同交期时取最早交期，避免较晚的一行掩盖已经临近交期的SKU。
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
        hits.add(new RuleHit(new Candidate(sn,0,null,entry.time(),hours,"PO","F:"+follower,entry.key(),stage),//PO(业务类型) + PO单号 + itemKey(明细键，其他地方也会用到这个键，加前缀能识别出是跟单员维度的明细)
                new Recipient(0,follower,"name:"+follower)));
    }

    /** 筛出本次PP范围内、创建时间合法的PO单，作为普通节点遍历的基础；无关联PP的按PO自身创建时间判断。最后返回的是po单列表 */
    private Map<String,JsonNode> scopedOrders(NodeData data,List<Excluded> excluded,LocalDateTime now) {
        Set<String> roots=new HashSet<>();//在PP范围内(创建时间≥pp-start-date)的PP单号
        Set<String> uncertainRoots=new HashSet<>();//创建时间缺失/异常的PP单号，关联到它的PO无法确定范围
        Map<String,JsonNode> planDocuments=documents(data,"1_PP");//先取出全部PP，按单号→JSON
        for (var document:planDocuments.entrySet()) {
            Entry created=creation(data,document.getKey(),document.getValue());//解析PP创建时间
            if (created==null || created.time().isAfter(now)) {
                uncertainRoots.add(document.getKey());//创建时间缺失或晚于现在，标记为不确定
                excluded.add(new Excluded(document.getKey(),"PP创建时间缺失或晚于当前时间"));
            }
            else if (!created.time().toLocalDate().isBefore(properties.getPpStartDate())) roots.add(document.getKey());//在起始日及以后，进roots
        }
        Map<String,JsonNode> result=new LinkedHashMap<>();//最终返回：范围内的PO单
        for (var document:documents(data,"1_PO").entrySet()) {
            JsonNode po=document.getValue();boolean related=false,uncertain=false,foundPlan=false;//三个标志：是否关联到范围内PP/是否关联不确定PP/是否找到关联PP
            for (JsonNode item:array(po,"item_list")) {
                String planSn=text(item,"plan_sn");//SKU直接关联的PP单号
                if (planDocuments.containsKey(planSn)) foundPlan=true;//能找到该PP
                if (roots.contains(planSn)) related=true;//关联的PP在范围内
                if (!planSn.isBlank() && (!planDocuments.containsKey(planSn) || uncertainRoots.contains(planSn))) uncertain=true;//找不到PP或PP时间不确定，标记不确定
                JsonNode plans=item.path("relation_purchase_plan");//SKU还可能通过"关联采购计划"字段挂多个PP
                if (plans.isTextual()) {
                    try { plans=objectMapper.readTree(plans.asText()); }//字段是JSON字符串，先解析成数组
                    catch (java.io.IOException exception) {throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE,"PO关联PP格式异常");}
                }
                if (plans.isArray()) for (JsonNode plan:plans) {//逐个关联PP判断
                    String linked=plan.asText();
                    if (planDocuments.containsKey(linked)) foundPlan=true;
                    if (roots.contains(linked)) related=true;
                    if (!linked.isBlank() && (!data.documents().containsKey("1_"+linked) || uncertainRoots.contains(linked))) uncertain=true;
                }
            }
            //有关联PP但都不在范围内：这条PO不参与本次催办；若时间不确定则记excluded(避免误关待办)，否则静默跳过。
            if (!related && foundPlan) {
                if (uncertain) excluded.add(new Excluded(document.getKey(),"关联PP创建时间不明，暂不关闭原待办"));
                continue;
            }
            // 无关联PP或关联PP无法找到时，使用PO自己的明确创建时间判断范围。即使找到了范围内PP，PO自身也必须在起始日当天及以后创建。
            Entry poCreated=creation(data,document.getKey(),po);
            if (poCreated==null || poCreated.time().isAfter(now)) {
                excluded.add(new Excluded(document.getKey(),"PO创建时间缺失或晚于当前时间，暂不关闭原待办"));
                continue;
            }
            if (!poCreated.time().toLocalDate().isBefore(properties.getPpStartDate()))
                result.put(document.getKey(),po);//PO创建时间在范围内，纳入结果
        }
        return result;
    }

    /** 从NodeData里按前缀（1_PP/1_PO/1_RF等）取单据，展开成「单号→单据JSON」的map。 */
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

    /** 判断该单创建人是否在Nacos白名单内：只比较中文姓名，忽略领星姓名里的字母/数字/分隔符前缀后缀。 */
    private boolean allowedCreator(NodeData data,String sn) {
        Recipient person=creator(data,sn);
        if (person == null) return false;
        // 领星姓名可能是Caigou-李静、HZ-CG-李静ABC等；忽略字母、数字、空格和分隔符。
        String chineseName = person.name().replaceAll("[^\\p{IsHan}]", "");
        return properties.getAllowedPoCreators().stream()
                .anyMatch(name -> name != null && !name.isBlank() && chineseName.equals(name.trim()));
    }
    /** 从创建日志里解析该单的创建人（领星UID+姓名），无明确创建人或多次创建人不同则返回null。 */
    private Recipient creator(NodeData data,String sn) {
        List<Event> created=events(data,sn).stream().filter(event->"创建".equals(event.type())).toList();
        if (created.isEmpty()) return null;
        Event first=created.get(0);
        if (first.uid()==null || first.uid()<=0 || first.actor()==null || first.actor().isBlank()) return null;
        if (created.stream().anyMatch(event->!java.util.Objects.equals(event.uid(),first.uid()))) return null;
        return new Recipient(first.uid(),first.actor(),"uid:"+first.uid());
    }
    /** 固定责任人节点（PO审批/财务付款/CR收货）：取出节点在 Nacos 配的 receiver-key（比如 PO_APPROVER）；拼成 "role:" + receiverKey（比如 role:PO_APPROVER，这个方法查的就是这个）； 然后拿它查到对应的people*/
    private Recipient fixed(NodeCode node) {
        SopReminderProperties.Node rule=properties.getNodes().get(node.name());
        return rule==null || rule.getReceiverKey()==null?null:new Recipient(0,rule.getReceiverKey(),"role:"+rule.getReceiverKey());
    }
    /** 取单据创建时间：优先用明确的create_time字段，缺失时回退到「创建」日志时间。 */
    private Entry creation(NodeData data,String sn,JsonNode row) {
        LocalDateTime explicit=dateTime(text(row,"create_time"));
        if (explicit!=null) return new Entry(explicit,"CREATE:"+explicit);
        return events(data,sn).stream().filter(event->"创建".equals(event.type()) && event.time()!=null)
                .findFirst().map(event->new Entry(event.time(),fingerprint(event))).orElse(null);
    }
    /** 从日志里解析单据「进入目标状态」的时间。用于“正在该状态才催”的节点，如po审批，po下发，请款付款，交期确认，货物备齐*/
    private Entry entered(NodeData data,String sn,String target,NodeCode node) {
        String current=null;//当前的状态
        Entry entered=null;//最终返回的"进入目标状态"时间点
        SopReminderProperties.Node rule=properties.getNodes().get(node.name());
        for (Event event:events(data,sn)) {
            if (event.time()==null) continue;//无时间的日志跳过
            String detail=plain(event.detail());Matcher matcher=STATE_CHANGE.matcher(detail);//把日志详情里的HTML标签去掉，转成纯文本，用"状态变更为【x】"这个正则去匹配
            boolean stateFound=matcher.find();
            if (!stateFound && !"编辑".equals(event.type())) {
                matcher=STATE_DECLARATION.matcher(detail);//没匹配到再试"单据状态为【x】"匹配（编辑事件不用）
                stateFound=matcher.find();
            }
            if (stateFound) {//日志里确实有状态变更
                String to=matcher.group(1).trim();if ("待审核".equals(to)) to="待审批";//把"待审核"统一成"待审批"
                if (target.equals(to) && !target.equals(current)) {//变成了目标状态，且之前不是该状态(新进入)
                    // “审批通过、仍待审批”只代表中间审批人通过，不能重置进入审批起点。
                    entered="待审批".equals(target) && "审批".equals(event.type())?null:new Entry(event.time(),fingerprint(event));
                }
                if (!target.equals(to)) entered=null;//变成了别的状态，说明已离开目标状态，清空
                current=to;//记录当前状态
            } else if (rule!=null && rule.getEntryLogTypes().contains(event.type())) {//没匹配到状态词，但日志类型属于配置的"进入日志"，也算进入
                entered=new Entry(event.time(),fingerprint(event));current=target;
            }
        }
        return entered;
    }
    /** 最近一次进入目标状态的时间：和entered方法的区别是lastentered方法的PO后续离开该状态也不清空，只用于计算卡滞起点。用于“按卡滞时长算起点”的节点，最后进入这个状态是什么时候，如创建请款单 */
    private Entry lastEntered(NodeData data,String sn,String target,NodeCode node) {
        Entry latest=null;//记录最近一次进入目标状态的时间，后续覆盖但不清空
        SopReminderProperties.Node rule=properties.getNodes().get(node.name());
        for (Event event:events(data,sn)) {
            if (event.time()==null) continue;
            String detail=plain(event.detail());Matcher matcher=STATE_CHANGE.matcher(detail);//同entered：先"状态变更为【x】"再"单据状态为【x】"
            boolean stateFound=matcher.find();
            if (!stateFound && !"编辑".equals(event.type())) {
                matcher=STATE_DECLARATION.matcher(detail);
                stateFound=matcher.find();
            }
            if (stateFound) {
                String to=matcher.group(1).trim();if ("待审核".equals(to)) to="待审批";
                if (target.equals(to)) latest=new Entry(event.time(),fingerprint(event));//每次进入目标状态都覆盖，保留最后一次
            } else if (rule!=null && rule.getEntryLogTypes().contains(event.type())) {
                latest=new Entry(event.time(),fingerprint(event));
            }
        }
        return latest;
    }
    /** 解析财务审批链当前该轮到谁：按审批通过日志推进，乱序/驳回/不在链内则返回null交给预览列出。 */
    private Recipient currentFinanceApprover(NodeData data,String sn) {
        // 配置只说明审批顺序，不能代替“前一人已经审批通过”的业务证据。乱序、驳回或审批人不在已确认链中时，返回缺失交给预览列出，不猜下一人。
        List<Long> chain=properties.getFinanceApprovalUids();//Nacos配置的审批链(3人，按顺序)
        if (chain.size()!=3 || new HashSet<>(chain).size()!=3 || chain.stream().anyMatch(uid->uid==null || uid<=0)) return null;//链不完整或重复，返回null
        Set<Long> passed=new HashSet<>();boolean invalid=false;//passed=已确认通过的审批人，invalid=链被破坏
        for (Event event:events(data,sn)) {
            String detail=plain(event.detail());
            if ("创建".equals(event.type()) || "重新提交".equals(event.type())) {
                passed.clear(); invalid=false;
            }//重新发起审批，清空已通过记录
            if (!"审批".equals(event.type())) continue;//只看审批日志
            if (!detail.matches("(?s).*审批结果\\s*[:：]\\s*通过.*") || !chain.contains(event.uid())) {invalid=true;continue;}//审批结果是"通过"且审批人在链内，否则链破坏
            int index=chain.indexOf(event.uid());
            for (int i=0;i<index;i++) if (!passed.contains(chain.get(i))) invalid=true;//跳过前面还没通过的人直接通过后面的人=乱序，链破坏
            passed.add(event.uid());
        }
        if (invalid) return null;//链被破坏，不猜下一个审批人
        for (Long uid:chain) if (!passed.contains(uid)) return new Recipient(uid,"审批人UID:"+uid,"uid:"+uid);//返回链上第一个还没通过的人
        return null;//所有人都通过了，没有待审批人
    }
    /** 命中一条候选：校验起算时间和收件人，算卡滞小时后生成RuleHit塞进hits。RuleHit 就是候选-收件人配对，hits 就是装了RuleHit的列表 */
    private void add(List<RuleHit> hits,List<Excluded> excluded,NodeCode node,String sn,String kind,
                     Entry entry,Recipient recipient,int stage,LocalDateTime now) {
        if (entry==null || entry.time()==null || entry.time().isAfter(now)) {
            excluded.add(new Excluded(sn,"缺少明确的本轮节点时间或时间晚于当前"));return;
        }
        if (recipient==null) { excluded.add(new Excluded(sn,"创建人、跟单员或当前审批责任人不明确"));return; }
        BigDecimal hours=BigDecimal.valueOf(Duration.between(entry.time(),now).getSeconds()).divide(BigDecimal.valueOf(3600),2,RoundingMode.HALF_UP);
        hits.add(new RuleHit(new Candidate(sn,0,null,entry.time(),hours,kind,"",entry.key(),stage),recipient));
    }
    /** 取某单号的日志列表，key按「1_单号」约定；没有则返回空列表。 */
    private List<Event> events(NodeData data,String sn) { return data.logs().getOrDefault("1_"+sn,List.of()); }
    /** 取JSON某字段的文本，字段缺失/为null时返回空串，避免NPE。 */
    private static String text(JsonNode value,String field) { return value.path(field).isNull()?"":value.path(field).asText(""); }
    /** 取JSON某字段的数组，字段不是数组时直接报错停止（结构异常）。 */
    private static List<JsonNode> array(JsonNode value,String field) {
        JsonNode rows=value.path(field);if (!rows.isArray()) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE,"单据明细缺失："+field);
        List<JsonNode> result=new ArrayList<>();rows.forEach(result::add);return result;
    }
    /** 解析时间字符串为LocalDateTime；空串、"-"或格式不对都返回null（不抛异常）。 */
    private static LocalDateTime dateTime(String text) {
        if (text.isBlank() || "-".equals(text)) return null;
        try {return LocalDateTime.parse(text.replace(' ','T'));}
        catch (java.time.format.DateTimeParseException exception) {return null;}
    }
    /** 把日志详情里的HTML标签去掉、<br>换成换行、&nbsp;换成空格，得到纯文本用于正则匹配状态。 */
    private static String plain(String html) {return html==null?"":html.replaceAll("(?i)<br\\s*/?>","\n").replaceAll("<[^>]+>","").replace("&nbsp;"," ").trim();}
    /** 对日志事件做SHA-256指纹，生成该事件的唯一标识（LOG:开头），用作待办的roundKey。 */
    private static String fingerprint(Event event) {
        try {return "LOG:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch (java.security.NoSuchAlgorithmException exception) {throw new IllegalStateException(exception);}
    }
}
