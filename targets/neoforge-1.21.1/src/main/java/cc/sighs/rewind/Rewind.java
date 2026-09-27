package cc.sighs.rewind;

import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongSet;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import cc.sighs.RewindNeoForge121;
import cc.sighs.rewind.snapshot.SnapshotLayout;

/** 模组共用的常量、日志与全局状态。 */
public final class Rewind {
    public static final String MOD_ID = RewindNeoForge121.MOD_ID;
    public static final Logger LOGGER = LogUtils.getLogger();

    /** F7 / F8 使用的滚动槽位。 */
    public static final String SLOT = SnapshotLayout.DEFAULT_SLOT;

    /**
     * 回滚窗口标记：置位期间世界正准备被快照覆盖，任何「写下去也会被丢掉」的落盘都应该跳过
     * （由 {@code cc.sighs.mixin} 下的 Mixin 检查）。只在服务器线程/客户端线程的同一处设置与清除。
     */
    private static volatile boolean discarding;

    public static void beginDiscard() {
        discarding = true;
    }

    public static void endDiscard() {
        discarding = false;
    }

    public static boolean isDiscarding() {
        return discarding;
    }

    /**
     * 原地回滚「本轮自己接管的区块」名单：{@code ChunkMap 实例 → 区块位置}。
     *
     * <p>回滚把这些区块从内存里丢掉之后，ticket 系统仍然认为它们「该加载」——邻居传播会把
     * {@code ChunkMap.updateChunkScheduling} 又叫回来、把 holder 重建出来，顺带拉起 worldgen 任务并把
     * {@code generationRefCount} 钉在正被卸载的区块上，于是卸载永远跑不完。所以这段时间里，这些位置
     * 只允许降级 / 卸载，不允许重建。只有服务端线程会读写它。
     */
    private static final Map<Object, LongSet> unloadGuards = new HashMap<>();

    public static void beginUnloadGuard(Object chunkMap, LongSet positions) {
        unloadGuards.put(chunkMap, positions);
    }

    public static void clearUnloadGuards() {
        unloadGuards.clear();
    }

    public static boolean isRecreationBlocked(Object chunkMap, long pos) {
        LongSet positions = unloadGuards.get(chunkMap);
        return positions != null && positions.contains(pos);
    }

    private Rewind() {
    }
}
