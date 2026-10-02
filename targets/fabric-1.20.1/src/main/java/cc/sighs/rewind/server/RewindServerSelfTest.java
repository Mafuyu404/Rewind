package cc.sighs.rewind.server;

import java.util.ArrayList;
import java.util.List;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ServerLevelData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * 专用服务器上的端到端自测：建点 → 改世界 → 原地回滚 → 校验。
 *
 * <p><b>为什么需要它</b>：{@link RewindApi} 的同步入口（{@code createCheckpoint} /
 * {@code rollbackInPlace} / {@code restoreFiles}）明确支持专用服务器，但专用服务器上没有任何东西
 * 会去调它们——热键、时间树、{@code /rewind} 命令都挂在客户端，客户端侧的自测要单人世界，
 * 「跟着原版自动保存建点」也显式跳过专用服务器。所以这条路径只有这里能测。
 *
 * <p><b>怎么用</b>：JVM 属性 {@code -Drewind.servertest=true}（构建脚本里对应
 * {@code -PrwSelfTest=true}）。跑完自己 {@code halt} 服务器，结论看
 * {@code REWIND_SERVERTEST PASS} / {@code REWIND_SERVERTEST FAIL: ...}。
 *
 * <p>没有玩家时区块根本不会加载，原地回滚就没有东西可回，所以第一步先 {@code forceload} 一片区块，
 * 探针方块放在那片里。
 *
 * <p><b>与 NeoForge 1.21.1 的差异</b>：只有事件接线。主版本监听 {@code NeoForge.EVENT_BUS} 上的
 * {@code ServerStartedEvent} / {@code ServerTickEvent.Post}；Fabric 换成 Fabric API 的
 * {@link ServerLifecycleEvents#SERVER_STARTED} 与 {@link ServerTickEvents#END_SERVER_TICK}
 * （后者就是「这一 tick 服务端活都干完之后」，对应主版本的 {@code ServerTickEvent.Post}）。
 * 断言与阶段机逐行照搬。
 *
 * <p><b>原地回滚的门控</b>：回滚那一步先看
 * {@code FabricRewindPlatform.supportsInPlaceRollback()}——本 target 已经移植了
 * {@code InPlaceRollback}，所以返回 true，走的就是主版本那条「原地回滚 + 验内存状态」的完整路径。
 * 万一平台退化成 false，那一步降级成 {@link RewindApi#restoreFiles}（纯文件还原），内存里的世界
 * 不会跟着变，于是 VERIFY 只查文件层面的结果，探针方块 / 天气 / 世界时间那几条内存断言会明确记成
 * SKIPPED 而不是假装通过。移植状态变了不用改自测。
 */
public final class RewindServerSelfTest {
    private static final String SLOT = SnapshotLayout.SLOT_QUICK;
    private static final String SECOND_SLOT = "s1";
    /** 探针方块：先放金块再建点，回滚之后必须是金块。 */
    private static final BlockPos PROBE = new BlockPos(8, 100, 8);
    /** 每两个阶段之间隔几个 tick，让区块落盘 / ticket 稳定下来。 */
    private static final int STAGE_TICKS = 5;

    private enum Stage {
        WAITING, SETUP, SNAPSHOT, MUTATE, ROLLBACK, VERIFY, SECOND, DONE
    }

    private static final List<String> failures = new ArrayList<>();
    private static MinecraftServer server;
    private static Stage stage = Stage.WAITING;
    private static int stageTicks;

    private static boolean baselineRaining;
    private static RewindResult checkpoint;
    /** 回滚那一步实际走的是哪条路：能不能验内存状态全看它。 */
    private static boolean inPlace;
    private static RewindResult rollbackResult;

    private RewindServerSelfTest() {
    }

    public static void install() {
        ServerLifecycleEvents.SERVER_STARTED.register(RewindServerSelfTest::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(RewindServerSelfTest::onServerTick);
    }

    private static void onServerStarted(MinecraftServer startedServer) {
        server = startedServer;
        Rewind.LOGGER.info("Rewind server self-test: server started, world={}", RewindApi.worldRoot(server));
        stage = Stage.SETUP;
        stageTicks = 0;
    }

    private static void onServerTick(MinecraftServer tickingServer) {
        if (server == null || stage == Stage.DONE) {
            return;
        }
        if (++stageTicks < STAGE_TICKS) {
            return;
        }
        stageTicks = 0;
        try {
            switch (stage) {
                case SETUP:
                    setup();
                    break;
                case SNAPSHOT:
                    snapshot();
                    break;
                case MUTATE:
                    mutate();
                    break;
                case ROLLBACK:
                    rollback();
                    break;
                case VERIFY:
                    verify();
                    break;
                case SECOND:
                    secondCheckpoint();
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind server self-test: stage {} threw", stage, t);
            check(false, "stage " + stage + " threw: " + t);
            finish();
        }
    }

    private static void setup() {
        ServerLevel level = server.overworld();
        // 专用服务器上没有玩家，区块不会自己加载：原地回滚要动区块，先强制加载一片
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "forceload add -16 -16 31 31");
        baselineRaining = raining(level);
        // 把「存档点那一刻应该是什么样」摆好：探针位置金块、天气反过来
        level.setBlockAndUpdate(PROBE, Blocks.GOLD_BLOCK.defaultBlockState());
        level.setWeatherParameters(0, 12000, !baselineRaining, !baselineRaining);
        Rewind.LOGGER.info("Rewind server self-test: baseline raining={} -> checkpoint state raining={}",
                baselineRaining, !baselineRaining);
        stage = Stage.SNAPSHOT;
    }

    /**
     * 天气读的是 level.dat 里的原始标志。
     *
     * <p>不能用 {@code ServerLevel.isRaining()}：它看的是**渐变中**的雨量等级，刚设完
     * {@code setWeatherParameters} 时还没涨上去 / 还没落下来，读数会跟真实标志不一致。
     */
    private static boolean raining(ServerLevel level) {
        return level.getLevelData().isRaining();
    }

    private static void snapshot() {
        checkpoint = RewindApi.createCheckpoint(server, SLOT, "servertest");
        check(checkpoint.success, "createCheckpoint failed: " + checkpoint.failure);
        check(RewindApi.hasCheckpoint(RewindApi.worldRoot(server), SLOT),
                "hasCheckpoint should be true right after createCheckpoint");
        if (checkpoint.meta != null) {
            check(checkpoint.meta.isComplete(), "the checkpoint should be marked complete");
            Rewind.LOGGER.info("Rewind server self-test: checkpoint written in {} ms ({}) files={} total={}",
                    checkpoint.millis, checkpoint.summary, checkpoint.meta.fileCount, checkpoint.meta.totalBytes);
        }
        stage = Stage.MUTATE;
    }

    private static void mutate() {
        ServerLevel level = server.overworld();
        level.setBlockAndUpdate(PROBE, Blocks.DIAMOND_BLOCK.defaultBlockState());
        if (level.getLevelData() instanceof ServerLevelData levelData) {
            levelData.setDayTime(levelData.getDayTime() + 6000L);
        }
        level.setWeatherParameters(0, 12000, baselineRaining, baselineRaining);
        check(level.getBlockState(PROBE).is(Blocks.DIAMOND_BLOCK), "the mutation did not take effect");
        Rewind.LOGGER.info("Rewind server self-test: world mutated (probe=diamond_block, dayTime+6000, raining={})",
                raining(level));
        stage = Stage.ROLLBACK;
    }

    private static void rollback() {
        if (inPlaceSupported()) {
            rollbackResult = RewindApi.rollbackInPlace(server, SLOT);
            inPlace = true;
            check(rollbackResult.success, "rollbackInPlace failed: " + rollbackResult.failure);
            Rewind.LOGGER.info("Rewind server self-test: in-place rollback in {} ms inPlace={} ({})",
                    rollbackResult.millis, rollbackResult.inPlace, rollbackResult.summary);
        } else {
            // 平台没有原地回滚：退到「纯文件还原」。世界不关、内存也不跟着变，
            // 所以下面 VERIFY 只查文件层面的结果，那几条内存断言会明确记成 skipped。
            rollbackResult = RewindApi.restoreFiles(RewindApi.worldRoot(server), SLOT);
            inPlace = false;
            check(rollbackResult.success, "restoreFiles failed: " + rollbackResult.failure);
            Rewind.LOGGER.warn("Rewind server self-test: in-place rollback is not supported on this platform;"
                    + " falling back to restoreFiles in {} ms ({}) — the live world keeps the mutation,"
                    + " so the in-memory checks will be skipped",
                    rollbackResult.millis, rollbackResult.summary);
        }
        stage = Stage.VERIFY;
    }

    private static void verify() {
        ServerLevel level = server.overworld();
        if (inPlace) {
            check(level.getBlockState(PROBE).is(Blocks.GOLD_BLOCK),
                    "the probe block should be back to gold_block, got " + level.getBlockState(PROBE));
            check(raining(level) == !baselineRaining,
                    "the weather should be the checkpoint's (raining=" + !baselineRaining + "), got " + raining(level));
            if (checkpoint != null && checkpoint.meta != null) {
                long delta = Math.abs(level.getGameTime() - checkpoint.meta.gameTime);
                check(delta <= 200L,
                        "the game time should be back to the checkpoint's " + checkpoint.meta.gameTime
                                + ", got " + level.getGameTime());
            }
            Rewind.LOGGER.info("Rewind server self-test: world verified after the rollback (probe={}, raining={})",
                    level.getBlockState(PROBE).getBlock(), raining(level));
        } else {
            // 只有文件被换过：探针方块 / 天气 / 时间都还在内存里，断言它们等于骗自己。这里能查的是
            // 「反向增量真的拷了东西、文件集合对得上」，内存那一层等原地回滚可用时再验。
            check(!rollbackResult.inPlace, "restoreFiles should report a file-level restore");
            check(rollbackResult.totalFiles > 0,
                    "the restore should have walked the checkpoint's files, got total=" + rollbackResult.totalFiles);
            check(rollbackResult.copiedFiles >= 0 && rollbackResult.skippedFiles >= 0
                            && rollbackResult.copiedFiles + rollbackResult.skippedFiles == rollbackResult.totalFiles,
                    "copied + skipped should add up to the file count, got copied=" + rollbackResult.copiedFiles
                            + " skipped=" + rollbackResult.skippedFiles + " total=" + rollbackResult.totalFiles);
            Rewind.LOGGER.warn("Rewind server self-test: SKIPPED the in-memory checks"
                    + " (probe block / weather / game time): the platform has no in-place rollback,"
                    + " so the live world still holds the mutation. Files restored: copied={} skipped={} total={}",
                    rollbackResult.copiedFiles, rollbackResult.skippedFiles, rollbackResult.totalFiles);
        }
        stage = Stage.SECOND;
    }

    /** 当前平台有没有原地回滚（这个 target 有，见类注释）。 */
    private static boolean inPlaceSupported() {
        RewindPlatform platform = RewindPlatforms.get();
        return platform != null && platform.supportsInPlaceRollback();
    }

    /**
     * 再往另一个槽位建一次点，用来量「按 4 KiB 块共享」到底省了多少：
     * {@code bytes=} 是这次真正写盘的字节数，{@code total=} 是快照总大小。
     */
    private static void secondCheckpoint() {
        RewindResult second = RewindApi.createCheckpoint(server, SECOND_SLOT, "servertest2");
        check(second.success, "the second createCheckpoint failed: " + second.failure);
        if (second.meta != null) {
            Rewind.LOGGER.info("Rewind server self-test: second checkpoint into {} in {} ms ({}) total={}",
                    SECOND_SLOT, second.millis, second.summary, second.meta.totalBytes);
        }
        stage = Stage.DONE;
        finish();
    }

    private static void finish() {
        if (failures.isEmpty()) {
            if (!inPlace) {
                Rewind.LOGGER.info("Rewind server self-test: REWIND_SERVERTEST PASS (partial:"
                        + " the in-memory rollback checks were skipped, see the log above)");
            } else {
                Rewind.LOGGER.info("Rewind server self-test: REWIND_SERVERTEST PASS");
            }
        } else {
            Rewind.LOGGER.error("Rewind server self-test: REWIND_SERVERTEST FAIL: {}",
                    String.join(" | ", failures));
        }
        server.halt(false);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures.add(message);
            Rewind.LOGGER.error("Rewind server self-test: check failed: {}", message);
        }
    }
}
