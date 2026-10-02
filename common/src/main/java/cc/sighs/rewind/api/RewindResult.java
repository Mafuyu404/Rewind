package cc.sighs.rewind.api;

import javax.annotation.Nullable;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * 一次存档点操作的结果。{@link RewindApi} 的同步入口直接返回它，带过渡的异步入口可以从
 * {@link RewindApi#lastResult()} 取到最近一次的结果。
 *
 * <p>字段是只读的（final），构造只走本类的静态工厂，所以拿到手之后不会被后续操作改掉。
 */
public final class RewindResult {
    /** 操作种类。 */
    public enum Kind {
        /** 建立 / 覆盖存档点。 */
        CHECKPOINT,
        /** 回溯到存档点。 */
        ROLLBACK
    }

    public final Kind kind;
    public final String slot;
    /** 是否成功。失败时 {@link #failure} 非 null。 */
    public final boolean success;
    /** 回溯是否走的原地回滚；false 表示走的是「关世界 → 覆盖 → 重开」那条回退路径。存档点恒为 false。 */
    public final boolean inPlace;
    /** 引擎统计（拷贝 / 跳过多少文件、耗时分解），只用于日志与诊断。 */
    public final String summary;
    /** 这次操作自身的耗时（毫秒）。 */
    public final long millis;
    /** 本次回拷真正写盘的文件数（-1 = 该操作不涉及）。 */
    public final int copiedFiles;
    /** 本次因为「活动存档里还是原样」而跳过的文件数（-1 = 该操作不涉及）。 */
    public final int skippedFiles;
    /** 涉及的槽位文件总数（-1 = 该操作不涉及）。 */
    public final int totalFiles;
    /** 建立存档点时写入的元数据；回溯时为 null。 */
    @Nullable public final SnapshotMeta meta;
    /** 失败原因；成功时为 null。 */
    @Nullable public final Throwable failure;

    private RewindResult(Kind kind, String slot, boolean success, boolean inPlace, String summary, long millis,
            int copiedFiles, int skippedFiles, int totalFiles, @Nullable SnapshotMeta meta,
            @Nullable Throwable failure) {
        this.kind = kind;
        this.slot = slot;
        this.success = success;
        this.inPlace = inPlace;
        this.summary = summary == null ? "" : summary;
        this.millis = millis;
        this.copiedFiles = copiedFiles;
        this.skippedFiles = skippedFiles;
        this.totalFiles = totalFiles;
        this.meta = meta;
        this.failure = failure;
    }

    public static RewindResult checkpoint(String slot, SnapshotMeta meta, String summary, long millis) {
        return new RewindResult(Kind.CHECKPOINT, slot, true, false, summary, millis, -1, -1, -1, meta, null);
    }

    public static RewindResult rollback(String slot, boolean inPlace, String summary, long millis,
            int copiedFiles, int skippedFiles, int totalFiles) {
        return new RewindResult(Kind.ROLLBACK, slot, true, inPlace, summary, millis,
                copiedFiles, skippedFiles, totalFiles, null, null);
    }

    /** 失败结果：{@code millis} 记 0（调用方自己统计墙钟时间）。 */
    public static RewindResult failure(Kind kind, String slot, Throwable failure) {
        return new RewindResult(kind, slot, false, false, String.valueOf(failure), 0L, -1, -1, -1, null, failure);
    }

    /** 一行摘要，给日志与 {@code /rewind status} 用。 */
    public String describe() {
        return kind + " slot=" + slot
                + (success ? " ok" : " FAILED")
                + (inPlace ? " inPlace" : "")
                + (millis > 0L ? " " + millis + "ms" : "")
                + (summary.isEmpty() ? "" : " [" + summary + "]");
    }
}
