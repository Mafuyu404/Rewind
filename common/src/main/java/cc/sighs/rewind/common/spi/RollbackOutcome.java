package cc.sighs.rewind.common.spi;

/**
 * {@link RewindPlatform#rollbackInPlace} 的结果：回拷统计与总耗时。
 *
 * <p>只保留 {@code RewindResult} 真正暴露的那几个数；平台侧引擎内部的分段耗时与中间计数留在各自的
 * 实现里写日志，不跨边界。
 */
public final class RollbackOutcome {
    /** 回拷摘要（已拷 / 跳过多少文件），用于日志与 {@code RewindResult.description()}。 */
    public final String summary;
    /** 本次回拷真正写盘的文件数。 */
    public final int mirrorCopied;
    /** 因为「活动存档里还是原样」而跳过的文件数。 */
    public final int mirrorSkipped;
    /** 涉及的槽位文件总数。 */
    public final int mirrorFiles;
    /** 整段原地回滚的耗时（毫秒）。 */
    public final long totalMs;

    public RollbackOutcome(String summary, int mirrorCopied, int mirrorSkipped, int mirrorFiles, long totalMs) {
        this.summary = summary;
        this.mirrorCopied = mirrorCopied;
        this.mirrorSkipped = mirrorSkipped;
        this.mirrorFiles = mirrorFiles;
        this.totalMs = totalMs;
    }
}
