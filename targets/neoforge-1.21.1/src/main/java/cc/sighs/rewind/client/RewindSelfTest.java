package cc.sighs.rewind.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import cc.sighs.rewind.snapshot.SnapshotMirror;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;

/**
 * 端到端自测：在单人世界里按「建立存档点 → 改世界 → 回溯 → 校验」跑一遍真实流程。
 *
 * <p>用 JVM 属性 {@code -Drewind.selftest=true} 打开。它不直接调业务方法，而是通过
 * {@code KeyMapping.click(...)} 模拟真实按键，走的就是 F7 / F8 那条路；世界状态由数据包
 * {@code rewind_test}（{@code run/saves/<世界>/datapacks/rewind_test/}）改，校验在模组侧读服务端状态。
 *
 * <p>结论以日志行为准：{@code REWIND_SELFTEST PASS} 或 {@code REWIND_SELFTEST FAIL: ...}。
 */
public final class RewindSelfTest {
    private static final String PROPERTY = "rewind.selftest";
    private static final String PACK_ID = "file/rewind_test";
    private static final String OBJECTIVE = "rewind_test";
    private static final String SCORE_HOLDER = "rewind_marker";
    private static final String ENTITY_TAG = "rewind_test";
    private static final int STAGE_TIMEOUT_TICKS = 12000;

    private enum Stage {
        WAIT_WORLD,
        ENABLE_PACK,
        PREPARE,
        CAPTURE_BASELINE,
        TRIGGER_SNAPSHOT,
        VERIFY_SNAPSHOT,
        MUTATE,
        VERIFY_MUTATION,
        TRIGGER_RESTORE,
        WAIT_WORLD_CLOSED,
        WAIT_WORLD_REOPENED,
        VERIFY_RESTORED,
        FINISHED
    }

    private static boolean initialised;
    private static boolean enabled;
    private static Stage stage = Stage.WAIT_WORLD;
    private static int stageTicks;
    private static int finishedTicks;
    /** 跑几轮「建点 → 改世界 → 回溯」：第 2 轮验证覆盖与重复回溯。 */
    private static final int MAX_CYCLES = 2;
    private static int cycle = 1;
    private static final List<String> failures = new ArrayList<>();
    private static WorldState baseline;
    private static boolean screenLeakReported;

    private RewindSelfTest() {
    }

    /** 自测是否开着（自测会在过渡满强度时存一张截图，方便人眼确认效果）。 */
    public static boolean isEnabled() {
        return enabled;
    }

    public static void tick(Minecraft minecraft) {
        if (!initialised) {
            initialised = true;
            String value = System.getProperty(PROPERTY);
            enabled = "true".equalsIgnoreCase(value) || "full".equalsIgnoreCase(value) || "1".equals(value);
            if (enabled) {
                Rewind.LOGGER.info("Rewind self-test: enabled (stage machine armed)");
                logKeyMappings();
            }
        }
        if (!enabled) {
            return;
        }
        assertNoUiDuringTransition(minecraft);
        if (stage == Stage.FINISHED) {
            if (++finishedTicks > 60) {
                Rewind.LOGGER.info("Rewind self-test: stopping client");
                minecraft.stop();
            }
            return;
        }

        stageTicks++;
        if (stageTicks > STAGE_TIMEOUT_TICKS) {
            fail("stage " + stage + " timed out after " + stageTicks + " ticks");
            report();
            return;
        }

        switch (stage) {
            case WAIT_WORLD: {
                if (minecraft.level == null || minecraft.player == null || !minecraft.hasSingleplayerServer()) {
                    stageTicks = 0;
                    return;
                }
                if (stageTicks < 60) {
                    return;
                }
                goTo(Stage.ENABLE_PACK);
                return;
            }
            case ENABLE_PACK: {
                if (stageTicks == 1) {
                    runServerCommand("datapack enable \"" + PACK_ID + "\"");
                }
                if (stageTicks < 60) {
                    return;
                }
                goTo(Stage.PREPARE);
                return;
            }
            case PREPARE: {
                if (stageTicks == 1) {
                    runServerCommand("function rewind_test:prepare");
                }
                if (stageTicks < 20) {
                    return;
                }
                goTo(Stage.CAPTURE_BASELINE);
                return;
            }
            case CAPTURE_BASELINE: {
                WorldState state = readState(minecraft);
                if (state == null) {
                    return;
                }
                baseline = state;
                Rewind.LOGGER.info("Rewind self-test: baseline {}", state.describe());
                check(state.block == Blocks.GOLD_BLOCK, "fixture should be gold_block before snapshot, got " + state.block);
                check(state.score == 1, "score should be 1 before snapshot, got " + state.score);
                check(state.stands >= 1, "fixture armour stand missing before snapshot, got " + state.stands);
                if (failures.isEmpty()) {
                    goTo(Stage.TRIGGER_SNAPSHOT);
                } else {
                    report();
                }
                return;
            }
            case TRIGGER_SNAPSHOT: {
                if (stageTicks == 1) {
                    Rewind.LOGGER.info("Rewind self-test: simulating F7 via KeyMapping.click({})",
                            InputConstants.KEY_F7);
                    KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_F7));
                    return;
                }
                if (CheckpointController.isBusy()) {
                    return;
                }
                if (CheckpointController.lastOutcome() == CheckpointController.Outcome.NONE) {
                    if (stageTicks > 200) {
                        fail("F7 did not trigger the checkpoint flow (keybind not wired?)");
                        report();
                    }
                    return;
                }
                if (CheckpointController.lastOutcome() != CheckpointController.Outcome.SUCCESS) {
                    fail("snapshot failed: " + CheckpointController.lastMessage());
                    report();
                    return;
                }
                Rewind.LOGGER.info("Rewind self-test: snapshot finished ({})", CheckpointController.lastMessage());
                goTo(Stage.VERIFY_SNAPSHOT);
                return;
            }
            case VERIFY_SNAPSHOT: {
                verifySnapshotFiles(minecraft);
                if (!failures.isEmpty()) {
                    report();
                } else if (cycle == 1) {
                    // 第 1 轮：先改世界再回溯，验证各处状态确实被抹掉
                    goTo(Stage.MUTATE);
                } else {
                    // 第 2 轮：中间什么都不改，用来验证反向增量还原「一个文件都不用回拷」
                    Rewind.LOGGER.info("Rewind self-test: cycle {} restores without touching the world", cycle);
                    goTo(Stage.TRIGGER_RESTORE);
                }
                return;
            }
            case MUTATE: {
                if (stageTicks == 1) {
                    runServerCommand("function rewind_test:mutate");
                }
                if (stageTicks < 20) {
                    return;
                }
                goTo(Stage.VERIFY_MUTATION);
                return;
            }
            case VERIFY_MUTATION: {
                WorldState state = readState(minecraft);
                if (state == null) {
                    return;
                }
                Rewind.LOGGER.info("Rewind self-test: after mutation {}", state.describe());
                check(state.block == Blocks.DIAMOND_BLOCK, "mutation should swap the fixture to diamond_block, got " + state.block);
                check(state.score == 2, "mutation should set the score to 2, got " + state.score);
                check(state.diamonds == baseline.diamonds + 5,
                        "mutation should add 5 diamonds (" + baseline.diamonds + " -> " + state.diamonds + ")");
                check(Math.abs(state.px - baseline.px) > 5.0D || Math.abs(state.pz - baseline.pz) > 5.0D,
                        "mutation should have teleported the player (still at " + state.describe() + ")");
                check(Math.abs(state.dayTime - baseline.dayTime) > 5000L,
                        "mutation should have pushed the world time away from the checkpoint ("
                                + baseline.dayTime + " -> " + state.dayTime + ")");
                if (failures.isEmpty()) {
                    goTo(Stage.TRIGGER_RESTORE);
                } else {
                    report();
                }
                return;
            }
            case TRIGGER_RESTORE: {
                if (stageTicks == 1) {
                    // A/B：第 1 轮走快速重启，第 2 轮走原版 openWorld，好在同一份世界上比耗时
                    CheckpointController.setFastRestartEnabled(cycle == 1);
                    Rewind.LOGGER.info("Rewind self-test: cycle {} will restore via the {} path", cycle,
                            cycle == 1 ? "fast" : "vanilla");
                    Rewind.LOGGER.info("Rewind self-test: simulating F8 via KeyMapping.click({})",
                            InputConstants.KEY_F8);
                    KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_F8));
                    return;
                }
                // F8 不再要确认屏：点下去就应该直接开始关世界
                if (CheckpointController.isBusy() || minecraft.level == null) {
                    goTo(Stage.WAIT_WORLD_CLOSED);
                    return;
                }
                if (stageTicks > 200) {
                    fail("F8 did not start the restore (keybind not wired?)");
                    report();
                }
                return;
            }
            case WAIT_WORLD_CLOSED: {
                if (minecraft.level != null) {
                    return;
                }
                goTo(Stage.WAIT_WORLD_REOPENED);
                return;
            }
            case WAIT_WORLD_REOPENED: {
                // screen == null 也要等：KeyMapping.click 是靠 clickCount 记的，界面还开着的时候
                // 点击会被攒下来，等界面关掉才被消费，容易在错误的时机触发。
                if (minecraft.level == null || minecraft.player == null || minecraft.screen != null
                        || !minecraft.hasSingleplayerServer()) {
                    return;
                }
                if (stageTicks < 80) {
                    return;
                }
                goTo(Stage.VERIFY_RESTORED);
                return;
            }
            case VERIFY_RESTORED: {
                WorldState state = readState(minecraft);
                if (state == null) {
                    return;
                }
                Rewind.LOGGER.info("Rewind self-test: after restore {}", state.describe());
                check(state.block == baseline.block,
                        "restored fixture block mismatch: " + state.block + " != " + baseline.block);
                check(state.score == baseline.score,
                        "restored score mismatch: " + state.score + " != " + baseline.score);
                check(state.stands == baseline.stands,
                        "restored armour stand count mismatch: " + state.stands + " != " + baseline.stands);
                check(state.diamonds == baseline.diamonds,
                        "restored inventory mismatch: diamonds " + state.diamonds + " != " + baseline.diamonds);
                check(Math.abs(state.px - baseline.px) < 2.5D && Math.abs(state.pz - baseline.pz) < 2.5D,
                        "restored player position mismatch: " + state.describe() + " vs " + baseline.describe());
                // dayTime 只能来自 level.dat，回溯后必须回到建点时的值（差几 tick 是回溯期间正常流逝）
                check(Math.abs(state.dayTime - baseline.dayTime) < 2000L,
                        "restored world time mismatch: " + state.dayTime + " != " + baseline.dayTime);
                // 反向增量：只有建点之后真正被改写过的文件才该回拷。
                // 注意「没动过」在活着的世界里并不存在——服务端每秒都在卸载区块、tick POI，
                // 所以判据只能是「回拷的明显少于总数」（没有增量优化时这里恒等于文件总数）。
                int copiedFiles = CheckpointController.lastRestoreCopiedFiles();
                int skippedFiles = CheckpointController.lastRestoreSkippedFiles();
                int totalFiles = CheckpointController.lastRestoreFileCount();
                check(copiedFiles >= 0 && skippedFiles > 0 && copiedFiles < totalFiles,
                        "restore should skip the files the checkpoint already matches, copied=" + copiedFiles
                                + " skipped=" + skippedFiles + " total=" + totalFiles);
                Rewind.LOGGER.info("Rewind self-test: cycle {} incremental restore copied={} skipped={} total={}",
                        cycle, copiedFiles, skippedFiles, totalFiles);
                if (cycle < MAX_CYCLES && failures.isEmpty()) {
                    cycle++;
                    Rewind.LOGGER.info("Rewind self-test: starting cycle {} (覆盖已有存档点后再次回溯)", cycle);
                    goTo(Stage.TRIGGER_SNAPSHOT);
                } else {
                    report();
                }
                return;
            }
            default:
                return;
        }
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 硬性要求：过渡期间不允许出现任何界面。
     * 唯一允许存在的是我们自己那个什么都不画的逻辑屏（它是为了满足原版「没有世界就一定有界面」的假设）。
     */
    private static void assertNoUiDuringTransition(Minecraft minecraft) {
        if (screenLeakReported || !RewindTransition.isActive() || RewindTransition.strength() <= 0.5F) {
            return;
        }
        if (minecraft.screen != null && !(minecraft.screen instanceof RewindBlankScreen)) {
            screenLeakReported = true;
            fail("a screen appeared during the transition: " + minecraft.screen.getClass().getName());
        }
    }

    private static void verifySnapshotFiles(Minecraft minecraft) {
        try {
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server == null) {
                fail("server disappeared during snapshot verification");
                return;
            }
            Path world = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
            Path slotDir = SnapshotLayout.slotDir(world, Rewind.SLOT);
            SnapshotMeta meta = SnapshotIndex.load(SnapshotLayout.indexFile(world)).get(Rewind.SLOT);

            check(meta != null && meta.isComplete(), "snapshot index status is not complete: " + (meta == null ? "absent" : meta.status));
            check(Files.isRegularFile(slotDir.resolve("level.dat")), "snapshot is missing level.dat");
            check(!Files.exists(slotDir.resolve("session.lock")), "snapshot must not contain session.lock");
            check(!Files.isDirectory(slotDir.resolve(SnapshotLayout.ROOT_DIR_NAME)), "snapshot must not contain itself");

            List<String> worldFiles = SnapshotMirror.listFiles(world);
            List<String> snapshotFiles = SnapshotMirror.listFiles(slotDir);
            check(worldFiles.size() == snapshotFiles.size(),
                    "snapshot file count " + snapshotFiles.size() + " != world file count " + worldFiles.size());
            if (meta != null) {
                check(meta.fileCount == snapshotFiles.size(),
                        "index fileCount " + meta.fileCount + " != snapshot files " + snapshotFiles.size());
            }
            if (Files.isRegularFile(world.resolve("level.dat")) && Files.isRegularFile(slotDir.resolve("level.dat"))) {
                check(Files.size(world.resolve("level.dat")) == Files.size(slotDir.resolve("level.dat")),
                        "snapshot level.dat size mismatch");
            }
            List<String> regions = SnapshotMirror.listFiles(slotDir.resolve("region"));
            check(!regions.isEmpty(), "snapshot has no overworld region files");
            if (!regions.isEmpty()) {
                String first = regions.get(0);
                check(Files.size(slotDir.resolve("region").resolve(first)) == Files.size(world.resolve("region").resolve(first)),
                        "snapshot region file size mismatch for " + first);
            }
            Rewind.LOGGER.info("Rewind self-test: snapshot files verified (worldFiles={}, snapshotFiles={}, regions={})",
                    worldFiles.size(), snapshotFiles.size(), regions.size());
        } catch (Exception e) {
            fail("snapshot verification threw: " + e);
        }
    }

    private static WorldState readState(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        WorldState state;
        try {
            state = server.submit(() -> readStateOnServer(server)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Rewind.LOGGER.error("Rewind self-test: reading server state failed", e);
            return null;
        }
        if (minecraft.player != null) {
            Vec3 position = minecraft.player.position();
            state.px = position.x;
            state.py = position.y;
            state.pz = position.z;
            int diamonds = 0;
            Inventory inventory = minecraft.player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(Items.DIAMOND)) {
                    diamonds += stack.getCount();
                }
            }
            state.diamonds = diamonds;
        }
        return state;
    }

    private static WorldState readStateOnServer(MinecraftServer server) {
        WorldState state = new WorldState();
        ServerLevel level = server.overworld();
        BlockPos anchor = level.getSharedSpawnPos().offset(0, 2, 0);
        state.block = level.getBlockState(anchor).getBlock();
        // 世界时间来自 level.dat（WorldData），用来验证「快速重启」确实重读了快照里的 level.dat
        state.dayTime = level.getDayTime();

        Objective objective = server.getScoreboard().getObjective(OBJECTIVE);
        if (objective != null) {
            ReadOnlyScoreInfo info = server.getScoreboard()
                    .getPlayerScoreInfo(ScoreHolder.forNameOnly(SCORE_HOLDER), objective);
            if (info != null) {
                state.score = info.value();
            }
        }
        int stands = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity.getTags().contains(ENTITY_TAG)) {
                stands++;
            }
        }
        state.stands = stands;
        return state;
    }

    // ------------------------------------------------------------------ 基础设施

    private static void runServerCommand(String command) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                Rewind.LOGGER.info("Rewind self-test: running /{}", command);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind self-test: command failed: /{}", command, t);
            }
        });
    }

    private static void logKeyMappings() {
        KeyMapping snapshot = RewindClient.snapshotKey();
        KeyMapping restore = RewindClient.restoreKey();
        Rewind.LOGGER.info("Rewind self-test: keybindings snapshot={} key={} restore={} key={}",
                snapshot == null ? "MISSING" : snapshot.getName(),
                snapshot == null ? "-" : String.valueOf(snapshot.getKey().getValue()),
                restore == null ? "MISSING" : restore.getName(),
                restore == null ? "-" : String.valueOf(restore.getKey().getValue()));
    }

    private static void goTo(Stage next) {
        Rewind.LOGGER.info("Rewind self-test: stage {} -> {}", stage, next);
        stage = next;
        stageTicks = 0;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            fail(message);
        }
    }

    private static void fail(String message) {
        failures.add(message);
        Rewind.LOGGER.error("Rewind self-test: check failed: {}", message);
    }

    private static void report() {
        if (failures.isEmpty()) {
            Rewind.LOGGER.info("REWIND_SELFTEST PASS");
        } else {
            Rewind.LOGGER.error("REWIND_SELFTEST FAIL: {}", String.join(" | ", failures));
        }
        stage = Stage.FINISHED;
        finishedTicks = 0;
    }

    private static final class WorldState {
        private Block block = Blocks.AIR;
        private int score = Integer.MIN_VALUE;
        private int stands = -1;
        private double px;
        private double py;
        private double pz;
        private int diamonds = -1;
        private long dayTime;

        private String describe() {
            return "block=" + block
                    + " score=" + score
                    + " stands=" + stands
                    + " player=" + String.format(Locale.ROOT, "%.2f/%.2f/%.2f", px, py, pz)
                    + " diamonds=" + diamonds
                    + " dayTime=" + dayTime;
        }
    }
}
