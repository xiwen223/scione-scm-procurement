package com.scione.scm.bill.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.scm.bill.config.SopReminderProperties;
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

/** 首次发送与补偿共用的最后状态门禁；只读，不把同步快照当成领星实时状态。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SopReminderStatusVerifier {
    private final LingxingOpenApiClient api;
    private final SopReminderSourceRepository repository;
    private final SopReminderService rules;
    private final SopReminderProperties properties;
    public enum Decision { ELIGIBLE, EXITED, UNKNOWN }
    public record Key(String nodeCode,String businessSn,String itemKey,int stage) {
        public static Key of(String code,Candidate c){return new Key(code,c.businessSn(),c.itemKey(),c.stage());}
    }

    public Map<Key,Decision> verify(String userid,Map<String,List<Candidate>> expected) {
        Map<Key,Decision> result=new LinkedHashMap<>();
        expected.forEach((code,rows)->rows.forEach(c->result.put(Key.of(code,c),Decision.UNKNOWN)));
        try {
            Set<String> codes=expected.keySet();
            // 单个来源查不到只作废依赖它的节点，一个接口抽风不能让整条消息都发不出去。
            Set<String> unverified=new HashSet<>();
            List<String> poSns=expected.entrySet().stream().filter(e->Set.of("PO_APPROVAL","PO_ORDER","CONTRACT_SIGN","DELIVERY_CONFIRM","GOODS_READY").contains(e.getKey()))
                    .flatMap(e->e.getValue().stream()).map(Candidate::businessSn).distinct().toList();
            CompletionFacts facts=poSns.isEmpty()?new CompletionFacts(Map.of(),Map.of(),Map.of(),null):repository.readCompletionFacts(poSns);
            Map<String,List<JsonNode>> plans=source(expected,unverified,Set.of("PO_CREATE"),
                    ()->rows(api.fetchReminderVerification("PP",sns(expected,"PO_CREATE"),properties.getPpStartDate()),"plan_sn"));
            List<String> livePos=expected.entrySet().stream().filter(e->Set.of("PO_APPROVAL","PO_ORDER","DELIVERY_CONFIRM","GOODS_READY").contains(e.getKey()))
                    .flatMap(e->e.getValue().stream()).map(Candidate::businessSn).distinct().toList();
            Map<String,List<JsonNode>> orders=source(expected,unverified,Set.of("PO_APPROVAL","PO_ORDER","DELIVERY_CONFIRM","GOODS_READY"),
                    ()->rows(livePos.isEmpty()?List.of():api.fetchReminderVerification("PO",livePos,properties.getPpStartDate()),"order_sn"));
            Map<String,List<JsonNode>> funds=source(expected,unverified,Set.of("RF_APPROVAL","RF_PAYMENT"),()->{
                List<String> rfSns=new ArrayList<>(sns(expected,"RF_APPROVAL"));rfSns.addAll(sns(expected,"RF_PAYMENT"));
                return rows(rfSns.isEmpty()?List.of():api.fetchReminderVerification("RF",rfSns,properties.getPpStartDate()),"order_sn");
            });
            Map<String,List<JsonNode>> pool=source(expected,unverified,Set.of("RF_CREATE"),
                    ()->rows(api.fetchReminderVerification("POOL",sns(expected,"RF_CREATE"),properties.getPpStartDate()),"order_sn"));
            Map<String,List<JsonNode>> receipts=source(expected,unverified,Set.of("CR_RECEIPT"),
                    ()->rows(api.fetchReminderVerification("CR",sns(expected,"CR_RECEIPT"),properties.getPpStartDate()),"order_sn"));
            Map<String,ReceiptState> currentCr=new LinkedHashMap<>(facts.receipts());
            if(codes.contains("GOODS_READY")) {
                try {
                    if(facts.collectedAt()==null)throw new IllegalStateException("缺少CR复核基线");
                    for(JsonNode cr:api.fetchReminderVerification("CR_UPDATES",List.of(),facts.collectedAt().toLocalDate())) {
                        String sn=text(cr,"order_sn"),po=text(cr,"business_order_sn");
                        if((poSns.contains(po)||currentCr.containsKey(sn)) && (sn.isBlank()||po.isBlank()||status(cr)==null))throw new IllegalStateException("CR复核字段缺失");
                        if(!sn.isBlank() && status(cr)!=null)currentCr.put(sn,new ReceiptState(po,status(cr)));
                    }
                } catch(RuntimeException exception) {
                    unverified.addAll(Set.of("GOODS_READY","DELIVERY_CONFIRM"));
                    log.error("发送前复核数据获取失败，本节点本轮不发送：nodes=[GOODS_READY, DELIVERY_CONFIRM], exceptionType={}",exception.getClass().getSimpleName());
                }
            }
            Set<String> packingEligible=new HashSet<>(),packingUnknown=new HashSet<>(),packingFound=new HashSet<>();
            if(codes.contains("PACKING_RELATE")) {
                try {
                    var live=api.fetchReminderPacking(properties.getPackingStartDate());
                    live.batches().forEach(row->packingFound.add(text(row,"seq")));
                    var preview=rules.evaluatePacking(live);
                    preview.excluded().forEach(e->packingUnknown.add(e.businessSn()));
                    preview.groups().forEach(g->{if(matches(userid,g.recipientKey(),g.recipientUid()))g.candidates().forEach(c->packingEligible.add(c.businessSn()));});
                } catch(RuntimeException exception) {
                    unverified.add("PACKING_RELATE");
                    log.error("发送前复核数据获取失败，本节点本轮不发送：nodes=[PACKING_RELATE], exceptionType={}",exception.getClass().getSimpleName());
                }
            }
            // 网络复核可能耗时，临发送判断前再读一次合同和Excel，避免沿用网络请求前的人工信息。
            if(!poSns.isEmpty()) facts=repository.readCompletionFacts(poSns);
            LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            for(var entry:expected.entrySet())for(Candidate candidate:entry.getValue()) {
                String code=entry.getKey(),sn=candidate.businessSn();Decision decision=Decision.UNKNOWN;
                // 该节点依赖的来源本轮没取到，保持UNKNOWN，不拿旧候选发送。
                if(!unverified.contains(code)) switch(code) {
                    case "PO_CREATE" -> {
                        List<JsonNode> list=plans.getOrDefault(sn,List.of());
                        List<JsonNode> scope=list.stream().filter(row->row.path("sid").asLong(-1)==candidate.sid()).toList();
                        if(!scope.isEmpty()&&scope.stream().allMatch(row->status(row)!=null && (!Set.of(2,3).contains(status(row)) || row.path("cg_uid").asLong() > 0)))decision=scope.stream().anyMatch(row->Set.of(2,3).contains(status(row))&&matches(userid,"uid:"+row.path("cg_uid").asLong(),row.path("cg_uid").asLong()))?Decision.ELIGIBLE:Decision.EXITED;
                    }
                    case "PO_APPROVAL","PO_ORDER" -> {
                        JsonNode po=one(orders,sn);
                        if(po!=null&&status(po)!=null)decision=status(po)==("PO_APPROVAL".equals(code)?121:1)
                                && (!"PO_ORDER".equals(code)||!facts.contracts().containsKey(sn))?Decision.ELIGIBLE:Decision.EXITED;
                    }
                    case "CONTRACT_SIGN" -> {var contract=facts.contracts().get(sn);decision=contract!=null&&candidate.roundKey().equals("CONTRACT:"+contract.id())?Decision.ELIGIBLE:Decision.EXITED;}
                    case "RF_CREATE" -> {
                        var list=pool.getOrDefault(sn,List.of());
                        if(!list.isEmpty()&&list.stream().allMatch(row->number(row,"pay_status")!=null && !text(row,"settlement_method").isBlank()))decision=list.stream().anyMatch(row->number(row,"pay_status")==0&&"现结".equals(text(row,"settlement_method")))?Decision.ELIGIBLE:Decision.EXITED;
                    }
                    case "RF_APPROVAL","RF_PAYMENT" -> {var rf=one(funds,sn);if(rf!=null&&status(rf)!=null&&!text(rf,"settlement_method").isBlank())decision=status(rf)==("RF_APPROVAL".equals(code)?121:1)&&"现结".equals(text(rf,"settlement_method"))?Decision.ELIGIBLE:Decision.EXITED;}
                    case "CR_RECEIPT" -> {var cr=one(receipts,sn);if(cr!=null&&status(cr)!=null)decision=status(cr)==10?Decision.ELIGIBLE:Decision.EXITED;}
                    case "DELIVERY_CONFIRM","GOODS_READY" -> decision=delivery(code,userid,candidate,one(orders,sn),facts,currentCr,now);
                    case "PACKING_RELATE" -> {if(!packingUnknown.contains(sn)&&packingFound.contains(sn))decision=packingEligible.contains(sn)?Decision.ELIGIBLE:Decision.EXITED;}
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
    private Map<String,List<JsonNode>> source(Map<String,List<Candidate>> expected,Set<String> unverified,
            Collection<String> nodes,java.util.function.Supplier<Map<String,List<JsonNode>>> loader) {
        if(nodes.stream().noneMatch(expected::containsKey))return Map.of();
        try { return loader.get(); }
        catch(RuntimeException exception) {
            unverified.addAll(nodes);
            log.error("发送前复核数据获取失败，本节点本轮不发送：nodes={}, exceptionType={}",nodes,exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Decision delivery(String code,String userid,Candidate candidate,JsonNode po,CompletionFacts facts,Map<String,ReceiptState> receipts,LocalDateTime now) {
        if(po==null||status(po)==null)return Decision.UNKNOWN;
        if(status(po)!=2)return Decision.EXITED;
        if(!po.path("item_list").isArray())return Decision.UNKNOWN;
        List<DeliveryFollowUp> all=new ArrayList<>();
        for(JsonNode item:po.path("item_list"))if(item.path("is_delete").asInt()==0&&!text(item,"sku").isBlank()) {
            var row=facts.followUps().get(candidate.businessSn()+"|"+text(item,"sku"));
            //未导入跟单员的SKU只跳过自己，不再让整单无法确认；与预览规则保持一致。
            if(row==null||row.followerName()==null||row.followerName().isBlank())continue;
            all.add(row);
        }
        if(all.isEmpty())return Decision.UNKNOWN;
        var mine=all.stream().filter(row->matches(userid,"name:"+row.followerName(),0)).toList();
        if("DELIVERY_CONFIRM".equals(code))return mine.stream().anyMatch(row->row.expectedDeliveryDate()==null)?Decision.ELIGIBLE:Decision.EXITED;
        //GOODS_READY只按该收件人名下的SKU判断阶段，同一PO可能由多人分头负责。
        if(mine.isEmpty())return Decision.EXITED;
        if(mine.stream().anyMatch(row->row.expectedDeliveryDate()==null))return Decision.UNKNOWN;
        var earliest=mine.stream().map(DeliveryFollowUp::expectedDeliveryDate).min(LocalDate::compareTo).orElseThrow();
        var node=properties.getNodes().get(code);Duration remaining=Duration.between(now,earliest.atStartOfDay());
        if(remaining.compareTo(Duration.ofDays(node.getFirstDueDays()))>0)return Decision.EXITED;
        int stage=remaining.compareTo(Duration.ofDays(node.getSecondDueDays()))<=0?2:1;
        return candidate.stage()==stage&&(stage!=2||receipts.values().stream().noneMatch(cr->candidate.businessSn().equals(cr.poSn())&&Set.of(10,20,30,40).contains(cr.status())))?Decision.ELIGIBLE:Decision.EXITED;
    }
    private boolean matches(String userid,String key,long uid) {
        var person=properties.resolvePerson(key,uid);
        return person!=null&&userid.equals(person.getUserid());
    }
    private static List<String> sns(Map<String,List<Candidate>> expected,String code){return expected.getOrDefault(code,List.of()).stream().map(Candidate::businessSn).distinct().toList();}
    private static Map<String,List<JsonNode>> rows(List<JsonNode> rows,String field){Map<String,List<JsonNode>> result=new LinkedHashMap<>();for(JsonNode row:rows){String key=text(row,field);if(key.isBlank())throw new IllegalStateException("实时单据缺少单号");result.computeIfAbsent(key,ignored->new ArrayList<>()).add(row);}return result;}
    private static JsonNode one(Map<String,List<JsonNode>> rows,String sn){var list=rows.getOrDefault(sn,List.of());return list.size()==1?list.get(0):null;}
    private static Integer status(JsonNode row){return number(row,"status");}
    private static Integer number(JsonNode row,String field){try{return Integer.valueOf(row.path(field).asText());}catch(NumberFormatException exception){return null;}}
    private static String text(JsonNode row,String field){return row.path(field).isNull()?"":row.path(field).asText("");}
}
