package cc.sighs.rewind.api;

import java.nio.file.Path;
import javax.annotation.Nullable;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * 一次回溯请求：common 在动手之前交给平台侧的那份说明，平台把它变成自己的加载器事件
 * （Forge / NeoForge 的 {@code cc.sighs.rewind.event.RollbackStartEvent}）。
 *
 * <p>只描述「谁、要回到哪个存档点、走哪条路」，没有可写状态——唯一的例外是
 * {@link #cancelReason}：监听者取消这次回溯时把原因写进去，它会进日志，也会出现在调用方拿到的
 * {@link RollbackCanceledException} 里。
 *
 * <p>同一次回溯的结束通知复用同一个对象（见 {@link RewindPlatform#afterRollback}）。
 */
public final class RollbackRequest {
    /** 活动存档根目录。 */
    public final Path worldRoot;
    /** 要回到的槽位。 */
    public final String slot;
    /** 触发来源：{@code key} / {@code ui} / {@code command} / {@code death} / {@code repair} / {@code api}… */
    public final String source;
    /**
     * 这次尝试走的是哪条路：{@code true} = 原地回滚（世界不关），
     * {@code false} = 只把槽位文件覆盖回活动存档（「关世界 → 覆盖 → 重开」那条路的第二步）。
     *
     * <p>一次用户可见的回溯可能走两轮：原地回滚失败之后客户端会退回文件覆盖那条路，
     * 于是会再发一次开始 / 结束。
     */
    public final boolean inPlace;
    /** 目标存档点的元数据；槽位不存在或索引读不出来时为 null。 */
    @Nullable public final SnapshotMeta meta;
    /** 取消原因：监听者取消这次回溯时写这里（空 = 没有原因）。 */
    public String cancelReason = "";

    public RollbackRequest(Path worldRoot, String slot, String source, boolean inPlace,
            @Nullable SnapshotMeta meta) {
        this.worldRoot = worldRoot;
        this.slot = slot;
        this.source = source;
        this.inPlace = inPlace;
        this.meta = meta;
    }
}
