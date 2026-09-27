package cc.sighs.rewind.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.server.CheckpointWriter;
import cc.sighs.rewind.server.InPlaceRollback;
import cc.sighs.rewind.snapshot.SnapshotIndex;
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
 *       需要客户端（集成服务器）；专用服务器上没有客户端桥接，调用会返回 false 并写一条日志。</li>
 * </ul>
 *
 * <p>查询类方法（{@link #hasCheckpoint} / {@link #describe} / {@link #listCheckpoints}）只读存档目录，
 * 任意线程、任意端都能调。
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
        void requestCheckpoint(String source);

        void requestRollback(String source);

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
        Path worldRoot = worldRoot(server);
        // 回滚窗口在这一刻打开：从这里到文件还原完，任何世界落盘都是马上要被覆盖掉的
        Rewind.beginDiscard();
        try {
            InPlaceRollback.Result rolled = InPlaceRollback.run(server, worldRoot, slot);
            RewindResult result = RewindResult.rollback(slot, true, rolled.summary(), rolled.totalMs,
                    rolled.mirrorCopied, rolled.mirrorSkipped, rolled.mirrorFiles);
            lastResult = result;
            return result;
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: in-place rollback for {} failed", slot, t);
            return fail(RewindResult.Kind.ROLLBACK, slot, t);
        } finally {
            Rewind.endDiscard();
        }
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
            lastResult = result;
            return result;
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: restoring files of slot {} failed", slot, t);
            return fail(RewindResult.Kind.ROLLBACK, slot, t);
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

    // ------------------------------------------------------------------ 带过渡的异步入口

    /** 由客户端在 setup 时调用；专用服务器上不要调。 */
    public static void installClientBridge(ClientBridge bridge) {
        clientBridge = bridge;
    }

    /** 等价于按 F7。返回 false 表示当前没有客户端可用（专用服务器 / 还没 setup）。 */
    public static boolean requestCheckpoint(String source) {
        ClientBridge bridge = clientBridge;
        if (bridge == null) {
            Rewind.LOGGER.warn("Rewind: requestCheckpoint needs a client; ignoring (source={})", source);
            return false;
        }
        bridge.requestCheckpoint(source);
        return true;
    }

    /** 等价于按 F8。返回 false 表示当前没有客户端可用。 */
    public static boolean requestRollback(String source) {
        ClientBridge bridge = clientBridge;
        if (bridge == null) {
            Rewind.LOGGER.warn("Rewind: requestRollback needs a client; ignoring (source={})", source);
            return false;
        }
        bridge.requestRollback(source);
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

    private static RewindResult fail(RewindResult.Kind kind, String slot, Throwable t) {
        RewindResult result = RewindResult.failure(kind, slot, t);
        lastResult = result;
        return result;
    }
}
