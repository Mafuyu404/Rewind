package cc.sighs.rewind.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.server.CheckpointWriter;
import cc.sighs.rewind.server.InPlaceRollback;
import cc.sighs.rewind.server.SnapshotStore;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

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
 *       需要客户端（集成服务器）；专用服务器上没有客户端桥接，调用会返回 false 并写一条日志。
 *       不带槽位参数的版本操作 {@link #DEFAULT_SLOT}，带槽位参数的版本可以指向任意槽位（界面用它）。</li>
 * </ul>
 *
 * <p>查询类方法（{@link #hasCheckpoint} / {@link #describe} / {@link #describeAll} / {@link #listCheckpoints} /
 * {@link #readInventory}）只读存档目录，任意线程、任意端都能调。槽位管理
 * （{@link #deleteCheckpoint} / {@link #renameCheckpoint}）是纯文件操作，任意线程可调，
 * 但不要和存档点操作并发（两边都写同一张索引）。
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
     * @param source 记录进元数据的来源标记（谁触发的），只用于诊断
     */
    public static RewindResult createCheckpoint(MinecraftServer server, String slot, String source) {
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
            Rewind.LOGGER.error("Rewind: checkpoint {} failed", slot, t);
            return fail(RewindResult.Kind.CHECKPOINT, slot, t);
        }
    }

    public static RewindResult createCheckpoint(MinecraftServer server, String slot) {
        return createCheckpoint(server, slot, "api");
    }

    /**
     * 原地回滚到存档点：世界不关、客户端不重登，在活着的集成服务器里把世界倒回去。
     * **必须在服务端线程上调用。**
     *
     * <p>回滚窗口（{@code Rewind.beginDiscard()}）由本方法自己开关；调用方不需要管。
     */
    public static RewindResult rollbackInPlace(MinecraftServer server, String slot) {
        if (!checkServerThread(server)) {
            return fail(RewindResult.Kind.ROLLBACK, slot, new IllegalStateException("must be called on the server thread"));
        }
        RewindResult invalid = invalidSlot(RewindResult.Kind.ROLLBACK, slot);
        if (invalid != null) {
            return invalid;
        }
        Path worldRoot = worldRoot(server);
        RewindResult result;
        // 回滚窗口在这一刻打开：从这里到文件还原完，任何世界落盘都是马上要被覆盖掉的
        Rewind.beginDiscard();
        try {
            InPlaceRollback.Result rolled = InPlaceRollback.run(server, worldRoot, slot);
            result = RewindResult.rollback(slot, true, rolled.summary(), rolled.totalMs,
                    rolled.mirrorCopied, rolled.mirrorSkipped, rolled.mirrorFiles);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: in-place rollback for {} failed", slot, t);
            return fail(RewindResult.Kind.ROLLBACK, slot, t);
        } finally {
            Rewind.endDiscard();
        }
        moveTimelineHead(worldRoot, slot);
        lastResult = result;
        return result;
    }

    /**
     * 只把槽位文件镜像回活动存档，不碰内存里的世界。
     *
     * <p>「关世界 → 覆盖 → 重开」那条回退路径用它；原地回滚不走这里。任意线程可调。
     */
    public static RewindResult restoreFiles(Path worldRoot, String slot) {
        try {
            CheckpointWriter.Result copied = CheckpointWriter.restoreFiles(worldRoot, slot);
            RewindResult result = RewindResult.rollback(slot, false, copied.summary(), copied.millis,
                    copied.mirror.copied, copied.mirror.skipped, copied.mirror.files.size());
            moveTimelineHead(worldRoot, slot);
            lastResult = result;
            return result;
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: restoring files of slot {} failed", slot, t);
            return fail(RewindResult.Kind.ROLLBACK, slot, t);
        }
    }

    /**
     * 把时间线的「头」移到这个槽位：回溯之后世界就站在这个存档点上，之后建的存档点都挂在它下面。
     *
     * <p>纯索引操作，失败了也不影响回溯本身（只写一条日志）——最坏的情况是下一个存档点的父节点
     * 还是上一个，时间线少一条边而已。
     */
    private static void moveTimelineHead(Path worldRoot, String slot) {
        try {
            Path indexFile = SnapshotLayout.indexFile(worldRoot);
            SnapshotIndex index = SnapshotIndex.load(indexFile);
            index.setHead(slot);
            index.save(indexFile);
        } catch (IOException e) {
            Rewind.LOGGER.warn("Rewind: failed to move the timeline head to {}", slot, e);
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 活动存档目录（{@code level.dat} 所在目录）。 */
    public static Path worldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
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
            Rewind.LOGGER.error("Rewind: failed to read snapshot index", e);
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
            Rewind.LOGGER.error("Rewind: failed to read snapshot index", e);
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
            Rewind.LOGGER.error("Rewind: failed to read snapshot index", e);
        }
        return metas;
    }

    /** 界面上固定展示的槽位顺序（自动、快速，然后是 8 个手动槽位）。 */
    public static List<String> slots() {
        return SnapshotLayout.uiSlots();
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
            Rewind.LOGGER.warn("Rewind: refusing to delete a checkpoint with an invalid slot name: {}", slot);
            return false;
        }
        try {
            return SnapshotStore.delete(worldRoot, slot);
        } catch (IOException e) {
            Rewind.LOGGER.error("Rewind: failed to delete checkpoint {}", slot, e);
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
            Rewind.LOGGER.warn("Rewind: refusing to rename a checkpoint with an invalid slot name: {}", slot);
            return false;
        }
        if (displayName != null && !displayName.trim().isEmpty()
                && !SnapshotLayout.isValidDisplayName(displayName)) {
            Rewind.LOGGER.warn("Rewind: refusing to rename {} to \"{}\": name must be 1-{} characters",
                    slot, displayName, SnapshotLayout.NAME_MAX_LENGTH);
            return false;
        }
        try {
            return SnapshotStore.rename(worldRoot, slot, displayName);
        } catch (IOException e) {
            Rewind.LOGGER.error("Rewind: failed to rename checkpoint {}", slot, e);
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
            Rewind.LOGGER.warn("Rewind: requestCheckpoint needs a client; ignoring (slot={}, source={})", slot, source);
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
            Rewind.LOGGER.warn("Rewind: requestRollback needs a client; ignoring (slot={}, source={})", slot, source);
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

    private static boolean checkServerThread(MinecraftServer server) {
        if (server.isSameThread()) {
            return true;
        }
        Rewind.LOGGER.error("Rewind: this entry point must be called on the server thread", new IllegalStateException());
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
