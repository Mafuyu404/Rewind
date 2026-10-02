package cc.sighs.rewind.common;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 跨模块共享的全局状态：回滚窗口标记与「原地回滚本轮自己接管的区块」名单。
 *
 * <p>这两样东西原先是 target 侧 {@code Rewind} 的静态字段，但它们没有任何 Minecraft 依赖，
 * 而且 target 的 Mixin 与 common 的回滚编排都要读写，所以搬到 common。
 *
 * <ul>
 *   <li>{@link #isDiscarding()}：置位期间世界正准备被快照覆盖，任何「写下去也会被丢掉」的落盘都应该跳过
 *       （Mixin 检查它）。只在服务器线程 / 客户端线程的同一处设置与清除。</li>
 *   <li>{@link #beginUnloadGuard} / {@link #isRecreationBlocked}：原地回滚把区块从内存里丢掉之后，
 *       ticket 系统仍然认为它们「该加载」，邻居传播会把 {@code ChunkMap.updateChunkScheduling} 又叫回来、
 *       重建 holder 并钉住 worldgen 引用计数。这段时间里这些位置只允许降级 / 卸载，不允许重建。
 *       区块映射用不透明的 {@code Object} 当键，所以这里不需要任何 Minecraft 类型。只有服务端线程读写它。</li>
 * </ul>
 */
public final class RewindState {
    private static volatile boolean discarding;

    /** {@code ChunkMap 实例 → 已排序的区块位置}。 */
    private static final Map<Object, long[]> unloadGuards = new HashMap<>();

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
     * 登记一批「本轮被回滚接管」的区块。
     *
     * @param positions 区块位置（{@code ChunkPos.toLong()}），**必须按升序排好**——查询走二分查找
     */
    public static void beginUnloadGuard(Object chunkMap, long[] positions) {
        unloadGuards.put(chunkMap, positions);
    }

    public static void clearUnloadGuards() {
        unloadGuards.clear();
    }

    public static boolean isRecreationBlocked(Object chunkMap, long pos) {
        long[] positions = unloadGuards.get(chunkMap);
        return positions != null && Arrays.binarySearch(positions, pos) >= 0;
    }

    private RewindState() {
    }
}
