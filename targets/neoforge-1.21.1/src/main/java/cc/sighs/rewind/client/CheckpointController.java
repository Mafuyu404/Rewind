package cc.sighs.rewind.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import com.mojang.serialization.Dynamic;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.server.RewindServerConfig;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;

/**
 * 存档点操作的客户端策略层：热键 / 命令 → 过渡 → {@link RewindApi} → 状态机收尾。
 *
 * <p>真正的存档 / 读档机制在 {@link RewindApi}（同步入口）和
 * {@code cc.sighs.rewind.server.CheckpointWriter} / {@code InPlaceRollback}（引擎）里，
 * 本类只负责「怎么让玩家看到这件事发生」：过渡包络、界面收放、失败回退、以及把结果记下来给命令和自测看。
 *
 * <p>F7：广角过渡淡入 → 服务端线程上 {@link RewindApi#createCheckpoint} → 过渡淡出。
 *
 * <p>F8：高斯模糊淡入 → 服务端线程上 {@link RewindApi#rollbackInPlace}（世界不关、客户端不重登）
 * → 过渡淡出。原地回滚失败时自动退回下面这条已知能成的老路。
 *
 * <p>F8（回退路径）：走原版「保存并退出」同一条路（{@code Minecraft.disconnect}），返回时
 * 服务器线程已结束、{@code session.lock} 已释放、所有 region 文件句柄已关闭，
 * 此时才能安全覆盖 .mca；覆盖完走快速重启重新进世界。
 *
 * <p>除了 disconnect 自身（它的忙等由原版负责），客户端线程不会被阻塞。
 */
public final class CheckpointController {
    public enum Phase {
        IDLE,
        /** 在死亡界面里回溯：已经请求原版重生，等客户端拿到新的 LocalPlayer 再开始。 */
        AWAITING_RESPAWN,
        /** 过渡淡入完成之后，在服务端线程上跑 {@link RewindApi} 的同步入口，客户端这边轮询。 */
        WORKING,
        /** F8（回退路径）：等过渡淡入完成，然后关世界。 */
        CLOSING_WORLD,
        REWRITING,
        REOPENING,
        /** 新世界已经回来（或原地回滚完成），等第一帧画出来再让过渡淡出。 */
        REVEALING
    }

    /** 供外部（命令、自测）观察的结果。 */
    public enum Outcome {
        NONE,
        SUCCESS,
        FAILED
    }

    /** 本轮在服务端线程上跑哪个同步入口。 */
    private enum Work {
        NONE,
        CHECKPOINT,
        ROLLBACK
    }

    private static final int TIMEOUT_PAUSE_TICKS = 200;
    private static final int TIMEOUT_REWRITE_TICKS = 24000;
    /** 等原版重生回来的上限（tick）；死亡界面上的回溯用它兜底，超时按失败处理。 */
    private static final int TIMEOUT_RESPAWN_TICKS = 200;
    /** 服务端线程上的活干得异常久时，每隔这么多 tick 记一条警告（不硬超时）。 */
    private static final int WORK_WARN_INTERVAL_TICKS = 6000;

    /**
     * 回退路径关世界期间用的提示屏：用原版自己的 {@link GenericMessageScreen}（原版「保存并退出」用的就是它），
     * 不自己造界面。它还会被 {@code RewindScreens} 拦掉——过渡期间不开任何界面。
     */
    private static final Component SAVING_SCREEN = Component.translatable("menu.savingLevel");

    private static Phase phase = Phase.IDLE;
    private static String requestSource = "";
    private static int phaseTicks;
    /** 「世界已经回来」之后等区块到位的判据：已加载区块数连续这么多 tick 没再增长就算到位。 */
    private static final int REVEAL_STABLE_TICKS = 2;
    private static int revealLoadedChunks;
    private static int revealStableTicks;
    /** 本轮操作针对的槽位。热键用 {@link Rewind#SLOT}，界面上的读取/覆盖按玩家选的槽位覆盖它。 */
    private static String slot = Rewind.SLOT;
    /** 「死亡后自动回溯」是否已经为这一次死亡触发过（玩家活着时重新武装）。 */
    private static boolean deathRollbackFired;

    /** 本轮回溯用哪种过渡效果；死亡回溯走 {@code DEATH}（模糊 + 视野红边）。 */
    private static RewindTransition.Effect restoreEffect = RewindTransition.Effect.GAUSSIAN_BLUR;

    /** 死亡那一刻的朝向；原版重生把俯仰角强制归零，回溯开始前要补回客户端。 */
    private static float deathYaw;
    private static float deathPitch;

    /** 本轮要跑的活 + 服务端线程回填的结果。 */
    private static Work work = Work.NONE;
    /** 本轮是否已经把活提交给服务端线程（只在客户端线程读写，避免同一轮反复提交）。 */
    private static boolean workStarted;
    private static volatile boolean workDone;
    private static volatile RewindResult workResult;
    private static volatile Throwable workFailure;

    private static Path worldRoot;
    private static String levelId = "";
    /** 回退路径的耗时起点（nanoTime）；0 表示当前没有回退在进行。 */
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
    /** 是否走原地回滚；留个开关给 A/B 测量（默认开）。关掉就退回「关世界 → 重开」那条路。 */
    private static boolean inPlaceEnabled = true;

    /** 回退路径里在后台线程上做文件回拷。 */
    private static volatile boolean workerFinished;
    private static volatile Throwable workerFailure;
    private static volatile String workerSummary = "";

    /** 最近一次回溯是不是原地回滚、整段耗时、回拷统计。 */
    private static boolean lastRestoreInPlace;
    private static long lastRestoreMillis = -1L;
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

    /** 最近一次操作的结果（同步入口自己记，异步入口完成后回填）；还没做过任何操作时为 null。 */
    public static RewindResult lastResult() {
        return RewindApi.lastResult();
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

    /** 切换原地回滚；自测用它在同一次运行里做 A/B 对比。 */
    public static void setInPlaceEnabled(boolean enabled) {
        inPlaceEnabled = enabled;
    }

    /** 最近一次回溯走的是不是原地回滚。 */
    public static boolean lastRestoreInPlace() {
        return lastRestoreInPlace;
    }

    /** 最近一次回溯主体（不含过渡淡入）的耗时（毫秒，-1 表示还没回溯过）。 */
    public static long lastRestoreMillis() {
        return lastRestoreMillis;
    }

    /**
     * 把本状态机接到 {@link RewindApi} 的异步入口上——{@code RewindApi.requestCheckpoint/requestRollback}
     * 等价于按 F7 / F8。客户端 setup 时调用一次。
     */
    public static void installApiBridge() {
        RewindApi.installClientBridge(new RewindApi.ClientBridge() {
            @Override
            public void requestCheckpoint(String targetSlot, String source) {
                requestSnapshot(targetSlot, source);
            }

            @Override
            public void requestRollback(String targetSlot, String source) {
                requestRestore(targetSlot, source);
            }

            @Override
            public boolean isBusy() {
                return CheckpointController.isBusy();
            }
        });
    }

    // ------------------------------------------------------------------ F7

    /** F7：建立/覆盖 {@link Rewind#SLOT} 的存档点。 */
    public static void requestSnapshot(String source) {
        requestSnapshot(Rewind.SLOT, source);
    }

    /**
     * 建立/覆盖指定槽位的存档点。没有界面，靠「广角过渡 + 服务端线程上同步落盘」完成。
     *
     * <p>界面上的「覆盖」也走这里——它会先把界面摘掉，因为过渡是整帧后处理，界面开着会挡在效果上面。
     */
    public static void requestSnapshot(String targetSlot, String source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ensureUsable(minecraft)) {
            return;
        }
        if (!SnapshotLayout.isValidSlotName(targetSlot)) {
            Rewind.LOGGER.warn("Rewind: refusing to write an invalid slot name: {}", targetSlot);
            return;
        }
        slot = targetSlot;
        closeScreen(minecraft);
        requestSource = source;
        lastOutcome = Outcome.NONE;
        lastMessage = "";
        startWork(Work.CHECKPOINT);
        // 先让画面开始畸变；等淡入到满强度再动手，视觉上就是「世界被拉宽、静止、然后恢复」
        RewindTransition.start(RewindTransition.Effect.SATURATION);
    }

    // ------------------------------------------------------------------ F8

    /** F8：回溯到 {@link Rewind#SLOT} 的存档点。直接执行，不再要确认屏。 */
    public static void requestRestore(String source) {
        requestRestore(Rewind.SLOT, source);
    }

    /** 回溯到指定槽位的存档点；界面上的「读取」走这条。 */
    public static void requestRestore(String targetSlot, String source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ensureUsable(minecraft)) {
            return;
        }
        if (!SnapshotLayout.isValidSlotName(targetSlot)) {
            Rewind.LOGGER.warn("Rewind: refusing to read an invalid slot name: {}", targetSlot);
            return;
        }
        slot = targetSlot;
        Path world = RewindApi.worldRoot(minecraft.getSingleplayerServer());
        long cooldown = RewindApi.rollbackCooldownRemainingMillis(world);
        if (cooldown > 0L) {
            // 冷却中：不动玩家当前的界面，只写一条日志（和「没有存档点」一样，不是失败路径）
            lastOutcome = Outcome.FAILED;
            lastMessage = describe("rewind.error.rollback_cooldown", rollbackCooldownText(cooldown));
            Rewind.LOGGER.warn("Rewind: {}", lastMessage);
            return;
        }
        if (!RewindApi.hasCheckpoint(world, slot)) {
            // 没有存档点不是「操作失败」，只是一个空动作：不动玩家当前的界面
            lastOutcome = Outcome.FAILED;
            lastMessage = describe("rewind.error.no_snapshot", String.valueOf(world));
            Rewind.LOGGER.warn("Rewind: {}", lastMessage);
            return;
        }
        requestSource = source;
        // 死亡界面上的回溯要特殊处理：客户端在死亡满 1 秒后会把 LocalPlayer 从客户端世界里移除
        // （ClientLevel.shouldTickDeath → LocalPlayer.tickDeath），而回溯只把「服务端那个玩家」救活，
        // 客户端手里那个已经移除的实体找不回来——结果就是一个不能动、不能交互、没有手也没有 HUD 的
        // 幽灵玩家。所以先走一次原版重生（等价于点死亡界面的「重生」），等新的 LocalPlayer 到位再回溯；
        // 重生点会先挪到玩家现在站的地方（见 moveRespawnPointHere），位置 / 物品 / 血量随后由快照盖回来。
        if (minecraft.player != null && minecraft.player.isDeadOrDying()) {
            // 重生会把人送到重生点，所以先把重生点挪到玩家现在站的地方：不然会先瞬移回出生点、
            // 再被回溯拉走，中间那一下场景变化很违和。这个临时重生点随后会被快照里的 playerdata 盖回去。
            deathYaw = minecraft.player.getYRot();
            deathPitch = minecraft.player.getXRot();
            moveRespawnPointHere(minecraft);
            Rewind.LOGGER.info("Rewind: player is dead, respawning before the rollback");
            minecraft.player.respawn();
            lastOutcome = Outcome.NONE;
            lastMessage = "";
            restoreEffect = RewindTransition.Effect.DEATH;
            phase = Phase.AWAITING_RESPAWN;
            phaseTicks = 0;
            return;
        }
        restoreEffect = RewindTransition.Effect.GAUSSIAN_BLUR;
        beginRestore(minecraft);
    }

    /** 真正开始回溯：摘屏、起过渡，然后等服务端线程上的 {@link RewindApi#rollbackInPlace}（或退回关世界重开）。 */
    private static void beginRestore(Minecraft minecraft) {
        closeScreen(minecraft);
        lastOutcome = Outcome.NONE;
        lastMessage = "";
        lastRestoreInPlace = false;
        lastRestoreMillis = -1L;
        lastRestoreCopied = -1;
        lastRestoreSkipped = -1;
        lastRestoreFiles = -1;
        if (inPlaceEnabled) {
            startWork(Work.ROLLBACK);
        } else {
            startWork(Work.NONE);
            phase = Phase.CLOSING_WORLD;
        }
        // 回滚窗口在这一刻就打开：从淡入开始，任何世界落盘都是马上要被覆盖掉的
        Rewind.beginDiscard();
        // 先让画面开始高斯模糊；等淡入到满强度（世界在视觉上已经糊住）再真正回滚
        RewindTransition.start(restoreEffect);
    }

    /**
     * 把玩家的重生点临时挪到他现在站的位置，好让紧接着的原版重生把人留在原地。
     *
     * <p>客户端与集成服务器在同一个进程里，所以直接把这件事排到服务端线程上（和 {@code submitWork} 一样），
     * 不需要额外的网络包；先排任务、再发重生请求，服务端那边这个任务一定先跑。{@code forced = true}：
     * 死亡地点多半不是床 / 重生锚，不强制的话原版会忽略这个重生点、退回世界出生点。
     */
    private static void moveRespawnPointHere(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        LocalPlayer player = minecraft.player;
        if (server == null || player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        ResourceKey<Level> dimension = player.level().dimension();
        BlockPos pos = player.blockPosition();
        float yRot = player.getYRot();
        float xRot = player.getXRot();
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(uuid);
            if (serverPlayer != null) {
                serverPlayer.setRespawnPosition(dimension, pos, yRot, true, false);
            }
        });
    }

    /**
     * 把死亡那一刻的朝向写回客户端。原版重生会把俯仰角强制归零（{@code PlayerList.respawn} 里的
     * {@code moveTo(..., yaw, 0)}），只挪重生点是不够的——不补的话一复活就是「直视前方」。
     * 连插值用的 {@code O} 值一起写，免得第一帧从 0 插值过去。
     */
    private static void restoreViewDirection(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        player.setYRot(deathYaw);
        player.setXRot(deathPitch);
        player.yRotO = deathYaw;
        player.xRotO = deathPitch;
    }

    /**
     * 「死亡后自动回溯」：只在「活着 → 死」的那一下触发一次，目标是时间线的头
     * （{@link RewindApi#currentSlot}，也就是世界当前站着的那个存档点）。
     *
     * <p>只有单人、且没在建点 / 回溯途中才会动手（{@link #ensureUsable}）；没有可回溯的节点时只写一条日志。
     * 死亡时客户端可能已经进了死亡流程，真正的「先重生再回滚」由 {@link #requestRestore} 负责。
     */
    private static void tickDeathRollback(Minecraft minecraft) {
        if (minecraft.player == null || !minecraft.player.isDeadOrDying()) {
            // 活着就重新武装：下一次死亡还会再触发
            deathRollbackFired = false;
            return;
        }
        if (deathRollbackFired || !RewindServerConfig.rollbackOnDeath()) {
            return;
        }
        deathRollbackFired = true;
        if (!ensureUsable(minecraft)) {
            return;
        }
        Path world = RewindApi.worldRoot(minecraft.getSingleplayerServer());
        String target = RewindApi.currentSlot(world);
        if (target.isEmpty()) {
            Rewind.LOGGER.warn("Rewind: player died but the timeline has no node to roll back to");
            return;
        }
        Rewind.LOGGER.info("Rewind: player died, rolling back to the timeline head {}", target);
        requestRestore(target, "death");
    }

    // ------------------------------------------------------------------ tick

    public static void tick(Minecraft minecraft) {
        if (phase == Phase.IDLE) {
            tickDeathRollback(minecraft);
            return;
        }
        phaseTicks++;

        switch (phase) {
            case AWAITING_RESPAWN: {
                // 死亡界面上的回溯：已经请求原版重生，等客户端真的活过来（新的 LocalPlayer 到位、
                // 死亡界面被 handleRespawn 关掉）再开始。这里不碰过渡——那几帧交给原版重生自己的画面。
                if (minecraft.player != null && !minecraft.player.isRemoved() && !minecraft.player.isDeadOrDying()) {
                    restoreViewDirection(minecraft);
                    beginRestore(minecraft);
                    return;
                }
                if (phaseTicks > TIMEOUT_RESPAWN_TICKS) {
                    fail(minecraft, "rewind.error.respawn_timeout", "");
                }
                return;
            }
            case WORKING: {
                // 等过渡淡入到位再动手，这样真正危险的动作玩家看不到
                if (!RewindTransition.isFadeInDone()) {
                    if (phaseTicks > TIMEOUT_PAUSE_TICKS) {
                        fail(minecraft, "rewind.error.pause_timeout", "");
                    }
                    return;
                }
                if (!workStarted) {
                    submitWork(minecraft);
                    return;
                }
                if (!workDone) {
                    // 服务端线程还在干活。这里只能等：它正在改世界，任何并发的关世界/重开都会撞上它。
                    // 视觉上的兜底由 RewindTransition 自己的超时守卫负责。
                    if (phaseTicks % WORK_WARN_INTERVAL_TICKS == 0) {
                        Rewind.LOGGER.warn("Rewind: {} is taking unusually long ({} ticks)", work, phaseTicks);
                    }
                    return;
                }
                if (work == Work.CHECKPOINT) {
                    finishCheckpoint(minecraft);
                    return;
                }
                finishRollback(minecraft);
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
                // 等区块到位再淡出：判据是「客户端已加载的区块数不再增长」（连续两 tick 没涨）。
                // 原来这里是固定等 restoreSettleTicks（默认 10 tick = 0.5 秒），而这段时间玩家其实已经
                // 能看见世界、手里的东西也回来了，多糊的每一 tick 都是白等；现在配置里的那个值改成
                // 上限，正常情况下 3-4 tick 就收。
                //
                // 注意上限是从**进入这个阶段**算起的（含世界还没回来的那些 tick）。关世界重开那条
                // 回退路径上，客户端拿到世界时 phaseTicks 往往已经超过上限了，于是「世界一出现就收」——
                // 这和改动前一样（改动前那 10 tick 也是被这些空 tick 吃掉的），所以那条路的行为没变。
                if (minecraft.level.getChunkSource().getLoadedChunksCount() > revealLoadedChunks) {
                    revealLoadedChunks = minecraft.level.getChunkSource().getLoadedChunksCount();
                    revealStableTicks = 0;
                } else {
                    revealStableTicks++;
                }
                if (revealStableTicks < REVEAL_STABLE_TICKS
                        && phaseTicks < RewindClientConfig.restoreSettleTicks()) {
                    return;
                }
                Rewind.LOGGER.info("Rewind: reveal settled after {} ticks (chunks={}, stable={}, cap={})",
                        phaseTicks, revealLoadedChunks, revealStableTicks,
                        RewindClientConfig.restoreSettleTicks());
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

    // ------------------------------------------------------------------ 服务端线程上的活

    private static void startWork(Work kind) {
        work = kind;
        workStarted = false;
        workDone = false;
        workResult = null;
        workFailure = null;
        phase = Phase.WORKING;
        phaseTicks = 0;
    }

    /**
     * 把活提交给服务端线程。客户端这边继续出帧（过渡还盖着），只轮询 {@code workDone}。
     *
     * <p>没界面就没法靠「暂停世界」保证拷贝期间没人写盘；让服务端线程忙在落盘和拷贝上等价于把它冻结，
     * 这也是 {@link RewindApi} 那两个同步入口要求「必须在服务端线程上调用」的原因。
     */
    private static void submitWork(Minecraft minecraft) {
        workStarted = true;
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.level == null) {
            workFailure = new IllegalStateException("integrated server is gone");
            workDone = true;
            return;
        }
        worldRoot = RewindApi.worldRoot(server);
        levelId = String.valueOf(worldRoot.getFileName());
        String source = requestSource;
        Rewind.LOGGER.info("Rewind: {} starting on {} for slot {}", work, levelId, slot);
        server.execute(() -> {
            try {
                workResult = work == Work.CHECKPOINT
                        ? RewindApi.createCheckpoint(server, slot, source)
                        : RewindApi.rollbackInPlace(server, slot);
            } catch (Throwable t) {
                workFailure = t;
                Rewind.LOGGER.error("Rewind: {} threw", work, t);
            } finally {
                workDone = true;
            }
        });
    }

    private static void finishCheckpoint(Minecraft minecraft) {
        if (workFailure != null) {
            fail(minecraft, "rewind.error.copy_failed", workFailure.toString());
            return;
        }
        if (workResult == null || !workResult.success) {
            fail(minecraft, "rewind.error.copy_failed", workResult == null ? "no result" : String.valueOf(workResult.failure));
            return;
        }
        // 存档点写完了：让广角效果淡出
        RewindTransition.finish();
        phase = Phase.IDLE;
        RewindResult result = workResult;
        succeed("rewind.msg.snapshot_done",
                result.summary + (result.meta == null ? "" : " | " + describeGameTime(result.meta.gameTime)));
    }

    private static void finishRollback(Minecraft minecraft) {
        if (workFailure != null || workResult == null || !workResult.success) {
            // 原地回滚没做成：退回「关世界 → 覆盖 → 重开」那条已知能成的路
            Rewind.LOGGER.warn("Rewind: in-place rollback failed, falling back to close-and-reopen",
                    workFailure != null ? workFailure : workResult == null ? null : workResult.failure);
            workResult = null;
            workFailure = null;
            startWork(Work.NONE);
            phase = Phase.CLOSING_WORLD;
            return;
        }
        lastRestoreInPlace = true;
        lastRestoreMillis = workResult.millis;
        lastRestoreCopied = workResult.copiedFiles;
        lastRestoreSkipped = workResult.skippedFiles;
        lastRestoreFiles = workResult.totalFiles;
        beginReveal();
        succeed("rewind.msg.restored", workResult.summary);
    }

    /** 进入「等世界回来」阶段：把「区块到位」的判据清零。 */
    private static void beginReveal() {
        phase = Phase.REVEALING;
        phaseTicks = 0;
        revealLoadedChunks = 0;
        revealStableTicks = 0;
    }

    // ------------------------------------------------------------------ 回退路径：关世界 → 覆盖 → 重开

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
        worldRoot = RewindApi.worldRoot(server);
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
        beginRewrite();
    }

    private static void beginRewrite() {
        phase = Phase.REWRITING;
        phaseTicks = 0;
        final Path world = worldRoot;
        startWorker("rewind-restore", () -> {
            try {
                RewindResult result = RewindApi.restoreFiles(world, slot);
                workerSummary = result.summary;
                lastRestoreCopied = result.copiedFiles;
                lastRestoreSkipped = result.skippedFiles;
                lastRestoreFiles = result.totalFiles;
                if (!result.success) {
                    throw new IOException(String.valueOf(result.failure));
                }
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
            finishReopen(minecraft);
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
        finishReopen(minecraft);
    }

    private static void finishReopen(Minecraft minecraft) {
        lastRestoreInPlace = false;
        lastRestoreMillis = restoreStartedNanos == 0L ? -1L : millisSince(restoreStartedNanos);
        restoreStartedNanos = 0L;
        beginReveal();
        succeed("rewind.msg.restored", workerSummary);
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

    /**
     * 动手之前先把界面摘掉：过渡是整帧后处理（重采样整幅画面），界面开着既会挡在效果上面，
     * 也违反「过渡期间不得出现界面」那条约定。F7/F8 因此可以在任何界面里按——
     * 按下去界面会被收起，然后就是纯粹的世界过渡。
     *
     * <p><b>容器界面要走原版关屏路径</b>：直接 {@code setScreen(null)} 只走 {@code removed()}，
     * 而给服务端发「关容器」包的是 {@code onClose()}。不发包的话服务端那份菜单会一直开着，里面的东西
     * （附魔台输入槽、工作台合成格、光标上的物品）留在原处，等以后真正关掉时才被 {@code clearContainer}
     * 塞回物品栏——「回溯之后凭空多出东西」就是这么来的。所以有容器菜单开着时改用
     * {@code player.closeContainer()}，让服务端先收尾（那些物品随后会被 player.load 一起覆盖掉）。
     */
    private static void closeScreen(Minecraft minecraft) {
        if (minecraft.screen == null) {
            return;
        }
        if (minecraft.player != null && minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
            minecraft.player.closeContainer();
            return;
        }
        minecraft.setScreen(null);
    }

    private static void succeed(String messageKey, String detailText) {
        lastOutcome = Outcome.SUCCESS;
        lastMessage = describe(messageKey, detailText);
        Rewind.LOGGER.info("Rewind: {}", lastMessage);
    }

    private static void fail(Minecraft minecraft, String messageKey, String detailText) {
        lastOutcome = Outcome.FAILED;
        lastMessage = describe(messageKey, detailText);
        Rewind.LOGGER.error("Rewind: {}", lastMessage);
        // 任何失败都意味着回滚窗口结束了：标记必须清掉，否则后续正常游玩的世界保存会被跳过
        Rewind.endDiscard();
        restoreStartedNanos = 0L;
        // 出错时不留过渡效果，也不留没跑完的活；该出现的提示屏也放行（不再拦截）
        RewindTransition.abort();
        work = Work.NONE;
        workStarted = false;
        workDone = true;
        if (minecraft.level != null) {
            minecraft.setScreen(null);
        } else {
            // 世界已经关了但回不去：退回标题屏，别把玩家留在空画面上
            minecraft.setScreen(new TitleScreen());
        }
        phase = Phase.IDLE;
    }

    /** 结果文案只用于日志与 {@code /rewind status}，不再往聊天框里发任何东西。 */
    private static String describe(String messageKey, String detailText) {
        return Component.translatable(messageKey).getString()
                + (detailText == null || detailText.isEmpty() ? "" : " (" + detailText + ")");
    }

    /** 冷却剩余时间给日志看：{@code 4m12s} / {@code 42s}。 */
    private static String rollbackCooldownText(long millis) {
        long totalSeconds = (millis + 999L) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes > 0L ? minutes + "m" + seconds + "s" : seconds + "s";
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
