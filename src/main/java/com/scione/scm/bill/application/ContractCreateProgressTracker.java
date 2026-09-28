package com.scione.scm.bill.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动创建合同的进度看板（纯内存，单实例）。
 *
 * <p>创建合同是一个同步长请求：最慢的是 {@code enrichContractItemsWithImages} 按 SKU 逐个查领星商品图，
 * 几十个 SKU 就要几十秒，而这期间前端收不到任何中间状态，很容易让人以为页面卡死。
 * 这里让前端发起创建时带上一个 {@code progressKey}，后端在关键节点更新进度，
 * 前端并行轮询 {@code GET /api/v1/contracts/create/progress} 把「第几步 / 在做什么」用小字显示出来。
 *
 * <p><b>进度只是体验增强，不参与任何业务判断</b>：key 缺失、进度查询不到、后端多实例或重启，
 * 都只是退化成「没有步骤文案」，创建合同本身完全不受影响。因此这里不引任何外部依赖、不落库，
 * 条目在创建结束时清除，另有 TTL 兜底回收。
 */
@Slf4j
@Component
public class ContractCreateProgressTracker {

    /** 步骤总数固定，前端据此显示「第 n/5 步」。与下面的 STEP_* 常量保持一致。 */
    public static final int TOTAL_STEPS = 5;

    /** 校验采购单与需方公司（含状态、唯一性）。 */
    public static final int STEP_VALIDATE = 1;
    /** 组装合同数据（编号、明细、手动补充字段）。 */
    public static final int STEP_ASSEMBLE = 2;
    /** 按 SKU 从领星补商品图片，最慢的一步，文案里会带 i/N。 */
    public static final int STEP_IMAGES = 3;
    /** 必填校验 + 落库 + 回写采购单标记。 */
    public static final int STEP_SAVE = 4;
    /** 生成首版合同 PDF 并上传。 */
    public static final int STEP_PDF = 5;

    /** 超过该时间未更新的条目视为残留（前端已离开、或创建线程已异常终止），读取时顺带回收。 */
    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(int step, String text, long startedAt, long touchedAt) {

        Entry advance(int step, String text) {
            return new Entry(step, text, startedAt, System.currentTimeMillis());
        }
    }

    /** 进度快照，直接作为接口响应体给前端展示。 */
    public record Snapshot(int step, int totalSteps, String text, long elapsedMillis) { }

    /**
     * 标记一次创建开始。progressKey 为空（老客户端、或调用方不需要进度）时不做任何事。
     */
    public void begin(String progressKey, String text) {
        if (!StringUtils.hasText(progressKey)) {
            return;
        }
        purgeExpired();
        long now = System.currentTimeMillis();
        entries.put(progressKey, new Entry(STEP_VALIDATE, text, now, now));
    }

    /** 推进到某一步。key 为空或条目已被回收时静默忽略；同一大步内的反复调用只更新文案，不会回退步号。 */
    public void advance(String progressKey, int step, String text) {
        if (!StringUtils.hasText(progressKey) || !StringUtils.hasText(text)) {
            return;
        }
        entries.computeIfPresent(progressKey,
                (key, entry) -> step < entry.step() ? entry : entry.advance(step, text));
    }

    /** 创建结束（成功或失败）后清除，避免残留条目。 */
    public void clear(String progressKey) {
        if (StringUtils.hasText(progressKey)) {
            entries.remove(progressKey);
        }
    }

    /** 读取当前进度；查不到返回空，由接口层回 data=null，前端继续显示通用文案。 */
    public Optional<Snapshot> find(String progressKey) {
        if (!StringUtils.hasText(progressKey)) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        Entry entry = entries.get(progressKey);
        if (entry == null || now - entry.touchedAt() > TTL_MILLIS) {
            entries.remove(progressKey);
            return Optional.empty();
        }
        return Optional.of(new Snapshot(entry.step(), TOTAL_STEPS, entry.text(), now - entry.startedAt()));
    }

    /**
     * 回收超时条目。只在 begin 时调用即可 —— 每次创建都会先做一次清理，
     * 单个请求的集合规模本来就只有个位数，不需要再起定时任务。
     */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(item -> now - item.getValue().touchedAt() > TTL_MILLIS);
    }
}
