package cc.sighs.rewind.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import cc.sighs.rewind.common.RewindLog;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.common.config.RollbackSettings;
import cc.sighs.rewind.common.core.CheckpointWriter;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.common.spi.RollbackOutcome;
import cc.sighs.rewind.common.store.SnapshotStore;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * 存档点（存档 / 读档）的对外 API。
 *
 * <p>分两层，按你手上有没有客户端线程来选：
 *
 * <ul>
 *   <li><b>同步入口</b>（{@link #createCheckpoint} / {@link #rollbackInPlace} / {@link #restoreFiles}）：
 *       直接干活，不带过渡、不碰界面。前两个必须在<b>服务端线程</b>上调用；{@link #restoreFiles} 是纯文件操作，
 *       任意线程都行。这一层不引用任何客户端类，专用服务器上也能用。</li>
 *   <li><b>带过渡的异步入口</b>（{@link #requestCheckpoint} / {@link #requestRollback}）：
 *       等价于玩家按 F7 / F8——收起当前界面、跑过渡、在服务端线程上执行上面那层。
 *       需要客户端（集成服务器）；专用服务器上没有客户端桥接，调用会返回 false 并写一条日志。</li>
 * </ul>
 *
 * <p>查询类方法（{@link #hasCheckpoint} / {@link #describe} / {@link #describeAll} / {@link #listCheckpoints} /
 * {@link #readInventory}）只读存档目录，任意线程、任意端都能调。槽位管理
 * （{@link #deleteCheckpoint} / {@link #renameCheckpoint}）是纯文件操作，任意线程可调，
 * 但不要和存档点操作并发（两边都写同一张索引）。
 *
 * <p><b>关于 {@code server} 参数</b>：本类整体放在 common，不能引用 {@code net.minecraft.server.MinecraftServer}，
 * 所以凡是需要服务器对象的地方都收一个不透明的 {@code Object}，原样转交给当前 target 安装的
 * {@link RewindPlatform}。调用方直接传自己那个 {@code MinecraftServer} 即可。
 *
 * <p>典型用法（自己写命令 / 别的模组）：
 * <pre>{@code
 * // 服务端线程上
 * RewindResult result = RewindApi.createCheckpoint(server, RewindApi.DEFAULT_SLOT, "mymod");
 * if (!result.success) { ... }
 *
 * // 客户端线程上，等价于按 F7
 * RewindApi.requestCheckpoint("mymod");
 * }</pre>
 */
public final class RewindApi {
    /** F7 / F8 使用的默认槽位。 */
    public static final String DEFAULT_SLOT = SnapshotLayout.DEFAULT_SLOT;

    /**
     * 客户端在 setup 时注入的桥接：把「带过渡的异步入口」接到客户端的过渡状态机上。
     *
     * <p>做成接口是为了让本类不引用任何 {@code net.minecraft.client.*} / 客户端类——
     * 专用服务器上这个字段保持 null，本类仍然可以被加载和调用。
     */
    public interface ClientBridge {
        void requestCheckpoint(String slot, String source);

        void requestRollback(String slot, String source);

        boolean isBusy();
    }

    private static volatile ClientBridge clientBridge;
    private static volatile RewindResult lastResult;

    private RewindApi() {
    }

    // ------------------------------------------------------------------ 同步入口

    /**
     * 建立 / 覆盖存档点。**必须在服务端线程上调用。**
     *
     * @param server 平台自己的服务器对象（原样转交给 {@link RewindPlatform}）
     * @param source 记录进元数据的来源标记（谁触发的），只用于诊断
     */
    public static RewindResult createCheckpoint(Object server, String slot, String source) {
        if (!checkServerThread(server)) {
            return fail(RewindResult.Kind.CHECKPOINT, slot, new IllegalStateException("must be called on the server thread"));
        }
        RewindResult invalid = invalidSlot(RewindResult.Kind.CHECKPOINT, slot);
        if (invalid != null) {
            return invalid;
        }
        try {
            CheckpointWriter.Result written = CheckpointWriter.create(server, slot, source);
            RewindResult result = RewindResult.checkpoint(slot, written.meta, written.summary(), written.millis);
            lastResult = result;
            return result;
        } catch (Throwable t) {
            RewindLog.LOGGER.error("Rewind: checkpoint {} failed", slot, t);
            return fail(RewindResult.Kind.CHECKPOINT, slot, t);
        }
    }

    public static RewindResult createCheckpoint(Object server, String slot) {
        return createCheckpoint(server, slot, "api");
    }

    /**
     * 原地回滚到存档点：世界不关、客户端不重登，在活着的集成服务器里把世界倒回去。
     * **必须在服务端线程上调用。**
     *
     * <p>回滚窗口（{@link RewindState#beginDiscard()}）由本方法自己开关；调用方不需要管。
     * 平台没有原地回滚引擎时返回一条失败结果（调用方按需回退到「关世界 → 覆盖 → 重开」那条路）。
     */
    public static RewindResult rollbackInPlace(Object server, String slot) {
        RewindPlatform platform = RewindPlatforms.get();
        if (platform == null) {
            return fail(RewindResult.Kind.ROLLBACK, slot,
                    new IllegalStateException("Rewind platform has not been installed"));
        }
        if (!platform.supportsInPlaceRollback()) {
            return fail(RewindResult.Kind.ROLLBACK, slot,
                    new UnsupportedOperationException("in-place rollback is not supported on this platform"));
        }
        if (!checkServerThread(server)) {
            return fail(RewindResult.Kind.ROLLBACK, slot, new IllegalStateException("must be called on the server thread"));
        }
        RewindResult invalid = invalidSlot(RewindResult.Kind.ROLLBACK, slot);
        if (invalid != null) {
            return invalid;
        }
        Path worldRoot = worldRoot(server);
        RewindResult cooling = cooldownFailure(worldRoot, slot);
        if (cooling != null) {
            RewindLog.LOGGER.warn("Rewind: ignoring the rollback to {}: still on cooldown", slot);
            return cooling;
        }
        RewindResult result;
        // 回滚窗口在这一刻打开：从这里到文件还原完，任何世界落盘都是马上要被覆盖掉的
        RewindState.beginDiscard();
        try {
            RollbackOutcome rolled = platform.rollbackInPlace(server, worldRoot, slot);
            result = RewindResult.rollback(slot, true, rolled.summary, rolled.totalMs,
                    rolled.mirrorCopied, rolled.mirrorSkipped, rolled.mirrorFiles);
        } catch (Throwable t) {
            RewindLog.LOGGER.error("Rewind: in-place rollback for {} failed", slot, t);
            return fail(RewindResult.Kind.ROLLBACK, slot, t);
        } finally {
            RewindState.endDiscard();
        }
        markRolledBack(worldRoot, slot);
        lastResult = result;
        return result;
    }

    /**
     * 只把槽位文件镜像回活动存档，不碰内存里的世界。
     *
     * <p>「关世界 → 覆盖 → 重开」那条回退路径用它；原地回滚不走这里。任意线程可调。
     */
    public static RewindResult restoreFiles(Path worldRoot, String slot) {
        RewindResult cooling = cooldownFailure(worldRoot, slot);
        if (cooling != null) {
            RewindLog.LOGGER.warn("Rewind: ignoring the file restore of {}: still on cooldown", slot);
            return cooling;
        }
        try {
            CheckpointWriter.Result copied = CheckpointWriter.restoreFiles(worldRoot, slot);
            RewindResult result = RewindResult.rollback(slot, false, copied.summary(), copied.millis,
                    copied.mirror.copied, copied.mirror.skipped, copied.mirror.files.size());
            markRolledBack(worldRoot, slot);
            lastResult = result;
            return result;
        } catch (Throwable t) {
            RewindLog.LOGGER.error("Rewind: restoring files of slot {} failed", slot, t);
            return fail(RewindResult.Kind.ROLLBACK, slot, t);
        }
    }

    /**
     * 回溯成功后的收尾：把时间线的「头」移到这个槽位（世界就站在这个存档点上，之后建的存档点都挂在
     * 它下面），并记下这次回溯的时刻——读档冷却的起点。
     *
     * <p>纯索引操作，失败了也不影响回溯本身（只写一条日志）——最坏的情况是时间线少一条边，
     * 或者冷却没记上。
     */
    private static void markRolledBack(Path worldRoot, String slot) {
        try {
            Path indexFile = SnapshotLayout.indexFile(worldRoot);
            SnapshotIndex index = SnapshotIndex.load(indexFile);
            index.setHead(slot);
            index.setLastRollbackAt(System.currentTimeMillis());
            index.save(indexFile);
        } catch (IOException e) {
            RewindLog.LOGGER.warn("Rewind: failed to record the rollback of {} in the index", slot, e);
        }
    }

    /**
     * 读档冷却剩余时间（毫秒）；{@code 0} 表示现在可以读档。
     *
     * <p>冷却是「一次成功回溯之后要等一段时间才能再回溯」，时长见
     * {@link RollbackSettings#cooldownSeconds()}，起点记在存档点索引里（{@code lastRollbackAt}），
     * 所以退出游戏重进也绕不过去；换个存档目录则各算各的。只读，任意线程可调。
     */
    public static long rollbackCooldownRemainingMillis(Path worldRoot) {
        long cooldown = RollbackSettings.cooldownMillis();
        if (cooldown <= 0L || worldRoot == null) {
            return 0L;
        }
        try {
            SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
            long last = index.getLastRollbackAt();
            if (last <= 0L) {
                return 0L;
            }
            long remaining = last + cooldown - System.currentTimeMillis();
            return remaining > 0L ? remaining : 0L;
        } catch (IOException e) {
            RewindLog.LOGGER.error("Rewind: failed to read snapshot index", e);
            return 0L;
        }
    }

    /** 还在冷却中时给调用方的失败结果；不在冷却里返回 null。 */
    @Nullable
    private static RewindResult cooldownFailure(Path worldRoot, String slot) {
        long remaining = rollbackCooldownRemainingMillis(worldRoot);
        if (remaining <= 0L) {
            return null;
        }
        return fail(RewindResult.Kind.ROLLBACK, slot,
                new IllegalStateException("rollback is on cooldown for another " + (remaining / 1000L) + "s"));
    }

    // ------------------------------------------------------------------ 查询

    /** 活动存档目录（{@code level.dat} 所在目录）。 */
    public static Path worldRoot(Object server) {
        return RewindPlatforms.require().worldRoot(server);
    }

    /** 这个槽位有没有一个完整的存档点可还原。 */
    public static boolean hasCheckpoint(Path worldRoot, String slot) {
        SnapshotMeta meta = describe(worldRoot, slot);
        return meta != null && meta.isComplete();
    }

    /** 槽位的元数据；不存在时返回 null。 */
    @Nullable
    public static SnapshotMeta describe(Path worldRoot, String slot) {
        try {
            return SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot)).get(slot);
        } catch (IOException e) {
            RewindLog.LOGGER.error("Rewind: failed to read snapshot index", e);
            return null;
        }
    }

    /** 列出所有槽位的元数据，按槽位名排序。 */
    public static List<SnapshotMeta> listCheckpoints(Path worldRoot) {
        List<SnapshotMeta> metas = new ArrayList<>();
        try {
            SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
            for (String slot : index.slots()) {
                SnapshotMeta meta = index.get(slot);
                if (meta != null) {
                    metas.add(meta);
                }
            }
        } catch (IOException e) {
            RewindLog.LOGGER.error("Rewind: failed to read snapshot index", e);
        }
        return metas;
    }

    /** 一次读完整张索引：槽位名 → 元数据。界面渲染整页槽位用它，避免每个槽位各读一遍文件。 */
    public static Map<String, SnapshotMeta> describeAll(Path worldRoot) {
        Map<String, SnapshotMeta> metas = new LinkedHashMap<>();
        try {
            SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
            for (String slot : index.slots()) {
                SnapshotMeta meta = index.get(slot);
                if (meta != null) {
                    metas.put(slot, meta);
                }
            }
        } catch (IOException e) {
            RewindLog.LOGGER.error("Rewind: failed to read snapshot index", e);
        }
        return metas;
    }

    /** 界面上固定展示的槽位顺序（自动、快速，然后是 8 个手动槽位）。 */
    public static List<String> slots() {
        return SnapshotLayout.uiSlots();
    }

    /**
     * 世界当前站在哪个存档点上——时间线的「头」（索引里的 {@code head}）。
     *
     * @return 槽位名；还没建过点、或者头所在的槽位已经被删了时返回空串
     */
    public static String currentSlot(Path worldRoot) {
        try {
            SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
            String head = index.getHead();
            return index.get(head) == null ? "" : head;
        } catch (IOException e) {
            RewindLog.LOGGER.error("Rewind: failed to read snapshot index", e);
            return "";
        }
    }

    /** 槽位的背包快照；没建过点或文件缺失时返回空快照。 */
    public static SnapshotInventory readInventory(Path worldRoot, String slot) {
        return SnapshotInventory.load(SnapshotLayout.inventoryFile(worldRoot, slot));
    }

    // ------------------------------------------------------------------ 槽位管理（纯文件操作）

    /**
     * 删除一个槽位：目录、清单、背包快照、索引条目。
     *
     * <p>纯文件操作，任意线程可调；但**不要在存档点操作进行中调用**（{@link #isBusy()}），
     * 两边都会写同一张索引。
     *
     * @return 是否真的删掉了东西
     */
    public static boolean deleteCheckpoint(Path worldRoot, String slot) {
        if (!SnapshotLayout.isValidSlotName(slot)) {
            RewindLog.LOGGER.warn("Rewind: refusing to delete a checkpoint with an invalid slot name: {}", slot);
            return false;
        }
        try {
            return SnapshotStore.delete(worldRoot, slot);
        } catch (IOException e) {
            RewindLog.LOGGER.error("Rewind: failed to delete checkpoint {}", slot, e);
            return false;
        }
    }

    /**
     * 给槽位改名（只改索引里的显示名，槽位 id 与目录不动）。
     *
     * <p>名字必须通过 {@link SnapshotLayout#isValidDisplayName}；传空串表示清掉自定义名。
     * 覆盖这个槽位时名字会保留。
     *
     * @return 槽位是否存在
     */
    public static boolean renameCheckpoint(Path worldRoot, String slot, String displayName) {
        if (!SnapshotLayout.isValidSlotName(slot)) {
            RewindLog.LOGGER.warn("Rewind: refusing to rename a checkpoint with an invalid slot name: {}", slot);
            return false;
        }
        if (displayName != null && !displayName.trim().isEmpty()
                && !SnapshotLayout.isValidDisplayName(displayName)) {
            RewindLog.LOGGER.warn("Rewind: refusing to rename {} to \"{}\": name must be 1-{} characters",
                    slot, displayName, SnapshotLayout.NAME_MAX_LENGTH);
            return false;
        }
        try {
            return SnapshotStore.rename(worldRoot, slot, displayName);
        } catch (IOException e) {
            RewindLog.LOGGER.error("Rewind: failed to rename checkpoint {}", slot, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ 带过渡的异步入口

    /** 由客户端在 setup 时调用；专用服务器上不要调。 */
    public static void installClientBridge(ClientBridge bridge) {
        clientBridge = bridge;
    }

    /** 等价于按 F7（写 {@link #DEFAULT_SLOT}）。返回 false 表示当前没有客户端可用（专用服务器 / 还没 setup）。 */
    public static boolean requestCheckpoint(String source) {
        return requestCheckpoint(DEFAULT_SLOT, source);
    }

    /** 等价于按 F7，但写指定槽位。 */
    public static boolean requestCheckpoint(String slot, String source) {
        ClientBridge bridge = clientBridge;
        if (bridge == null) {
            RewindLog.LOGGER.warn("Rewind: requestCheckpoint needs a client; ignoring (slot={}, source={})", slot, source);
            return false;
        }
        bridge.requestCheckpoint(slot, source);
        return true;
    }

    /** 等价于按 F8（读 {@link #DEFAULT_SLOT}）。返回 false 表示当前没有客户端可用。 */
    public static boolean requestRollback(String source) {
        return requestRollback(DEFAULT_SLOT, source);
    }

    /** 等价于按 F8，但读指定槽位。 */
    public static boolean requestRollback(String slot, String source) {
        ClientBridge bridge = clientBridge;
        if (bridge == null) {
            RewindLog.LOGGER.warn("Rewind: requestRollback needs a client; ignoring (slot={}, source={})", slot, source);
            return false;
        }
        bridge.requestRollback(slot, source);
        return true;
    }

    /** 当前有没有一次操作正在进行（含过渡）。没有客户端时恒为 false。 */
    public static boolean isBusy() {
        ClientBridge bridge = clientBridge;
        return bridge != null && bridge.isBusy();
    }

    /**
     * 最近一次操作的结果；还没做过任何操作时为 null。
     *
     * <p>三个同步入口都会记录（带过渡的异步入口最终也是走它们），所以这里看到的始终是最后一次
     * 真正执行过的存档 / 读档结果。
     */
    @Nullable
    public static RewindResult lastResult() {
        return lastResult;
    }

    // ------------------------------------------------------------------ 内部

    private static boolean checkServerThread(Object server) {
        RewindPlatform platform = RewindPlatforms.get();
        if (platform == null) {
            RewindLog.LOGGER.error("Rewind: this entry point needs an installed platform");
            return false;
        }
        if (platform.isSameThread(server)) {
            return true;
        }
        RewindLog.LOGGER.error("Rewind: this entry point must be called on the server thread", new IllegalStateException());
        return false;
    }

    /** 槽位名会当目录名用，必须校验。名字不合法时返回一条失败结果，合法时返回 null。 */
    @Nullable
    private static RewindResult invalidSlot(RewindResult.Kind kind, String slot) {
        if (SnapshotLayout.isValidSlotName(slot)) {
            return null;
        }
        return fail(kind, slot, new IllegalArgumentException("invalid slot name: " + slot));
    }

    private static RewindResult fail(RewindResult.Kind kind, String slot, Throwable t) {
        RewindResult result = RewindResult.failure(kind, slot, t);
        lastResult = result;
        return result;
    }
}
