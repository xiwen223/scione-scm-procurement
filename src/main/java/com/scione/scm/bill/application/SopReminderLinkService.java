package com.scione.scm.bill.application;

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
 * 免登录催办外链：校验 token、按节点汇总待办。
 * 页面不做登录校验，访问控制完全依赖不可猜的 token；打开链接只读，不记录浏览信息。
 */
@Service
@RequiredArgsConstructor
public class SopReminderLinkService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    private final SopReminderRepository repository;
    private final com.scione.scm.bill.config.SopReminderProperties properties;

    /**
     * 打开链接：返回该催办人本轮的待办。页面不做登录校验，访问控制完全依赖不可猜的 token。
     */
    public SopReminderLinkView open(String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "催办链接格式错误");
        }
        SopReminderRepository.Link link = repository.findLink(token);
        List<SopReminderRepository.TodoRow> todos = repository.loadTodos(token);
        Map<Long, List<ReasonItem>> reasons =
                repository.loadReasons(todos.stream().map(SopReminderRepository.TodoRow::id).toList());

        Map<String, List<TodoItem>> grouped = new LinkedHashMap<>();
        for (SopReminderRepository.TodoRow todo : todos) {
            List<ReasonItem> items = List.copyOf(reasons.getOrDefault(todo.id(), List.of()));
            grouped.computeIfAbsent(todo.nodeCode(), key -> new ArrayList<>())
                    .add(new TodoItem(todo.id(), todo.businessSn(), todo.businessType(), todo.enteredAt(),
                            todo.reminderCount(), todo.firstRemindedAt(), items));
        }
        // SQL按编码排序；页面改用统一节点目录的业务顺序，缺少待办的节点自然跳过。
        List<NodeGroup> nodes = grouped.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(entry -> nodeOrder(entry.getKey())))
                .map(entry -> new NodeGroup(entry.getKey(),
                        nodeName(entry.getKey()), List.copyOf(entry.getValue())))
                .toList();
        return new SopReminderLinkView(link.assigneeUserid(), ownerName(link.assigneeUserid()), nodes);
    }

    private String ownerName(String userid) {
        List<com.scione.scm.bill.config.SopReminderProperties.Person> people = new ArrayList<>();
        people.addAll(properties.getSend().getPeople().values());
        people.addAll(properties.getSend().getPurchasers().values());
        people.add(properties.getSend().getFallbackPerson());
        return people.stream().filter(person -> person != null && userid.equals(person.getUserid())
                        && person.getName() != null && !person.getName().isBlank())
                .map(com.scione.scm.bill.config.SopReminderProperties.Person::getName).findFirst().orElse(userid);
    }

    /** 免登录页面填写卡滞原因；原因长度受数据库 varchar(150) 限制。 */
    public void addReason(String token, Long todoId, String reason) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "催办链接格式错误");
        }
        if (todoId == null || todoId <= 0) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "待办ID必须有效");
        }
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "原因不能为空");
        }
        if (text.length() > 150) {
            throw new BusinessException(ResultCode.REMINDER_LINK_INVALID, "原因最多 150 个字");
        }
        repository.addReason(token, todoId, text);
    }

    private static int nodeOrder(String nodeCode) {
        try { return com.scione.scm.bill.domain.sop.SopReminderPreview.NodeCode.valueOf(nodeCode).ordinal(); }
        catch (IllegalArgumentException exception) { return Integer.MAX_VALUE; }
    }

    private static String nodeName(String nodeCode) {
        try { return com.scione.scm.bill.domain.sop.SopReminderPreview.NodeCode.valueOf(nodeCode).title(); }
        catch (IllegalArgumentException exception) { return nodeCode; }
    }
}
