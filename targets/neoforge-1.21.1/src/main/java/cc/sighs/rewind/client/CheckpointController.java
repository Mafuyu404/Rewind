package cc.sighs.rewind.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.server.WorldFlush;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotManifest;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import cc.sighs.rewind.snapshot.SnapshotMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 存档点（F7 建立 / 覆盖，F8 回溯）的客户端状态机。
 *
 * <p>F7：弹一个会暂停世界的进度屏 → 等集成服务器真的停了 → 服务器线程强同步落盘 →
 * 后台线程把存档目录镜像进 {@code rewind_snapshots/quick/} → 关屏回到游戏。
 *
 * <p>F8：确认后走原版「保存并退出」同一条路（{@code Minecraft.disconnect}），返回时
 * 服务器线程已结束、{@code session.lock} 已释放、所有 region 文件句柄已关闭，
 * 此时才能安全覆盖 .mca；覆盖完再 {@code WorldOpenFlows.openWorld} 重新进世界。
 *
 * <p>整个流程不阻塞客户端线程（除了 disconnect 自身，它的忙等由原版负责）。
 */
public final class CheckpointController {
    public enum Phase {
        IDLE,
        CONFIRMING,
        PAUSING,
        FLUSHING,
        SNAPSHOTTING,
        CLOSING_WORLD,
        REWRITING,
        REOPENING
    }

    /** 供外部（命令、自测）观察的结果。 */
    public enum Outcome {
        NONE,
        SUCCESS,
        FAILED
    }

    private static final int TIMEOUT_PAUSE_TICKS = 200;
    private static final int TIMEOUT_FLUSH_TICKS = 1200;
    private static final int TIMEOUT_SNAPSHOT_TICKS = 24000;
    private static final int TIMEOUT_REWRITE_TICKS = 24000;

    private static Phase phase = Phase.IDLE;
    private static String statusKey = "rewind.screen.title";
    private static String detail = "";
    private static String requestSource = "";
    private static int phaseTicks;

    private static CompletableFuture<Void> flushFuture;
    private static WorldFlush.Result flushed;
    private static Path worldRoot;
    private static String levelId = "";
    private static String slot = Rewind.SLOT;

    private static volatile boolean workerFinished;
    private static volatile Throwable workerFailure;
    private static volatile String workerSummary = "";
    private static volatile int workerIndex;
    private static volatile int workerTotal;

    private static Outcome lastOutcome = Outcome.NONE;
    private static String lastMessage = "";
    /** 世界正处于「已关闭 / 正在重开」时，消息先存下来，等玩家回来再发。 */
    private static Component pendingMessage;

    private CheckpointController() {
    }

    public static Phase phase() {
        return phase;
    }

    public static boolean isBusy() {
        return phase != Phase.IDLE;
    }

    public static Outcome lastOutcome() {
        return lastOutcome;
    }

    public static String lastMessage() {
        return lastMessage;
    }

    public static Component statusComponent() {
        return Component.translatable(statusKey);
    }

    public static Component detailComponent() {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        return Component.literal(detail);
    }

    /** 0-100，未知时返回 -1（进度条画成空的）。 */
    public static int progressPercent() {
        int total = workerTotal;
        if (phase != Phase.SNAPSHOTTING && phase != Phase.REWRITING) {
            return -1;
        }
        if (total <= 0) {
            return -1;
        }
        int done = Math.min(workerIndex, total);
        return done * 100 / total;
    }

    // ------------------------------------------------------------------ F7

    /** F7：建立/覆盖存档点。 */
    public static void requestSnapshot(String source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ensureUsable(minecraft)) {
            return;
        }
        requestSource = source;
        lastOutcome = Outcome.NONE;
        lastMessage = "";
        phase = Phase.PAUSING;
        phaseTicks = 0;
        statusKey = "rewind.progress.pausing_snapshot";
        detail = "";
        minecraft.setScreen(new RewindProgressScreen());
    }

    // ------------------------------------------------------------------ F8

    /** F8：回溯到存档点。{@code confirm} 为 true 时先弹确认屏。 */
    public static void requestRestore(String source, boolean confirm) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ensureUsable(minecraft)) {
            return;
        }
        Path world = minecraft.getSingleplayerServer().getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        SnapshotMeta meta = loadMeta(world, slot);
        if (meta == null || !meta.isComplete()) {
            fail(minecraft, "rewind.error.no_snapshot", String.valueOf(world));
            return;
        }
        requestSource = source;
        lastOutcome = Outcome.NONE;
        lastMessage = "";
        if (!confirm) {
            startRestore(minecraft);
            return;
        }
        phase = Phase.CONFIRMING;
        phaseTicks = 0;
        minecraft.setScreen(new ConfirmScreen(
                CheckpointController::acceptRestore,
                Component.translatable("rewind.confirm.title"),
                Component.translatable("rewind.confirm.message", meta.worldName, describeAge(meta.savedAtMillis)),
                Component.translatable("rewind.confirm.yes"),
                Component.translatable("rewind.confirm.no")));
    }

    /** 确认屏（或自测）的回调。 */
    public static void acceptRestore(boolean accepted) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!accepted) {
            phase = Phase.IDLE;
            minecraft.setScreen(null);
            return;
        }
        minecraft.setScreen(new RewindProgressScreen());
        // 屏幕回调里直接做阻塞式 disconnect 容易和 vanilla 的按钮处理互相踩，放到下一帧的主线程任务里
        minecraft.execute(() -> startRestore(minecraft));
    }

    private static void startRestore(Minecraft minecraft) {
        if (minecraft.level == null || !minecraft.hasSingleplayerServer()) {
            fail(minecraft, "rewind.error.no_world", "");
            return;
        }
        lastOutcome = Outcome.NONE;
        lastMessage = "";
        phase = Phase.CLOSING_WORLD;
        phaseTicks = 0;
        statusKey = "rewind.progress.closing_world";
        detail = "";

        IntegratedServer server = minecraft.getSingleplayerServer();
        worldRoot = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        levelId = String.valueOf(worldRoot.getFileName());

        minecraft.setScreen(new RewindProgressScreen());
        Rewind.LOGGER.info("Rewind: closing world {} to restore slot {}", levelId, slot);
        minecraft.level.disconnect();
        // 阻塞直到集成服务器线程结束：MinecraftServer.stopServer → ServerLevel.close → RegionFile.close，
        // 之后 session.lock 与所有 .mca 句柄都已释放，可以安全覆盖文件。
        minecraft.disconnect(new RewindProgressScreen());

        if (minecraft.level != null) {
            fail(minecraft, "rewind.error.close_failed", "");
            return;
        }
        beginRewrite(minecraft);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(Minecraft minecraft) {
        flushPendingMessage(minecraft);
        if (phase == Phase.IDLE) {
            return;
        }
        phaseTicks++;

        switch (phase) {
            case PAUSING: {
                IntegratedServer server = minecraft.getSingleplayerServer();
                if (server == null || minecraft.level == null) {
                    fail(minecraft, "rewind.error.server_gone", "");
                    return;
                }
                if (!server.isPaused()) {
                    if (phaseTicks > TIMEOUT_PAUSE_TICKS) {
                        fail(minecraft, "rewind.error.pause_timeout", "");
                    }
                    return;
                }
                beginFlush(server);
                return;
            }
            case FLUSHING: {
                if (flushFuture == null) {
                    fail(minecraft, "rewind.error.flush_failed", "no future");
                    return;
                }
                if (!flushFuture.isDone()) {
                    if (phaseTicks > TIMEOUT_FLUSH_TICKS) {
                        fail(minecraft, "rewind.error.flush_timeout", "");
                    }
                    return;
                }
                try {
                    flushFuture.join();
                } catch (CompletionException e) {
                    fail(minecraft, "rewind.error.flush_failed", String.valueOf(e.getCause()));
                    return;
                }
                beginSnapshotCopy();
                return;
            }
            case SNAPSHOTTING: {
                if (!workerFinished) {
                    if (phaseTicks > TIMEOUT_SNAPSHOT_TICKS) {
                        fail(minecraft, "rewind.error.copy_timeout", "");
                    }
                    return;
                }
                if (workerFailure != null) {
                    fail(minecraft, "rewind.error.copy_failed", workerFailure.toString());
                    return;
                }
                SnapshotMeta meta = flushed == null ? null : flushed.meta;
                String summary = workerSummary;
                phase = Phase.IDLE;
                minecraft.setScreen(null);
                succeed(minecraft, "rewind.msg.snapshot_done",
                        summary + (meta == null ? "" : " | " + describeGameTime(meta.gameTime)));
                return;
            }
            case REWRITING: {
                if (!workerFinished) {
                    if (phaseTicks > TIMEOUT_REWRITE_TICKS) {
                        fail(minecraft, "rewind.error.rewrite_timeout", "");
                    }
                    return;
                }
                if (workerFailure != null) {
                    fail(minecraft, "rewind.error.rewrite_failed", workerFailure.toString());
                    return;
                }
                beginReopen(minecraft);
                return;
            }
            default:
                // CONFIRMING / CLOSING_WORLD / REOPENING 不需要推进
        }
    }

    // ------------------------------------------------------------------ 各阶段实现

    private static void beginFlush(IntegratedServer server) {
        phase = Phase.FLUSHING;
        phaseTicks = 0;
        statusKey = "rewind.progress.flushing";
        detail = "";
        CompletableFuture<Void> future = new CompletableFuture<>();
        flushFuture = future;
        server.execute(() -> {
            try {
                flushed = WorldFlush.flush(server, slot, requestSource);
                future.complete(null);
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: flush failed", t);
                future.completeExceptionally(t);
            }
        });
    }

    private static void beginSnapshotCopy() {
        phase = Phase.SNAPSHOTTING;
        phaseTicks = 0;
        statusKey = "rewind.progress.copying";
        detail = "";

        if (flushed == null) {
            fail(Minecraft.getInstance(), "rewind.error.copy_failed", "flush result missing");
            return;
        }
        final Path world = flushed.worldRoot;
        final SnapshotMeta base = flushed.meta.copy();
        startWorker("rewind-snapshot", () -> {
            Path snapshotRoot = SnapshotLayout.snapshotRoot(world);
            Files.createDirectories(snapshotRoot);
            Path indexFile = SnapshotLayout.indexFile(world);
            Path manifestFile = SnapshotLayout.manifestFile(world, slot);

            SnapshotMeta incomplete = base.copy();
            incomplete.status = SnapshotLayout.STATUS_INCOMPLETE;
            SnapshotIndex index = SnapshotIndex.load(indexFile);
            index.put(incomplete, slot);
            index.save(indexFile);

            SnapshotManifest previous = SnapshotManifest.load(manifestFile);
            SnapshotMirror.Result result = SnapshotMirror.mirror(
                    world, SnapshotLayout.slotDir(world, slot), previous,
                    (indexInRun, total, relative) -> {
                        workerIndex = indexInRun;
                        workerTotal = total;
                    });
            result.manifest.save(manifestFile);

            SnapshotMeta complete = base.copy();
            complete.status = SnapshotLayout.STATUS_COMPLETE;
            complete.fileCount = result.files.size();
            complete.totalBytes = result.totalBytes;
            index = SnapshotIndex.load(indexFile);
            index.put(complete, slot);
            index.save(indexFile);

            workerSummary = result.summary();
            Rewind.LOGGER.info("Rewind: snapshot {} written ({})", slot, result.summary());
        });
    }

    private static void beginRewrite(Minecraft minecraft) {
        phase = Phase.REWRITING;
        phaseTicks = 0;
        statusKey = "rewind.progress.rewriting";
        detail = String.valueOf(worldRoot);
        final Path world = worldRoot;
        final Path snapshotDir = SnapshotLayout.slotDir(world, slot);
        startWorker("rewind-restore", () -> {
            if (!Files.isDirectory(snapshotDir)) {
                throw new IOException("snapshot directory missing: " + snapshotDir);
            }
            // 回溯方向永远全量拷贝：活存档里的文件可能比快照新
            SnapshotMirror.Result result = SnapshotMirror.mirror(
                    snapshotDir, world, null,
                    (indexInRun, total, relative) -> {
                        workerIndex = indexInRun;
                        workerTotal = total;
                    });
            workerSummary = result.summary();
            Rewind.LOGGER.info("Rewind: restored slot {} into {} ({})", slot, world, result.summary());
        });
    }

    private static void beginReopen(Minecraft minecraft) {
        phase = Phase.REOPENING;
        phaseTicks = 0;
        statusKey = "rewind.progress.reopening";
        detail = levelId;
        Rewind.LOGGER.info("Rewind: reopening world {}", levelId);
        try {
            minecraft.createWorldOpenFlows().openWorld(levelId, () -> {
                lastOutcome = Outcome.FAILED;
                lastMessage = "reopen aborted";
                Rewind.LOGGER.error("Rewind: reopening {} was aborted by the user", levelId);
                minecraft.setScreen(new TitleScreen());
            });
        } catch (Throwable t) {
            fail(minecraft, "rewind.error.reopen_failed", t.toString());
            return;
        }
        // openWorld 返回时 ClientLevel 可能还没建好——客户端要等后续 tick 处理完登录包才会 setLevel，
        // 所以这里不能拿 level 判成败（真正失败会走上面传入的 onFail 回调）。
        phase = Phase.IDLE;
        succeed(minecraft, "rewind.msg.restored", workerSummary);
    }

    private static void startWorker(String name, WorkerBody body) {
        workerFinished = false;
        workerFailure = null;
        workerSummary = "";
        workerIndex = 0;
        workerTotal = 0;
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: {} failed", name, t);
                workerFailure = t;
            } finally {
                workerFinished = true;
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    private interface WorkerBody {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ 辅助

    private static boolean ensureUsable(Minecraft minecraft) {
        if (isBusy()) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable("rewind.error.busy"), false);
            }
            return false;
        }
        if (!minecraft.hasSingleplayerServer() || minecraft.level == null || minecraft.player == null) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable("rewind.error.no_world"), false);
            }
            return false;
        }
        // 局域网开放时服务端不会因弹屏而暂停，没有一致的快照可言
        if (minecraft.getSingleplayerServer().isPublished()) {
            minecraft.player.displayClientMessage(Component.translatable("rewind.error.lan_published"), false);
            return false;
        }
        return true;
    }

    private static SnapshotMeta loadMeta(Path world, String slot) {
        try {
            return SnapshotIndex.load(SnapshotLayout.indexFile(world)).get(slot);
        } catch (IOException e) {
            Rewind.LOGGER.error("Rewind: failed to read snapshot index", e);
            return null;
        }
    }

    private static void succeed(Minecraft minecraft, String messageKey, String detailText) {
        lastOutcome = Outcome.SUCCESS;
        lastMessage = describe(messageKey, detailText);
        Rewind.LOGGER.info("Rewind: {}", lastMessage);
        announce(minecraft, messageKey, detailText);
    }

    private static void fail(Minecraft minecraft, String messageKey, String detailText) {
        lastOutcome = Outcome.FAILED;
        lastMessage = describe(messageKey, detailText);
        Rewind.LOGGER.error("Rewind: {}", lastMessage);
        announce(minecraft, messageKey, detailText);
        if (minecraft.level != null) {
            minecraft.setScreen(null);
        } else if (minecraft.screen instanceof RewindProgressScreen) {
            // 世界已经关了但回不去：退回标题屏，别把玩家卡在进度屏上
            minecraft.setScreen(new TitleScreen());
        }
        phase = Phase.IDLE;
    }

    private static String describe(String messageKey, String detailText) {
        return Component.translatable(messageKey).getString()
                + (detailText == null || detailText.isEmpty() ? "" : " (" + detailText + ")");
    }

    /** 回溯期间玩家实体还不存在，消息先缓存，等世界回来再发。 */
    private static void announce(Minecraft minecraft, String messageKey, String detailText) {
        Component message = detailText == null || detailText.isEmpty()
                ? Component.translatable(messageKey)
                : Component.translatable(messageKey).append(" " + detailText);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, false);
        } else {
            pendingMessage = message;
        }
    }

    private static void flushPendingMessage(Minecraft minecraft) {
        if (pendingMessage != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(pendingMessage, false);
            pendingMessage = null;
        }
    }

    private static String describeAge(long savedAtMillis) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - savedAtMillis) / 1000L);
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "min";
        }
        return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "min";
    }

    private static String describeGameTime(long gameTime) {
        long days = gameTime / 24000L;
        long timeOfDay = gameTime % 24000L;
        long hours = (timeOfDay / 1000L + 6L) % 24L;
        long minutes = (timeOfDay % 1000L) * 60L / 1000L;
        return String.format("day=%d %02d:%02d", days, hours, minutes);
    }
}
