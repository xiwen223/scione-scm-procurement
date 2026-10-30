package com.scione.scm.bill.application;

import com.scione.scm.bill.domain.sop.enums.NodeCode;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.domain.sop.SopReminderLinkView;
import com.scione.scm.bill.domain.sop.SopReminderLinkView.NodeGroup;
import com.scione.scm.bill.domain.sop.SopReminderLinkView.ReasonItem;
import com.scione.scm.bill.domain.sop.SopReminderLinkView.TodoItem;
import com.scione.scm.bill.domain.sop.SopReminderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 免登录催办外链：校验 token、按节点汇总待办。处理「收件人点开催办消息里的链接 → 看到自己的待办清单 → 填卡滞原因」
 * 页面不做登录校验，访问控制完全依赖不可猜的 token；打开链接只读，不记录浏览信息。
 */
@Service
@RequiredArgsConstructor
public class SopReminderLinkService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[0-9a-f]{32}$");//外链token必须是32位十六进制

    private final SopReminderRepository repository;
    private final com.scione.scm.bill.config.SopReminderProperties properties;//人员映射，用于把userid解析成中文姓名

    /**
     * 打开链接：按这条消息发送时的快照返回待办，内容不随后续状态变化。
     * 页面不做登录校验，访问控制完全依赖不可猜的 token。
     */
    public SopReminderLinkView open(String token) {
        // 第1步：校验 token 格式（32位十六进制），格式不对直接拒绝。
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "催办链接格式错误");
        }
        // 第2步：解析 token → 消息批次/收件人，读待办明细 + 各待办的历史原因。
        SopReminderRepository.Link link = repository.findLink(token);//按token找对应的消息批次和收件人
        List<SopReminderRepository.TodoRow> todos = repository.loadTodos(link);//读这条链接该展示的待办（有快照读快照，没快照实时读）
        Map<Long, List<ReasonItem>> reasons =
                repository.loadReasons(todos.stream().map(SopReminderRepository.TodoRow::id).toList());//读每条待办的历史原因，key=待办id

        // 第3步：按节点分组，把 TodoRow 转成页面需要的 TodoItem（带原因列表）。
        Map<NodeCode, List<TodoItem>> grouped = new LinkedHashMap<>();//key=节点码，value=该节点下的待办列表
        for (SopReminderRepository.TodoRow todo : todos) {
            List<ReasonItem> items = List.copyOf(reasons.getOrDefault(todo.id(), List.of()));//该待办的历史原因（没有就空列表）
            grouped.computeIfAbsent(todo.nodeCode(), key -> new ArrayList<>())
                    .add(new TodoItem(todo.id(), todo.businessSn(), todo.businessType(), todo.enteredAt(),
                            todo.reminderCount(), todo.firstRemindedAt(), items));//把TodoRow转成TodoItem塞进对应节点分组
        }
        // SQL按编码排序；页面改用统一节点目录的业务顺序，缺少待办的节点自然跳过。
        List<NodeGroup> nodes = grouped.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(entry -> entry.getKey().ordinal()))//按节点目录的业务顺序排序
                .map(entry -> new NodeGroup(entry.getKey(),
                        entry.getKey().title(), List.copyOf(entry.getValue())))//转成NodeGroup(节点码、节点中文名、待办列表)
                .toList();
        return new SopReminderLinkView(link.assigneeUserid(), ownerName(link.assigneeUserid()),
                link.sentAt(), nodes);//返回外链视图：收件人userid、中文名、快照时间、节点分组
    }

    /** 把收件人 userid 解析成 Nacos 人员映射里的中文姓名，用于页面展示；映射缺失则回退显示 userid。 */
    private String ownerName(String userid) {
        List<com.scione.scm.bill.config.SopReminderProperties.Person> people = new ArrayList<>();//把所有可能的人员配置汇总到一张表
        people.addAll(properties.getSend().getPeople().values());//先加people映射
        people.addAll(properties.getSend().getPurchasers().values());//再加采购员映射
        return people.stream().filter(person -> person != null && userid.equals(person.getUserid())
                        && person.getName() != null && !person.getName().isBlank())//筛出userid匹配且姓名非空的人
                .map(com.scione.scm.bill.config.SopReminderProperties.Person::getName).findFirst().orElse(userid);//取第一个匹配的姓名，找不到就回退显示userid
    }

    /** 免登录页面填写卡滞原因；原因长度受数据库 varchar(150) 限制。 */
    public void addReason(String token, Long todoId, String reason) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "催办链接格式错误");//token格式不对
        }
        if (todoId == null || todoId <= 0) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "待办ID必须有效");//待办ID无效
        }
        String text = reason == null ? "" : reason.trim();//原因去首尾空格
        if (text.isEmpty()) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "原因不能为空");//原因不能是空的
        }
        if (text.length() > 150) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "原因最多 150 个字");//原因超长
        }
        repository.addReason(token, todoId, text);//写入原因历史
    }

}
