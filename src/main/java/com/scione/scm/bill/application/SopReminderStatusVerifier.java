package com.scione.scm.bill.application;

import com.scione.scm.bill.domain.sop.enums.NodeCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.scione.scm.bill.config.SopReminderProperties;
import com.scione.scm.bill.domain.sop.enums.Decision;
import com.scione.scm.bill.domain.sop.SopReminderPreview.Candidate;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import com.scione.scm.bill.infrastructure.lingxing.LingxingOpenApiClient;
import com.scione.scm.bill.domain.sop.SopReminderSourceRepository;
import com.scione.scm.bill.domain.sop.SopReminderSourceRepository.CompletionFacts;
import com.scione.scm.bill.domain.sop.SopReminderSourceRepository.ReceiptState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** 首次发送与补偿共用的最后状态校验；只读，不把同步快照当成领星实时状态。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SopReminderStatusVerifier {
    private final LingxingOpenApiClient api;//实时查领星接口（PP/PO/RF/请款池/CR/装箱）
    private final SopReminderSourceRepository repository;//读合同/跟单/收货等人工信息
    private final SopReminderService rules;//复用装箱候选计算（evaluatePacking）
    private final SopReminderProperties properties;//Nacos配置：人员映射、节点天数门槛等
    /** 复核结论枚举已迁移至独立枚举类：com.scione.scm.bill.domain.sop.enums.Decision。 */
    public record Key(NodeCode nodeCode,String businessSn,String itemKey,int stage) {
        public static Key of(NodeCode code,Candidate c){return new Key(code,c.businessSn(),c.itemKey(),c.stage());}
    }

    /** 发送前对每条候选做实时状态复核：逐个节点查领星，判断「该发/已退出/未知」，返回 Key→Decision。
     * key：nodeCode:哪个节点(如 NodeCode.GOODS_READY);businessSn:哪张单(如 "PO261016004");itemKey:哪个明细(跟单节点是 F:跟单员);stage:哪个阶段(1 或 2，这个字段是专门给货物备齐节点的)。
     * Decision —— 复核结论(这条到底该不该发)，枚举:ELIGIBLE	单据仍在该催的状态 发出去；EXITED  单据已经办完了 关闭待办；UNKNOWN	数据没取到,无法判断  不关闭,下轮再说
     * */
    public Map<Key,Decision> verify(String userid,Map<NodeCode,List<Candidate>> expected) {
        Map<Key,Decision> result=new LinkedHashMap<>();//最终结果：key=候选定位，value=复核结论
        // 默认全部 UNKNOWN：只有明确判定为 ELIGIBLE 或 EXITED 才会覆盖。
        expected.forEach((code,rows)->rows.forEach(c->result.put(Key.of(code,c),Decision.UNKNOWN)));//先把每条候选都置为UNKNOWN
        try {
            Set<NodeCode> codes=expected.keySet();//本轮涉及的节点集合
            // 单个来源查不到只作废依赖它的节点，一个接口查不到不能让整条消息都发不出去。
            Set<NodeCode> unverified=new HashSet<>();//记录哪些节点的来源数据没取到，这些节点保持UNKNOWN
            // 第1步：读合同/跟单/收货等人工信息（CompletionFacts），供多个节点共用。(即这些数据不是从领星实时查到的)
            List<String> poSns=expected.entrySet().stream().filter(e->Set.of(NodeCode.PO_APPROVAL,NodeCode.PO_ORDER,NodeCode.CONTRACT_SIGN,NodeCode.DELIVERY_CONFIRM,NodeCode.GOODS_READY).contains(e.getKey()))
                    .flatMap(e->e.getValue().stream()).map(Candidate::businessSn).distinct().toList();//这几个节点涉及的全部PO单号(去重)
            CompletionFacts facts=poSns.isEmpty()?new CompletionFacts(Map.of(),Map.of(),Map.of(),null):repository.readCompletionFacts(poSns);//读合同/跟单/收货；没有PO就返回空包
            // 第2步：按节点分组实时拉领星数据（PP/PO/RF/请款池/CR），失败只作废对应节点。(这边如果没拉到，会进行重试，网络层重试:「间隔 2 秒、4 秒」,重试 MAX_PAGE_ATTEMPTS 次;
            //业务/限流重试(requestQueryWithRetry):最多重试 3 次,遇到 429(限流)、500、3001008、103 这些码会等 30 秒 × 次数再试;多次重试都拉不到的话才会作废该节点)
            Map<String,List<JsonNode>> plans=source(expected,unverified,Set.of(NodeCode.PO_CREATE),
                    ()->rows(api.fetchReminderVerification("PP",sns(expected,NodeCode.PO_CREATE),properties.getPpStartDate()),"plan_sn"));//PP数据，key=plan_sn
            List<String> livePos=expected.entrySet().stream().filter(e->Set.of(NodeCode.PO_APPROVAL,NodeCode.PO_ORDER,NodeCode.DELIVERY_CONFIRM,NodeCode.GOODS_READY).contains(e.getKey()))
                    .flatMap(e->e.getValue().stream()).map(Candidate::businessSn).distinct().toList();//需要查实时PO的PO单号集合
            Map<String,List<JsonNode>> orders=source(expected,unverified,Set.of(NodeCode.PO_APPROVAL,NodeCode.PO_ORDER,NodeCode.DELIVERY_CONFIRM,NodeCode.GOODS_READY),
                    ()->rows(livePos.isEmpty()?List.of():api.fetchReminderVerification("PO",livePos,properties.getPpStartDate()),"order_sn"));//PO数据，key=order_sn
            Map<String,List<JsonNode>> funds=source(expected,unverified,Set.of(NodeCode.RF_APPROVAL,NodeCode.RF_PAYMENT),()->{
                List<String> rfSns=new ArrayList<>(sns(expected,NodeCode.RF_APPROVAL));rfSns.addAll(sns(expected,NodeCode.RF_PAYMENT));//两个财务节点涉及的RF单号合并
                return rows(rfSns.isEmpty()?List.of():api.fetchReminderVerification("RF",rfSns,properties.getPpStartDate()),"order_sn");//RF请款单数据
            });
            Map<String,List<JsonNode>> pool=source(expected,unverified,Set.of(NodeCode.RF_CREATE),
                    ()->rows(api.fetchReminderVerification("POOL",sns(expected,NodeCode.RF_CREATE),properties.getPpStartDate()),"order_sn"));//请款池数据
            Map<String,List<JsonNode>> receipts=source(expected,unverified,Set.of(NodeCode.CR_RECEIPT),
                    ()->rows(api.fetchReminderVerification("CR",sns(expected,NodeCode.CR_RECEIPT),properties.getPpStartDate()),"order_sn"));//CR收货单数据
            // 第3步：货物备齐需要额外拉「收货单增量」，把最新收货状态补进 currentCr。
            Map<String,ReceiptState> currentCr=new LinkedHashMap<>(facts.receipts());//先复制已有的收货状态，key=收货单号
            if(codes.contains(NodeCode.GOODS_READY)) {
                try {
                    if(facts.collectedAt()==null)throw new IllegalStateException("缺少CR复核基线");//没有采集基线时间，无法拉增量
                    for(JsonNode cr:api.fetchReminderVerification("CR_UPDATES",List.of(),facts.collectedAt().toLocalDate())) {//拉采集基线之后的收货单增量
                        String sn=text(cr,"order_sn"),po=text(cr,"business_order_sn");//收货单号 + 关联PO单号
                        if((poSns.contains(po)||currentCr.containsKey(sn)) && (sn.isBlank()||po.isBlank()||status(cr)==null))throw new IllegalStateException("CR复核字段缺失");//关心的收货单如果字段缺失，报错
                        if(!sn.isBlank() && status(cr)!=null)currentCr.put(sn,new ReceiptState(po,status(cr)));//把最新状态覆盖进currentCr
                    }
                } catch(RuntimeException exception) {
                    unverified.addAll(Set.of(NodeCode.GOODS_READY,NodeCode.DELIVERY_CONFIRM));//收货增量拉失败，作废跟单两节点
                    log.error("发送前复核数据获取失败，本节点本轮不发送：nodes=[GOODS_READY, DELIVERY_CONFIRM], exceptionType={}",exception.getClass().getSimpleName());
                }
            }
            // 第4步：装箱节点实时算一遍候选，得到「该收件人名下可发批次」集合。
            Set<String> packingEligible=new HashSet<>();//该收件人名下可发的批次号
            Set<String> packingUnknown=new HashSet<>();//数据有问题的批次号
            Set<String> packingFound=new HashSet<>();//领星上实际存在的批次号
            if(codes.contains(NodeCode.PACKING_RELATE)) {
                try {
                    var live=api.fetchReminderPacking(properties.getPackingStartDate());//实时拉装箱数据
                    live.batches().forEach(row->packingFound.add(text(row,"seq")));//把所有批次号记进packingFound
                    var preview=rules.evaluatePacking(live);//复用规则层算一遍候选
                    preview.excluded().forEach(e->packingUnknown.add(e.businessSn()));//被排除的批次记进packingUnknown
                    preview.groups().forEach(g->{if(matches(userid,g.recipientKey(),g.recipientUid()))g.candidates().forEach(c->packingEligible.add(c.businessSn()));});//该收件人名下的批次记进packingEligible
                } catch(RuntimeException exception) {
                    unverified.add(NodeCode.PACKING_RELATE);
                    log.error("发送前复核数据获取失败，本节点本轮不发送：nodes=[PACKING_RELATE], exceptionType={}",exception.getClass().getSimpleName());
                }
            }
            // 网络复核可能耗时，临发送判断前再读一次合同和Excel，避免沿用网络请求前的人工信息。
            if(!poSns.isEmpty()) facts=repository.readCompletionFacts(poSns);//重新读一次，拿到最新的合同/跟单
            LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));//复核判断用的当前时间
            // 第5步：逐条候选按节点规则判定 ELIGIBLE / EXITED / 保持 UNKNOWN。
            for(var entry:expected.entrySet())for(Candidate candidate:entry.getValue()) {
                NodeCode code=entry.getKey();String sn=candidate.businessSn();Decision decision=Decision.UNKNOWN;//节点码、业务单号、初始判定UNKNOWN
                // 该节点依赖的来源本轮没取到，保持UNKNOWN，不拿旧候选发送。
                if(!unverified.contains(code)) switch(code) {
                    case PO_CREATE -> {
                        List<JsonNode> list=plans.getOrDefault(sn,List.of());//实时拉回的PP行
                        List<JsonNode> scope=list.stream().filter(row->row.path("sid").asLong(-1)==candidate.sid()).toList();//只认该候选对应店铺的PP行
                        //PP状态必须是2/3(待采购/部分采购)且采购员明确、且采购员正好是当前收件人，才算ELIGIBLE。
                        if(!scope.isEmpty()&&scope.stream().allMatch(row->status(row)!=null && (!Set.of(2,3).contains(status(row)) || row.path("cg_uid").asLong() > 0)))decision=scope.stream().anyMatch(row->Set.of(2,3).contains(status(row))&&matches(userid,"uid:"+row.path("cg_uid").asLong(),row.path("cg_uid").asLong()))?Decision.ELIGIBLE:Decision.EXITED;
                    }
                    case PO_APPROVAL,PO_ORDER -> {
                        JsonNode po=one(orders,sn);//实时拉回的PO
                        //审批节点要状态121、下发节点要状态1；下发节点还不能已有合同（有合同交给合同节点催）。
                        if(po!=null&&status(po)!=null)decision=status(po)==((code == NodeCode.PO_APPROVAL)?121:1)
                                && (code != NodeCode.PO_ORDER||!facts.contracts().containsKey(sn))?Decision.ELIGIBLE:Decision.EXITED;
                    }
                    case CONTRACT_SIGN -> {var contract=facts.contracts().get(sn);decision=contract!=null&&candidate.roundKey().equals("CONTRACT:"+contract.id())?Decision.ELIGIBLE:Decision.EXITED;}//合同还在且roundKey对得上该合同，才ELIGIBLE
                    case RF_CREATE -> {
                        var list=pool.getOrDefault(sn,List.of());//实时拉回的请款池行
                        //请款池里有"现结且未付款"的行才算ELIGIBLE。
                        if(!list.isEmpty()&&list.stream().allMatch(row->number(row,"pay_status")!=null && !text(row,"settlement_method").isBlank()))decision=list.stream().anyMatch(row->number(row,"pay_status")==0&&"现结".equals(text(row,"settlement_method")))?Decision.ELIGIBLE:Decision.EXITED;
                    }
                    case RF_APPROVAL,RF_PAYMENT -> {var rf=one(funds,sn);if(rf!=null&&status(rf)!=null&&!text(rf,"settlement_method").isBlank())decision=status(rf)==((code == NodeCode.RF_APPROVAL)?121:1)&&"现结".equals(text(rf,"settlement_method"))?Decision.ELIGIBLE:Decision.EXITED;}//审批节点状态121、付款节点状态1，且都要现结
                    case CR_RECEIPT -> {var cr=one(receipts,sn);if(cr!=null&&status(cr)!=null)decision=status(cr)==10?Decision.ELIGIBLE:Decision.EXITED;}//CR状态10(待收货)才ELIGIBLE
                    case DELIVERY_CONFIRM,GOODS_READY -> decision=delivery(code,userid,candidate,one(orders,sn),facts,currentCr,now);//跟单两节点走专门的delivery复核
                    case PACKING_RELATE -> {if(!packingUnknown.contains(sn)&&packingFound.contains(sn))decision=packingEligible.contains(sn)?Decision.ELIGIBLE:Decision.EXITED;}//装箱：批次存在且该收件人名下可发才ELIGIBLE
                    default -> throw new IllegalStateException("未实现实时复核节点");
                }
                result.put(Key.of(code,candidate),decision);
            }
        } catch(RuntimeException exception) {
            // 不记录异常原文或令牌；失败时整条消息保留UNKNOWN，不使用旧候选发送。
            result.replaceAll((key,value)->Decision.UNKNOWN);
            log.error("发送前状态复核失败，本条消息不发送：nodes={}, exceptionType={}",expected.keySet(),exception.getClass().getSimpleName());
        }
        return result;
    }

    /** 取一个复核来源；失败时只作废依赖它的节点，其它节点继续复核。 */
    private Map<String,List<JsonNode>> source(Map<NodeCode,List<Candidate>> expected,Set<NodeCode> unverified,
            Collection<NodeCode> nodes,java.util.function.Supplier<Map<String,List<JsonNode>>> loader) {
        if(nodes.stream().noneMatch(expected::containsKey))return Map.of();
        try { return loader.get(); }
        catch(RuntimeException exception) {
            unverified.addAll(nodes);
            log.error("发送前复核数据获取失败，本节点本轮不发送：nodes={}, exceptionType={}",nodes,exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** 跟单两个节点的复核：按该收件人名下SKU判断阶段；缺跟单员的SKU只跳过自己，不阻断整单。 */
    private Decision delivery(NodeCode code,String userid,Candidate candidate,JsonNode po,CompletionFacts facts,Map<String,ReceiptState> receipts,LocalDateTime now) {
        if(po==null||status(po)==null)return Decision.UNKNOWN;//PO查不到或状态字段缺失，无法判断
        if(status(po)!=2)return Decision.EXITED;//PO不在"待到货"状态(2)，已经离开了，不再催
        if(!po.path("item_list").isArray())return Decision.UNKNOWN;//item_list不是数组，数据结构异常，无法判断
        // 收集该PO所有有效SKU的跟单信息，缺跟单员的SKU直接跳过。
        List<DeliveryFollowUp> all=new ArrayList<>();//该PO所有有效SKU的跟单记录
        for(JsonNode item:po.path("item_list"))if(item.path("is_delete").asInt()==0&&!text(item,"sku").isBlank()) {//遍历每个未删除且有SKU的行
            var row=facts.followUps().get(candidate.businessSn()+"|"+text(item,"sku"));//按"PO单号|SKU"找这条SKU的跟单记录
            //未导入跟单员的SKU只跳过自己，不再让整单无法确认；与预览规则保持一致。
            if(row==null||row.followerName()==null||row.followerName().isBlank())continue;//没导入跟单员，跳过这个SKU
            all.add(row);//有跟单员，加入all
        }
        if(all.isEmpty())return Decision.UNKNOWN;//一个有效跟单记录都没有，无法判断
        // 只认该收件人名下的SKU（按跟单员姓名匹配），同PO可能多人分头负责。
        var mine=all.stream().filter(row->matches(userid,"name:"+row.followerName(),0)).toList();//筛选出跟单员=当前收件人的SKU
        // 交期节点：该人名下还有缺交期的SKU就继续催，交期都填了就视为已完成。
        if((code == NodeCode.DELIVERY_CONFIRM))return mine.stream().anyMatch(row->row.expectedDeliveryDate()==null)?Decision.ELIGIBLE:Decision.EXITED;
        //GOODS_READY只按该收件人名下的SKU判断阶段，同一PO可能由多人分头负责。
        if(mine.isEmpty())return Decision.EXITED;//该收件人名下没有SKU，视为已退出
        if(mine.stream().anyMatch(row->row.expectedDeliveryDate()==null))return Decision.UNKNOWN;//该人名下还有SKU没填交期，无法判断阶段
        // 取该人名下最早的交期作为阶段判断基准，避免较晚交期的SKU掩盖临近交期的SKU。
        var earliest=mine.stream().map(DeliveryFollowUp::expectedDeliveryDate).min(LocalDate::compareTo).orElseThrow();//最早的交期日期
        var node=properties.getNodes().get(code);Duration remaining=Duration.between(now,earliest.atStartOfDay());//距离最早交期还有多久
        if(remaining.compareTo(Duration.ofDays(node.getFirstDueDays()))>0)return Decision.EXITED;//还没到第一次提醒的窗口，退出
        int stage=remaining.compareTo(Duration.ofDays(node.getSecondDueDays()))<=0?2:1;//≤第二次门槛就阶段2，否则阶段1
        return candidate.stage()==stage&&(stage!=2||receipts.values().stream().noneMatch(cr->candidate.businessSn().equals(cr.poSn())&&Set.of(10,20,30,40).contains(cr.status())))?Decision.ELIGIBLE:Decision.EXITED;//阶段对得上、且阶段2时还没建CR，才算ELIGIBLE
    }
    /** 判断某key（uid:/name:/role:）解析出的收件人是否是当前userid。 */
    private boolean matches(String userid,String key,long uid) {
        var person=properties.resolvePerson(key,uid);
        return person!=null&&userid.equals(person.getUserid());
    }
    /** 取某节点在expected里的全部业务单号（去重）。 */
    private static List<String> sns(Map<NodeCode,List<Candidate>> expected,NodeCode code){return expected.getOrDefault(code,List.of()).stream().map(Candidate::businessSn).distinct().toList();}
    /** 把实时拉回的单据行按某字段（order_sn/plan_sn等）归组成「单号→行列表」；缺单号直接报错。 */
    private static Map<String,List<JsonNode>> rows(List<JsonNode> rows,String field){Map<String,List<JsonNode>> result=new LinkedHashMap<>();for(JsonNode row:rows){String key=text(row,field);if(key.isBlank())throw new IllegalStateException("实时单据缺少单号");result.computeIfAbsent(key,ignored->new ArrayList<>()).add(row);}return result;}
    /** 取某单号唯一的那一行；没有或出现多行（脏数据）都返回null，不猜。 */
    private static JsonNode one(Map<String,List<JsonNode>> rows,String sn){var list=rows.getOrDefault(sn,List.of());return list.size()==1?list.get(0):null;}
    /** 取status字段的整数。 */
    private static Integer status(JsonNode row){return number(row,"status");}
    /** 取某字段的整数值，非数字时返回null。 */
    private static Integer number(JsonNode row,String field){try{return Integer.valueOf(row.path(field).asText());}catch(NumberFormatException exception){return null;}}
    /** 取某字段文本，缺失/为null返回空串。 */
    private static String text(JsonNode row,String field){return row.path(field).isNull()?"":row.path(field).asText("");}
}
