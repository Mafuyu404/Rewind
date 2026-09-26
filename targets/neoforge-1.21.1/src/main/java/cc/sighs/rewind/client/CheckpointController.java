package cc.sighs.rewind.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.mojang.serialization.Dynamic;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.server.WorldFlush;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotManifest;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import cc.sighs.rewind.snapshot.SnapshotMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;

/**
 * 存档点（F7 建立 / 覆盖，F8 回溯）的客户端状态机。
 *
 * <p>F7：弹一个会暂停世界的进度屏 → 等集成服务器真的停了 → 服务器线程强同步落盘 →
 * 后台线程把存档目录镜像进 {@code rewind_snapshots/quick/} → 关屏回到游戏。
 *
 * <p>F8：直接走原版「保存并退出」同一条路（{@code Minecraft.disconnect}），返回时
 * 服务器线程已结束、{@code session.lock} 已释放、所有 region 文件句柄已关闭，
 * 此时才能安全覆盖 .mca；覆盖完走快速重启重新进世界。
 *
 * <p>整个流程不阻塞客户端线程（除了 disconnect 自身，它的忙等由原版负责）。
 */
public final class CheckpointController {
    public enum Phase {
        IDLE,
        /** F7：等过渡淡入完成，然后开始落盘。 */
        PAUSING,
        FLUSHING,
        SNAPSHOTTING,
        /** F8：等过渡淡入完成，然后关世界。 */
        CLOSING_WORLD,
        REWRITING,
        REOPENING,
        /** 新世界已经回来，等第一帧画出来再让过渡淡出。 */
        REVEALING
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
    /** 服务端线程最多冻结多久（秒）。客户端的 keep-alive 超时远大于这个值，超了也只是提前放行。 */
    private static final long SERVER_FREEZE_SECONDS = 20L;
    /** 新世界回来了之后再等几 tick 才收起模糊，避免露出还没收到区块的空画面。 */
    private static final int REVEAL_SETTLE_TICKS = 10;

    /**
     * 操作期间显示的提示屏：用原版自己的 {@link GenericMessageScreen}（原版「保存并退出」用的就是它），
     * 不自己造界面。它还承担一个功能职责——{@code Screen.isPauseScreen()} 默认为 true，
     * 屏幕一挂上集成服务器就会暂停，世界不再 tick、不再产生新写入。
     */
    private static final Component SAVING_SCREEN = Component.translatable("menu.savingLevel");

    private static Phase phase = Phase.IDLE;
    private static String requestSource = "";
    private static int phaseTicks;

    private static CompletableFuture<Void> flushFuture;
    /**
     * 存档时用来冻结服务端线程：落盘做完之后让服务端线程停在这里，直到快照写完才放行。
     * 没有界面就没有「暂停世界」这个手段，只能靠把线程摁住来保证拷贝期间没人写盘。
     */
    private static CountDownLatch releaseServerLatch;
    private static WorldFlush.Result flushed;
    private static Path worldRoot;
    private static String levelId = "";
    private static String slot = Rewind.SLOT;
    /** 回溯起始时刻（nanoTime），用于统计「按下 F8 → 玩家回到世界」的耗时；0 表示当前没有回溯在进行。 */
    private static long restoreStartedNanos;
    /**
     * 快速重启要复用的东西：注册表层 + 数据包资源（配方/战利品/标签/函数编译都在里面）+ 上一轮的 WorldData。
     * 它们在 {@code MinecraftServer.stopServer} 里都不会被关闭，所以跨世界生命周期可复用。
     */
    private static LayeredRegistryAccess<RegistryLayer> reusableRegistries;
    private static ReloadableServerResources reusableResources;
    private static WorldData reusableWorldData;
    /** 是否走快速重启；留个开关给 A/B 测量（默认开）。 */
    private static boolean fastRestartEnabled = true;

    private static volatile boolean workerFinished;
    private static volatile Throwable workerFailure;
    private static volatile String workerSummary = "";
    /** 最近一次回溯的回拷统计：给自测 / 命令看增量还原到底省了多少。 */
    private static int lastRestoreCopied = -1;
    private static int lastRestoreSkipped = -1;
    private static int lastRestoreFiles = -1;

    private static Outcome lastOutcome = Outcome.NONE;
    private static String lastMessage = "";

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

    /** 最近一次回溯真正回拷的文件数（-1 表示还没回溯过）。 */
    public static int lastRestoreCopiedFiles() {
        return lastRestoreCopied;
    }

    /** 最近一次回溯因为「活动存档里还是原样」而跳过的文件数。 */
    public static int lastRestoreSkippedFiles() {
        return lastRestoreSkipped;
    }

    /** 最近一次回溯涉及的槽位文件总数。 */
    public static int lastRestoreFileCount() {
        return lastRestoreFiles;
    }

    /** 切换快速重启路径；自测用它在同一次运行里做 A/B 对比。 */
    public static void setFastRestartEnabled(boolean enabled) {
        fastRestartEnabled = enabled;
    }

    // ------------------------------------------------------------------ F7

    /** F7：建立/覆盖存档点。没有界面，靠「广角过渡 + 冻结服务端线程」完成。 */
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
        // 先让画面开始广角畸变；等淡入到满强度再动手，视觉上就是「世界被拉宽、静止、然后恢复」
        RewindTransition.start(RewindTransition.Effect.WIDE_ANGLE);
    }

    // ------------------------------------------------------------------ F8

    /** F8：回溯到存档点。直接执行，不再要确认屏。 */
    public static void requestRestore(String source) {
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
        phase = Phase.CLOSING_WORLD;
        phaseTicks = 0;
        // 回滚窗口在这一刻就打开：从淡入开始，任何世界落盘都是马上要被覆盖掉的
        Rewind.beginDiscard();
        // 先让画面开始高斯模糊；等淡入到满强度（世界在视觉上已经糊住）再真正关世界
        RewindTransition.start(RewindTransition.Effect.GAUSSIAN_BLUR);
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

        IntegratedServer server = minecraft.getSingleplayerServer();
        worldRoot = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        levelId = String.valueOf(worldRoot.getFileName());
        restoreStartedNanos = System.nanoTime();
        captureReusableResources(server);

        Rewind.LOGGER.info("Rewind: closing world {} to restore slot {}", levelId, slot);
        // 挂一个什么都不画的逻辑屏：接下来的重载期间客户端会短暂没有 ClientLevel，
        // 而原版假设「没有界面就一定有玩家」，这里用它维持那条不变量（画面交给后处理）。
        minecraft.setScreen(new RewindBlankScreen());
        // 从这里到快照写回结束，磁盘上的世界状态都会被覆盖：置位标记让 Mixin 跳过这段期间的落盘
        Rewind.beginDiscard();
        minecraft.level.disconnect();
        // 阻塞直到集成服务器线程结束：MinecraftServer.stopServer → ServerLevel.close → RegionFile.close，
        // 之后 session.lock 与所有 .mca 句柄都已释放，可以安全覆盖文件。
        // 传进去的屏会被 RewindScreens 取消掉（过渡期间不开任何界面），这里只是需要一个非 null 参数。
        minecraft.disconnect(new GenericMessageScreen(SAVING_SCREEN));

        if (minecraft.level != null) {
            fail(minecraft, "rewind.error.close_failed", "");
            return;
        }
        beginRewrite(minecraft);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(Minecraft minecraft) {
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
                // 等广角过渡淡入到位再动手：画面上就是「世界被拉宽之后静止住」
                if (!RewindTransition.isFadeInDone()) {
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
                releaseServer();
                // 快照写完了：放开服务端线程，同时让广角效果淡出
                RewindTransition.finish();
                phase = Phase.IDLE;
                succeed(minecraft, "rewind.msg.snapshot_done",
                        summary + (meta == null ? "" : " | " + describeGameTime(meta.gameTime)));
                return;
            }
            case CLOSING_WORLD: {
                // 等模糊淡入到位（画面已经糊住）再真正关世界，这样关世界的过程玩家看不到
                if (!RewindTransition.isFadeInDone()) {
                    if (phaseTicks > TIMEOUT_PAUSE_TICKS) {
                        fail(minecraft, "rewind.error.pause_timeout", "");
                    }
                    return;
                }
                // 这里会一直阻塞到集成服务器线程结束；不排队到下一帧，避免重复触发
                startRestore(minecraft);
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
            case REVEALING: {
                // 世界已经重建，等它真的画出来了再让模糊淡出：玩家看到的是「糊着的旧画面 → 清晰的新世界」
                if (minecraft.level == null || minecraft.player == null) {
                    if (phaseTicks > TIMEOUT_REWRITE_TICKS) {
                        Rewind.LOGGER.warn("Rewind: new world did not come back, ending the transition anyway");
                        RewindTransition.abort();
                        phase = Phase.IDLE;
                    }
                    return;
                }
                if (phaseTicks < REVEAL_SETTLE_TICKS) {
                    return;
                }
                // 世界真的回来了：摘掉逻辑屏，让模糊淡出去露出新世界
                minecraft.setScreen(null);
                RewindTransition.finish();
                phase = Phase.IDLE;
                return;
            }
            default:
                // REOPENING 由自己的分支直接推进，不需要在这里等
        }
    }

    // ------------------------------------------------------------------ 各阶段实现

    private static void beginFlush(IntegratedServer server) {
        phase = Phase.FLUSHING;
        phaseTicks = 0;
        CompletableFuture<Void> future = new CompletableFuture<>();
        flushFuture = future;
        CountDownLatch release = new CountDownLatch(1);
        releaseServerLatch = release;
        server.execute(() -> {
            try {
                flushed = WorldFlush.flush(server, slot, requestSource);
                future.complete(null);
                // 落盘完成：把服务端线程摁在这里，直到快照写完再放行。
                // 没有界面就没法靠「暂停世界」保证一致性，冻结线程是等价手段。
                if (!release.await(SERVER_FREEZE_SECONDS, TimeUnit.SECONDS)) {
                    Rewind.LOGGER.warn("Rewind: server freeze timed out, resuming the world");
                }
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: flush failed", t);
                future.completeExceptionally(t);
            }
        });
    }

    private static void beginSnapshotCopy() {
        phase = Phase.SNAPSHOTTING;
        phaseTicks = 0;

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
            long copyStartedNanos = System.nanoTime();
            SnapshotMirror.Result result = SnapshotMirror.mirror(
                    world, SnapshotLayout.slotDir(world, slot), SnapshotMirror.Direction.TO_SNAPSHOT, previous, null);
            result.manifest.save(manifestFile);

            SnapshotMeta complete = base.copy();
            complete.status = SnapshotLayout.STATUS_COMPLETE;
            complete.fileCount = result.files.size();
            complete.totalBytes = result.totalBytes;
            index = SnapshotIndex.load(indexFile);
            index.put(complete, slot);
            index.save(indexFile);

            workerSummary = result.summary();
            Rewind.LOGGER.info("Rewind: snapshot {} written ({}) in {} ms", slot, result.summary(),
                    millisSince(copyStartedNanos));
        });
    }

    private static void beginRewrite(Minecraft minecraft) {
        phase = Phase.REWRITING;
        phaseTicks = 0;
        final Path world = worldRoot;
        final Path snapshotDir = SnapshotLayout.slotDir(world, slot);
        startWorker("rewind-restore", () -> {
            try {
                if (!Files.isDirectory(snapshotDir)) {
                    throw new IOException("snapshot directory missing: " + snapshotDir);
                }
                // 用建点时记录的清单判断活动存档里哪些文件还是原样：没动过的不必回拷
                SnapshotManifest reference = SnapshotManifest.load(SnapshotLayout.manifestFile(world, slot));
                long copyStartedNanos = System.nanoTime();
                SnapshotMirror.Result result = SnapshotMirror.mirror(
                        snapshotDir, world, SnapshotMirror.Direction.TO_WORLD, reference, null);
                workerSummary = result.summary() + " copyMs=" + millisSince(copyStartedNanos);
                lastRestoreCopied = result.copied;
                lastRestoreSkipped = result.skipped;
                lastRestoreFiles = result.files.size();
                Rewind.LOGGER.info("Rewind: restored slot {} into {} ({}) in {} ms", slot, world, result.summary(),
                        millisSince(copyStartedNanos));
            } finally {
                // 回滚窗口到此结束：接下来（重新开世界）的落盘都是正常保存，不能再跳过
                Rewind.endDiscard();
            }
        });
    }

    private static void beginReopen(Minecraft minecraft) {
        phase = Phase.REOPENING;
        phaseTicks = 0;
        Rewind.LOGGER.info("Rewind: reopening world {}", levelId);
        long reopenStartedNanos = System.nanoTime();

        if (fastRestartEnabled && tryFastRestart(minecraft, levelId)) {
            Rewind.LOGGER.info("Rewind: reopen phase finished in {} ms (fast restart)",
                    millisSince(reopenStartedNanos));
            phase = Phase.REVEALING;
            phaseTicks = 0;
            succeed(minecraft, "rewind.msg.restored", workerSummary);
            return;
        }

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
        Rewind.LOGGER.info("Rewind: reopen phase finished in {} ms (vanilla path)", millisSince(reopenStartedNanos));
        phase = Phase.REVEALING;
        phaseTicks = 0;
        succeed(minecraft, "rewind.msg.restored", workerSummary);
    }

    /** 关世界之前把可以复用的注册表 / 数据包资源抓下来（{@code Minecraft.disconnect} 之后 server 引用就没了）。 */
    private static void captureReusableResources(IntegratedServer server) {
        try {
            reusableRegistries = server.registries();
            reusableResources = server.getServerResources().managers();
            reusableWorldData = server.getWorldData();
        } catch (Throwable t) {
            Rewind.LOGGER.warn("Rewind: cannot capture reusable world resources; will use the vanilla open path", t);
            reusableRegistries = null;
            reusableResources = null;
            reusableWorldData = null;
        }
    }

    /**
     * 快速重启：跳过 {@code WorldOpenFlows} 的数据包 / 注册表重载阶段。
     *
     * <p>依据：{@code MinecraftServer.stopServer} 只会关掉 resource manager，注册表层
     * （{@code LayeredRegistryAccess}）和 {@code ReloadableServerResources}（配方 / 战利品 / 标签 /
     * 函数编译）都不会被关，因此可以跨两次世界生命周期复用；原版自己的
     * {@code WorldOpenFlows.createLevelFromExistingSettings} 就是这条路（新建世界时用）。
     *
     * <p>我们要额外做的是把 level.dat 重新读一遍并重新 bake DIMENSIONS 层：回溯后 level.dat 是快照里的，
     * 而 {@code Minecraft.doWorldLoad} 会用它把 level.dat 写回去，所以 WorldData 必须是新的那一份，
     * 否则时间/天气/出生点这些不会回滚。
     *
     * @return 是否成功驱动了重启；false 表示调用方应该退回原版 openWorld 路径
     */
    private static boolean tryFastRestart(Minecraft minecraft, String levelId) {
        if (reusableRegistries == null || reusableResources == null || reusableWorldData == null) {
            return false;
        }
        LevelStorageSource.LevelStorageAccess access = null;
        boolean handedOver = false;
        try {
            access = minecraft.getLevelSource().validateAndCreateAccess(levelId);
            Dynamic<?> levelDataTag = access.getDataTag();
            RegistryAccess.Frozen datapackWorldgen = reusableRegistries.getAccessForLoading(RegistryLayer.DIMENSIONS);
            Registry<LevelStem> datapackLevelStems = reusableRegistries.getLayer(RegistryLayer.DIMENSIONS)
                    .registryOrThrow(Registries.LEVEL_STEM);
            LevelDataAndDimensions levelDataAndDimensions = LevelStorageSource.getLevelDataAndDimensions(
                    levelDataTag, reusableWorldData.getDataConfiguration(), datapackLevelStems, datapackWorldgen);
            LayeredRegistryAccess<RegistryLayer> registries = reusableRegistries.replaceFrom(
                    RegistryLayer.DIMENSIONS, levelDataAndDimensions.dimensions().dimensionsRegistryAccess());

            PackRepository packs = ServerPacksSource.createPackRepository(access);
            CloseableResourceManager resourceManager = new WorldLoader.PackConfig(
                    packs, reusableWorldData.getDataConfiguration(), false, false)
                    .createResourceManager().getSecond();
            WorldStem stem = new WorldStem(resourceManager, reusableResources, registries,
                    levelDataAndDimensions.worldData());

            Rewind.LOGGER.info("Rewind: fast reopen, reusing registries and data pack resources");
            handedOver = true;
            minecraft.doWorldLoad(access, packs, stem, false);
            return true;
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: fast reopen failed, falling back to the vanilla open path", t);
            if (access != null && !handedOver) {
                try {
                    access.close();
                } catch (Throwable ignored) {
                    // 关不掉也没关系：原版路径会自己再拿一次锁
                }
            }
            return false;
        }
    }

    private static void startWorker(String name, WorkerBody body) {
        workerFinished = false;
        workerFailure = null;
        workerSummary = "";
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
        if (isBusy() || RewindTransition.isActive()) {
            Rewind.LOGGER.warn("Rewind: another checkpoint operation is still running");
            return false;
        }
        if (!minecraft.hasSingleplayerServer() || minecraft.level == null || minecraft.player == null) {
            Rewind.LOGGER.warn("Rewind: checkpoint operations are only available in-game in a singleplayer world");
            return false;
        }
        // 局域网开放时本地服务端会继续给别的玩家发包，冻结/重开都会影响他们，不做
        if (minecraft.getSingleplayerServer().isPublished()) {
            Rewind.LOGGER.warn("Rewind: refusing to checkpoint while LAN is published");
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
        if (restoreStartedNanos != 0L) {
            Rewind.LOGGER.info("Rewind: rewind completed in {} ms (F8 → 玩家实体回到世界)",
                    millisSince(restoreStartedNanos));
            restoreStartedNanos = 0L;
        }
    }

    private static void fail(Minecraft minecraft, String messageKey, String detailText) {
        lastOutcome = Outcome.FAILED;
        lastMessage = describe(messageKey, detailText);
        Rewind.LOGGER.error("Rewind: {}", lastMessage);
        // 任何失败都意味着回滚窗口结束了：标记必须清掉，否则后续正常游玩的世界保存会被跳过
        Rewind.endDiscard();
        restoreStartedNanos = 0L;
        // 出错时不留过渡效果，也不留被冻结的服务端线程；该出现的提示屏也放行（不再拦截）
        RewindTransition.abort();
        releaseServer();
        if (minecraft.level != null) {
            minecraft.setScreen(null);
        } else {
            // 世界已经关了但回不去：退回标题屏，别把玩家留在空画面上
            minecraft.setScreen(new TitleScreen());
        }
        phase = Phase.IDLE;
    }

    /** 放行被冻结的服务端线程（幂等）。 */
    private static void releaseServer() {
        CountDownLatch latch = releaseServerLatch;
        if (latch != null) {
            releaseServerLatch = null;
            latch.countDown();
        }
    }

    /** 结果文案只用于日志与 {@code /rewind status}，不再往聊天框里发任何东西。 */
    private static String describe(String messageKey, String detailText) {
        return Component.translatable(messageKey).getString()
                + (detailText == null || detailText.isEmpty() ? "" : " (" + detailText + ")");
    }

    /** 耗时统计用：把单调时钟差值换成毫秒。 */
    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String describeGameTime(long gameTime) {
        long days = gameTime / 24000L;
        long timeOfDay = gameTime % 24000L;
        long hours = (timeOfDay / 1000L + 6L) % 24L;
        long minutes = (timeOfDay % 1000L) * 60L / 1000L;
        return String.format("day=%d %02d:%02d", days, hours, minutes);
    }
}
