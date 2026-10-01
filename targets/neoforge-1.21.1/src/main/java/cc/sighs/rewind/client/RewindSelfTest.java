package cc.sighs.rewind.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.server.RewindServerConfig;
import cc.sighs.rewind.snapshot.SnapshotBlockStore;
import cc.sighs.rewind.snapshot.SnapshotBlocks;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import cc.sighs.rewind.snapshot.SnapshotMirror;
import cc.sighs.mixin.KeyBindsListMixins;
import com.mojang.blaze3d.platform.InputConstants;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.loader.Loader;
import com.sighs.apricityui.render.ImageDrawer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 端到端自测：在单人世界里按「建立存档点 → 改世界 → 回溯 → 校验」跑一遍真实流程。
 *
 * <p>用 JVM 属性 {@code -Drewind.selftest=true} 打开。它不直接调业务方法，而是通过
 * {@code KeyMapping.click(...)} 模拟真实按键，走的就是 F7 / F8 那条路；世界状态由数据包
 * {@code rewind_test}（{@code run/saves/<世界>/datapacks/rewind_test/}）改，校验在模组侧读服务端状态。
 *
 * <p>第 1 轮在存档点写完之后会开一次「时间树」界面（{@link Stage#VERIFY_TREE}），最后一轮结束后
 * 再开一次查覆盖 / 删除（{@link Stage#VERIFY_TREE_MANAGE}）——那套界面完全由 Java 侧驱动，
 * 所以能在这里直接把 DOM 查一遍。
 *
 * <p>结论以日志行为准：{@code REWIND_SELFTEST PASS} 或 {@code REWIND_SELFTEST FAIL: ...}。
 */
public final class RewindSelfTest {
    private static final String PROPERTY = "rewind.selftest";
    private static final String PACK_ID = "file/rewind_test";
    private static final String OBJECTIVE = "rewind_test";
    private static final String SCORE_HOLDER = "rewind_marker";
    private static final String ENTITY_TAG = "rewind_test";
    /** 建点之后才召唤的实体用的 tag：用来验证「只放实体、没动方块」的区块也会被回滚。 */
    private static final String EXTRA_TAG = "rewind_test_extra";
    /** 自测里给槽位起的名字（跑完会改回去，不留在存档里）。 */
    private static final String RENAME_PROBE = "rewind-selftest";
    /** 最后一段（界面上的覆盖 / 删除）走到哪一步：0 = 还没查，1 = 已触发覆盖、等写盘落地。 */
    private static int managePhase;
    /** 「时间树」那一轮的子步骤：0 = 档案布局，1 = 已切到节点树布局、等布局算完。 */
    private static int treePhase;
    /** 进入当前子步骤的 tick：等待时长按「进入之后过了几拍」算，不能拿 stageTicks 当绝对阈值。 */
    private static int treePhaseStart;
    /** 覆盖 / 删除这两条路用的探针槽位：建完就删，跑完不留痕迹。 */
    private static final String PROBE_SLOT = SnapshotLayout.manualSlot(1);
    /** 自动建点之前快速槽位的 savedAt，用来断言「自动存档不许碰快速槽位」。 */
    private static long quickSavedAtBeforeAuto = -1L;
    /** 主背包的格数（9 × 4，含快捷栏），展开后详情面板至少要有这么多格。 */
    private static final int MAIN_INVENTORY_CELLS = 36;
    /** 折叠状态下只显示一行快捷栏（9 格）。 */
    private static final int HOTBAR_CELLS = 9;
    private static final int STAGE_TIMEOUT_TICKS = 12000;
    /**
     * 开「时间树」之后等多久再截图 / 查时间线（tick）。
     *
     * <p>AUI 从 1.2.5.2 起把整页文字的光栅化丢到工作线程，**首绘时还没光栅完的行是留白的**
     * （日志里 `[AUI Font] blank text draw`），一帧只上传 16 条。整页两百来条文字要十几帧才铺满，
     * 所以太早截图会得到一张没字的图——DOM 断言不受影响（结构早就对了），但截图是给人看的。
     */
    private static final int TREE_SCREEN_SETTLE_TICKS = 50;
    /** 切到节点树布局之后再等这么久：新铺出来的节点卡片又是一批没光栅过的文字。 */
    private static final int TREE_TIMELINE_SETTLE_TICKS = 90;
    /** 打开「自动存档」的设置弹窗之后再等这么久才截图：弹窗是上一帧才画出来的，里面的字也要等光栅。 */
    private static final int TREE_SETTINGS_SETTLE_TICKS = 80;

    private enum Stage {
        WAIT_WORLD,
        ENABLE_PACK,
        PREPARE,
        CAPTURE_BASELINE,
        TRIGGER_SNAPSHOT,
        VERIFY_SNAPSHOT,
        /** 打开「时间树」界面，验证它确实把磁盘上的存档点渲染出来了（只在第 1 轮跑）。 */
        VERIFY_TREE,
        /** 「跟着原版自动保存建点」：关掉不写、打开写（只在第 1 轮跑）。 */
        VERIFY_AUTO,
        /** 界面上的覆盖 / 删除：探针槽位建点 → 弹确认 → 删除（最后一步）。 */
        VERIFY_TREE_MANAGE,
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
    /** 「从界面里按下的热键会把界面收掉」这条断言每轮只查一次。 */
    private static boolean guiCloseChecked;
    /** 开局时玩家已经死了多久（用来每 20 tick 求一次复活）。 */
    private static int deadTicks;
    /** 本轮是不是走原地回滚（第 1 轮走原地，第 2 轮走「关世界重开」做 A/B）。 */
    private static boolean expectInPlace;
    /** 本轮回溯期间世界有没有被关掉——原地回滚的核心断言就是「没关」。 */
    private static boolean worldWasClosed;
    private static long inPlaceMillis = -1L;
    private static long reopenMillis = -1L;

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
                // 测试世界是反复复用的，玩家可能已经死了：死亡屏会挡掉一切，先复活再开跑。
                // （玩家位置/背包在自测里是相对 baseline 比对的，所以复活不会影响断言。）
                if (minecraft.player.isDeadOrDying()) {
                    deadTicks++;
                    if (deadTicks % 20 == 1) {
                        Rewind.LOGGER.info("Rewind self-test: player is dead; requesting respawn (attempt {})",
                                deadTicks / 20 + 1);
                        minecraft.player.respawn();
                    }
                    stageTicks = 0;
                    return;
                }
                deadTicks = 0;
                if (minecraft.screen != null) {
                    // 死亡屏 / 暂停屏之类：自测要在「世界里」跑，先摘掉
                    Rewind.LOGGER.info("Rewind self-test: closing {} before the run",
                            minecraft.screen.getClass().getSimpleName());
                    minecraft.setScreen(null);
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
                check(state.extra == 0, "extra entity should not exist before the snapshot, got " + state.extra);
                if (failures.isEmpty()) {
                    goTo(Stage.TRIGGER_SNAPSHOT);
                } else {
                    report();
                }
                return;
            }
            case TRIGGER_SNAPSHOT: {
                if (stageTicks == 1) {
                    // 等上一次过渡彻底结束再开界面：界面出现在过渡上面是玩家自己开的，不属于
                    // 「过渡期间不得出现界面」要管的事，但会误伤那条断言
                    if (RewindTransition.isActive()) {
                        stageTicks = 0;
                        return;
                    }
                    pressKeyFromGui(minecraft, InputConstants.KEY_F7, "F7");
                    return;
                }
                if (CheckpointController.isBusy()) {
                    checkGuiClosed(minecraft, "F7");
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
                    // 第 1 轮：先验自动建点、看一眼时间树，再改世界、回溯，验证各处状态确实被抹掉
                    goTo(Stage.VERIFY_AUTO);
                } else {
                    // 第 2 轮：中间什么都不改，用来验证反向增量还原「一个文件都不用回拷」
                    Rewind.LOGGER.info("Rewind self-test: cycle {} restores without touching the world", cycle);
                    goTo(Stage.TRIGGER_RESTORE);
                }
                return;
            }
            case VERIFY_AUTO: {
                // 「跟着原版自动保存建点」：关掉时不该写，打开时该写。触发的就是原版自动保存那一次
                // 调用的参数（saveEverything(true, false, false)），不用等五分钟一次的真自动保存。
                IntegratedServer server = minecraft.getSingleplayerServer();
                if (server == null) {
                    fail("the integrated server is gone before the auto checkpoint check");
                    report();
                    return;
                }
                Path world = RewindApi.worldRoot(server);
                if (stageTicks == 1) {
                    // 自动存档是**独立**槽位：先记下快速槽位此刻的状态，等下断言自动建点没碰它
                    SnapshotMeta quick = RewindApi.describe(world, Rewind.SLOT);
                    quickSavedAtBeforeAuto = quick == null ? -1L : quick.savedAtMillis;
                    RewindApi.deleteCheckpoint(world, SnapshotLayout.SLOT_AUTO);
                    RewindServerConfig.setAutoCheckpointEnabled(false);
                    runAutosave(server);
                    return;
                }
                if (stageTicks == 20) {
                    check(!RewindApi.hasCheckpoint(world, SnapshotLayout.SLOT_AUTO),
                            "the auto slot must stay empty while the follow-autosave toggle is off");
                    Rewind.LOGGER.info("Rewind self-test: auto checkpoint off -> the slot stayed empty");
                    RewindServerConfig.setAutoCheckpointEnabled(true);
                    runAutosave(server);
                    return;
                }
                if (stageTicks < 40) {
                    return;
                }
                if (RewindApi.hasCheckpoint(world, SnapshotLayout.SLOT_AUTO)) {
                    SnapshotMeta auto = RewindApi.describe(world, SnapshotLayout.SLOT_AUTO);
                    check(auto != null && "autosave".equals(auto.source),
                            "the auto checkpoint should record source=autosave, got "
                                    + (auto == null ? "absent" : auto.source));
                    // 自动存档和快速存档是两个独立槽位：自动建点不许碰快速槽位
                    SnapshotMeta quickAfter = RewindApi.describe(world, Rewind.SLOT);
                    check(quickAfter != null && quickAfter.savedAtMillis == quickSavedAtBeforeAuto,
                            "the auto checkpoint must not touch the quick slot (savedAt " + quickSavedAtBeforeAuto
                                    + " -> " + (quickAfter == null ? "absent" : quickAfter.savedAtMillis) + ")");
                    check(auto != null && quickAfter != null && auto.savedAtMillis != quickAfter.savedAtMillis,
                            "the auto and quick slots should hold different checkpoints");
                    Rewind.LOGGER.info("Rewind self-test: auto checkpoint written by the vanilla autosave ({})",
                            auto == null ? "absent" : auto.describe());
                    goTo(Stage.VERIFY_TREE);
                    return;
                }
                if (stageTicks > 200) {
                    fail("the vanilla autosave did not write the auto slot");
                    report();
                }
                return;
            }
            case VERIFY_TREE: {
                if (stageTicks == 1) {
                    // 存档点的过渡还在淡出时不能开界面：那会被「过渡期间不得出现任何界面」当场抓住
                    if (RewindTransition.isActive()) {
                        stageTicks = 0;
                        return;
                    }
                    Rewind.LOGGER.info("Rewind self-test: opening the time tree");
                    treePhase = 0;
                    treePhaseStart = stageTicks;
                    RewindTreeScreen.open();
                    return;
                }
                if (!(minecraft.screen instanceof RewindTreeScreen tree)) {
                    if (stageTicks > 100) {
                        fail("the tree screen did not open (screen=" + minecraft.screen + ")");
                        report();
                    }
                    return;
                }
                if (stageTicks < 5) {
                    // 让 AUI 的样式与布局先跑一两帧，别去读还没算完的盒子
                    return;
                }
                if (treePhase == 0) {
                    // 档案布局：卡片、详情面板、背包快照、设置弹窗
                    if (stageTicks - treePhaseStart < TREE_SCREEN_SETTLE_TICKS) {
                        return;
                    }
                    verifyTree(tree);
                    screenshotTree(minecraft, "rewind-tree");
                    treePhase = 1;
                    treePhaseStart = stageTicks;
                    // 把「自动存档」的设置弹窗重新打开并留着：隔几拍截一张图，给人眼看看那两个配置项
                    Document timelineDoc = tree.getLinkedDocument();
                    Element settings = timelineDoc == null ? null : timelineDoc.querySelector("#specialGrid [data-slot=\""
                            + SnapshotLayout.SLOT_AUTO + "\"] [data-act=\"settings\"]");
                    check(settings != null, "the auto card should offer a settings button");
                    if (settings != null) {
                        settings.click();
                    }
                    return;
                }
                if (treePhase == 1) {
                    // 弹窗刚打开那一帧还没画出来（截图读的是上一帧），而且弹窗里的字也要等光栅
                    if (stageTicks - treePhaseStart < TREE_SETTINGS_SETTLE_TICKS) {
                        return;
                    }
                    screenshotTree(minecraft, "rewind-auto-settings");
                    Document settingsDoc = tree.getLinkedDocument();
                    Element close = settingsDoc == null
                            ? null
                            : settingsDoc.querySelector("#modalSettings [data-act=\"modal-close\"]");
                    if (close != null) {
                        close.click();
                    }
                    treePhase = 2;
                    treePhaseStart = stageTicks;
                    // 再打开「快速存档」的设置：那两条过渡强度滚动条也要人眼看一眼
                    Element quickSettings = settingsDoc == null
                            ? null
                            : settingsDoc.querySelector("#specialGrid [data-slot=\"" + Rewind.SLOT
                                    + "\"] [data-act=\"settings\"]");
                    check(quickSettings != null, "the quick card should offer a settings button");
                    if (quickSettings != null) {
                        quickSettings.click();
                    }
                    return;
                }
                if (treePhase == 2) {
                    if (stageTicks - treePhaseStart < TREE_SETTINGS_SETTLE_TICKS) {
                        return;
                    }
                    screenshotTree(minecraft, "rewind-quick-settings");
                    Document quickDoc = tree.getLinkedDocument();
                    Element closeQuick = quickDoc == null
                            ? null
                            : quickDoc.querySelector("#modalSettings [data-act=\"modal-close\"]");
                    if (closeQuick != null) {
                        closeQuick.click();
                    }
                    treePhase = 3;
                    treePhaseStart = stageTicks;
                    openTimeline(tree);
                    return;
                }
                if (treePhase == 3) {
                    // 节点树布局：切过去之后要等布局算完、fitFlow 把树装进画布（它下一 tick 才动手），
                    // 再等新铺出来的节点文字光栅完
                    if (stageTicks - treePhaseStart < TREE_TIMELINE_SETTLE_TICKS) {
                        return;
                    }
                    verifyTimeline(tree);
                    screenshotTree(minecraft, "rewind-timeline");
                    treePhase = 4;
                    treePhaseStart = stageTicks;
                    return;
                }
                minecraft.setScreen(null);
                if (failures.isEmpty()) {
                    goTo(Stage.MUTATE);
                } else {
                    report();
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
                check(state.extra == 1, "mutation should have summoned one extra entity, got " + state.extra);
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
                    if (RewindTransition.isActive()) {
                        stageTicks = 0;
                        return;
                    }
                    // A/B：第 1 轮走原地回滚，第 2 轮走「关世界 → 重开」，好在同一份世界上比耗时
                    expectInPlace = cycle == 1;
                    worldWasClosed = false;
                    CheckpointController.setInPlaceEnabled(expectInPlace);
                    CheckpointController.setFastRestartEnabled(cycle == 1);
                    // 把客户端的快捷栏选中槽位挪走，好验证回滚之后它会被推回建点时的那个。
                    // 只对原地回滚做：关世界重开那条路上客户端会重登，选中槽位由它自己那套逻辑收敛。
                    if (expectInPlace && minecraft.player != null && baseline != null) {
                        minecraft.player.getInventory().selected = (baseline.selectedSlot + 4) % 9;
                        Rewind.LOGGER.info(
                                "Rewind self-test: moved the client's hotbar selection to {} (baseline {})",
                                minecraft.player.getInventory().selected, baseline.selectedSlot);
                    }
                    Rewind.LOGGER.info("Rewind self-test: cycle {} will restore via the {} path", cycle,
                            expectInPlace ? "in-place" : "close-and-reopen");
                    pressKeyFromGui(minecraft, InputConstants.KEY_F8, "F8");
                    return;
                }
                // F8 不再要确认屏：点下去就应该直接开始回溯
                if (CheckpointController.isBusy() || minecraft.level == null) {
                    checkGuiClosed(minecraft, "F8");
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
                if (minecraft.level == null) {
                    worldWasClosed = true;
                }
                if (CheckpointController.isBusy()) {
                    return;
                }
                if (expectInPlace) {
                    check(!worldWasClosed, "in-place restore must keep the world open, but it was closed");
                    check(CheckpointController.lastRestoreInPlace(),
                            "restore was expected to run in place, but it fell back to close-and-reopen");
                    inPlaceMillis = CheckpointController.lastRestoreMillis();
                } else {
                    check(worldWasClosed, "close-and-reopen restore should have closed the world");
                    reopenMillis = CheckpointController.lastRestoreMillis();
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
                // 实体回滚的关键断言：位置要回去，而且建点之后才召唤的实体必须消失。
                // 只数个数是不够的——把盔甲架传送到别处、个数照样是 1，之前那轮 PASS 就是这么空过的。
                check(Math.abs(state.standX - baseline.standX) < 0.5D
                                && Math.abs(state.standY - baseline.standY) < 0.5D
                                && Math.abs(state.standZ - baseline.standZ) < 0.5D,
                        "restored armour stand position mismatch: " + state.describe() + " vs " + baseline.describe());
                check(state.extra == 0,
                        "entities summoned after the checkpoint must be gone after the restore, got " + state.extra);
                check(state.diamonds == baseline.diamonds,
                        "restored inventory mismatch: diamonds " + state.diamonds + " != " + baseline.diamonds);
                // 快捷栏选中槽位：原地回滚之后服务端必须把它推回客户端，否则客户端显示/选中的物品
                // 和服务端实际用的是两回事（物品栏内容是一起回滚的，这个错位不容易看出来）。
                // 关世界重开那条路客户端会重登，选中槽位由它自己那套逻辑收敛，不在这里断言。
                if (cycle == 1) {
                    check(state.selectedSlot == baseline.selectedSlot,
                            "restored hotbar selection mismatch: " + state.selectedSlot
                                    + " != " + baseline.selectedSlot);
                }
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
                if (expectInPlace) {
                    RewindResult result = CheckpointController.lastResult();
                    Rewind.LOGGER.info("Rewind self-test: in-place rollback detail {}",
                            result == null ? "n/a" : result.describe());
                    check(result != null && result.success && result.inPlace,
                            "the in-place rollback should report success through the API, got " + result);
                    check(inPlaceMillis > 0L && inPlaceMillis < 1500L,
                            "in-place restore should finish well under 1.5s, took " + inPlaceMillis + " ms");
                }
                if (cycle < MAX_CYCLES && failures.isEmpty()) {
                    cycle++;
                    Rewind.LOGGER.info("Rewind self-test: starting cycle {} (覆盖已有存档点后再次回溯)", cycle);
                    goTo(Stage.TRIGGER_SNAPSHOT);
                } else {
                    Rewind.LOGGER.info("Rewind self-test: A/B restore timing in-place={} ms close-and-reopen={} ms",
                            inPlaceMillis, reopenMillis);
                    check(inPlaceMillis > 0L && reopenMillis > 0L && inPlaceMillis < reopenMillis,
                            "in-place restore (" + inPlaceMillis + " ms) should beat close-and-reopen ("
                                    + reopenMillis + " ms)");
                    if (failures.isEmpty()) {
                        goTo(Stage.VERIFY_TREE_MANAGE);
                    } else {
                        report();
                    }
                }
                return;
            }
            case VERIFY_TREE_MANAGE: {
                if (stageTicks == 1) {
                    if (RewindTransition.isActive()) {
                        stageTicks = 0;
                        return;
                    }
                    managePhase = 0;
                    // 先给探针槽位建一个存档点（同步等它写完：这轮不用过渡，也就没有淡入可等）
                    writeProbeSlot(minecraft);
                    return;
                }
                if (stageTicks < 5) {
                    return;
                }
                if (!(minecraft.screen instanceof RewindTreeScreen tree)) {
                    Rewind.LOGGER.info("Rewind self-test: opening the time tree for the management checks");
                    RewindTreeScreen.open();
                    return;
                }
                if (managePhase == 0) {
                    // 探针卡片 / 重命名 / 删除 / 触发一次界面里的覆盖，都在这一帧里同步做完
                    verifyTreeManage(tree);
                    managePhase = 1;
                    return;
                }
                // 界面里那次覆盖是后台写的：等它落地，同时确认界面一直开着、没起过渡
                IntegratedServer manageServer = minecraft.getSingleplayerServer();
                SnapshotMeta written = manageServer == null
                        ? null
                        : RewindApi.describe(RewindApi.worldRoot(manageServer), PROBE_SLOT);
                if (written != null && written.isComplete()) {
                    check(minecraft.screen instanceof RewindTreeScreen,
                            "the tree should still be open after the background save, but the screen is "
                                    + minecraft.screen);
                    check(!RewindTransition.isActive(), "the background save must not start a transition");
                    Path manageWorld = RewindApi.worldRoot(manageServer);
                    boolean cover = CoverCapture.hasCover(String.valueOf(manageWorld.getFileName()), PROBE_SLOT,
                            written.savedAtMillis);
                    if (!cover && stageTicks <= 400) {
                        // 封面是抓帧之后异步落盘的，比索引晚几十毫秒，等它出现（超时由上面那个 400 兜着）
                        return;
                    }
                    check(cover, "overwriting from inside the tree should still capture a cover");
                    if (managePhase == 1) {
                        // 封面落地之后界面还要自己重画一次，卡片上才会出现 <img>（不然要等玩家重开页面）
                        managePhase = 2;
                        return;
                    }
                    Element shot = tree.getLinkedDocument() == null ? null
                            : tree.getLinkedDocument().querySelector(
                                    "#slotGrid [data-slot=\"" + PROBE_SLOT + "\"] img.cover-shot");
                    check(shot != null, "the probe card should show the cover <img> once the file lands");
                    Rewind.LOGGER.info("Rewind self-test: overwriting from the tree kept the screen open ({})",
                            written.describe());
                    minecraft.setScreen(null);
                    report();
                    return;
                }
                if (stageTicks > 400) {
                    fail("the overwrite started from the tree never finished");
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

            // 顺带把抽出来的 API 查一遍：命令和别的模组读的就是这几个入口
            check(RewindApi.worldRoot(server).equals(world), "RewindApi.worldRoot() disagrees with the server's world path");
            check(RewindApi.hasCheckpoint(world, Rewind.SLOT), "RewindApi.hasCheckpoint() should see the checkpoint we just wrote");
            SnapshotMeta described = RewindApi.describe(world, Rewind.SLOT);
            check(described != null && described.isComplete(),
                    "RewindApi.describe() should return a complete checkpoint, got " + described);
            check(RewindApi.listCheckpoints(world).stream().anyMatch(entry -> Rewind.SLOT.equals(entry.slot)),
                    "RewindApi.listCheckpoints() should contain slot " + Rewind.SLOT);
            check(!RewindApi.hasCheckpoint(world, "rewind_selftest_absent_slot"),
                    "RewindApi.hasCheckpoint() should be false for a slot that was never written");

            check(meta != null && meta.isComplete(), "snapshot index status is not complete: " + (meta == null ? "absent" : meta.status));
            check(Files.isRegularFile(slotDir.resolve("level.dat")), "snapshot is missing level.dat");
            check(!Files.exists(slotDir.resolve("session.lock")), "snapshot must not contain session.lock");
            check(!Files.isDirectory(slotDir.resolve(SnapshotLayout.ROOT_DIR_NAME)), "snapshot must not contain itself");

            List<String> worldFiles = SnapshotMirror.listFiles(world);
            // 块编码的 .mca 在槽位里没有实体文件，逻辑文件集合要把块映射并进来
            SnapshotBlocks blocks = SnapshotBlocks.load(SnapshotLayout.blockMapFile(world, Rewind.SLOT));
            List<String> snapshotFiles = new ArrayList<>(SnapshotMirror.listFiles(slotDir));
            snapshotFiles.addAll(blocks.paths());
            check(worldFiles.size() == snapshotFiles.size(),
                    "snapshot file count " + snapshotFiles.size() + " != world file count " + worldFiles.size());
            check(snapshotFiles.containsAll(worldFiles),
                    "snapshot is missing " + worldFiles.stream().filter(file -> !snapshotFiles.contains(file)).toList());
            if (meta != null) {
                check(meta.fileCount == snapshotFiles.size(),
                        "index fileCount " + meta.fileCount + " != snapshot files " + snapshotFiles.size());
            }
            if (Files.isRegularFile(world.resolve("level.dat")) && Files.isRegularFile(slotDir.resolve("level.dat"))) {
                check(Files.size(world.resolve("level.dat")) == Files.size(slotDir.resolve("level.dat")),
                        "snapshot level.dat size mismatch");
            }
            // region 文件走 4 KiB 块存储：抽一个重建出来，逐字节跟世界里那份比
            List<String> regions = new ArrayList<>();
            for (String relative : blocks.paths()) {
                if (relative.startsWith("region/")) {
                    regions.add(relative);
                }
            }
            check(!regions.isEmpty(), "the snapshot should keep the overworld region files in block storage");
            if (!regions.isEmpty()) {
                String first = regions.get(0);
                SnapshotBlocks.Entry entry = blocks.get(first);
                Path rebuilt = Files.createTempFile("rewind-selftest", ".mca");
                Path scratch = Files.createTempDirectory("rewind-selftest-blocks");
                try (SnapshotBlockStore store = SnapshotBlockStore.open(SnapshotLayout.blocksRoot(world))) {
                    store.decode(entry.hashes, entry.size, rebuilt);
                    check(Files.size(rebuilt) == entry.size,
                            "rebuilt " + first + " is " + Files.size(rebuilt) + " bytes, expected " + entry.size);
                    // 把重建出来的文件再编码一次，块序列必须与记录逐块一致。这里不能拿世界的当前内容去比：
                    // 快照写完之后服务器还在 tick，region 文件随时会被重新落盘（mtime 都变了），
                    // 那是世界在往前走，不是快照错了。内容正确性由两轮回溯后的世界状态断言负责。
                    try (SnapshotBlockStore scratchStore = SnapshotBlockStore.open(scratch)) {
                        check(entry.hashes.equals(scratchStore.encode(rebuilt).hashes),
                                "rebuilding " + first + " did not reproduce the recorded blocks");
                    }
                } finally {
                    Files.deleteIfExists(rebuilt);
                    try (var paths = Files.walk(scratch)) {
                        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
            }
            // 界面要用的那几样：一次读完整张索引、槽位清单、背包快照
            check(RewindApi.slots().size() == SnapshotLayout.MANUAL_SLOT_COUNT + 2,
                    "RewindApi.slots() should list the 2 special slots plus "
                            + SnapshotLayout.MANUAL_SLOT_COUNT + " manual ones, got " + RewindApi.slots());
            check(RewindApi.describeAll(world).containsKey(Rewind.SLOT),
                    "RewindApi.describeAll() should contain slot " + Rewind.SLOT);
            check(!RewindApi.deleteCheckpoint(world, "rewind_selftest_absent_slot"),
                    "deleting a slot that was never written should report nothing to delete");

            SnapshotInventory inventory = RewindApi.readInventory(world, Rewind.SLOT);
            check(!inventory.isEmpty(), "the checkpoint should carry an inventory snapshot");
            boolean hasFixtureItems = inventory.entries().stream()
                    .anyMatch(entry -> entry.item.contains("minecraft:redstone"))
                    && inventory.entries().stream().anyMatch(entry -> entry.item.contains("minecraft:iron_ingot"));
            check(hasFixtureItems, "the inventory snapshot should contain the fixture's redstone and iron, got "
                    + inventory.size() + " entries");
            check(inventory.entries().stream().allMatch(entry -> entry.index >= 0 && entry.index < SnapshotInventory.SLOT_COUNT),
                    "the inventory snapshot has an out-of-range slot index");
            Rewind.LOGGER.info("Rewind self-test: inventory snapshot entries={}", inventory.size());

            if (meta != null) {
                check(!meta.biomeId.isEmpty(), "the checkpoint should record the player's biome");
                check(meta.playtimeTicks > 0, "the checkpoint should record the playtime, got " + meta.playtimeTicks);
                Rewind.LOGGER.info("Rewind self-test: meta biome={} playtimeTicks={} name=\"{}\"",
                        meta.biomeId, meta.playtimeTicks, meta.displayName);
            }

            Rewind.LOGGER.info("Rewind self-test: snapshot files verified (worldFiles={}, snapshotFiles={}, regions={})",
                    worldFiles.size(), snapshotFiles.size(), regions.size());
        } catch (Exception e) {
            fail("snapshot verification threw: " + e);
        }
    }

    /**
     * 「时间树」界面：模板扫没扫到、卡片有没有按磁盘上的数据铺出来、点击能不能选中、重命名能不能落地。
     *
     * <p>这套界面完全由 Java 侧驱动（AUI 的页面 {@code <script>} 要有 KubeJS 才会执行），所以这里查的
     * 就是 Java 侧真的把 DOM 铺对了——包括 AUI 的自定义 {@code <item>} 元素有没有被解析出来。
     */
    private static void verifyTree(RewindTreeScreen tree) {
        Document document = tree.getLinkedDocument();
        check(document != null, "the tree screen has no document (is screens/rewind_screen.html being scanned?)");
        if (document == null) {
            return;
        }
        List<Element> special = document.querySelectorAll("#specialGrid .special-card");
        List<Element> manual = document.querySelectorAll("#slotGrid .slot-card");
        List<Element> chips = document.querySelectorAll("#hudBar .hud-chip");
        check(special.size() == 2, "the tree should render 2 special cards, got " + special.size());
        check(manual.size() == 8, "the tree should render 8 manual slots, got " + manual.size());
        check(chips.size() == 4, "the tree HUD should have 4 chips, got " + chips.size());

        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        Element quickCard = document.querySelector("#specialGrid [data-slot=\"" + Rewind.SLOT + "\"]");
        check(quickCard != null, "the quick slot card is missing");
        if (quickCard != null && server != null) {
            String worldName = server.getWorldData().getLevelName();
            check(quickCard.getTextContent().contains(worldName),
                    "the quick card should show the world name \"" + worldName + "\", got: " + quickCard.getTextContent());
        }

        // 空槽位的「读取」必须是禁用的
        Element emptyLoad = document.querySelector("#slotGrid [data-slot=\"s8\"] [data-act=\"load\"]");
        check(emptyLoad != null && emptyLoad.hasAttribute("disabled"),
                "the load button of an empty slot should be disabled, got " + emptyLoad);

        // 背包快照：默认折叠，只铺一行快捷栏（9 格）；点标题上那个箭头才展开成完整背包
        int cells = document.querySelectorAll("#detailBody .inventory-grid .slot").size();
        int items = document.querySelectorAll("#detailBody item").size();
        check(cells == HOTBAR_CELLS,
                "the collapsed inventory should show just the hotbar row (" + HOTBAR_CELLS + " cells), got " + cells);
        check(items > 0, "the inventory snapshot should be rendered as <item> elements, got " + items);
        Element inventoryTitle = document.querySelector("#detailBody .inv-title");
        check(inventoryTitle != null, "the inventory title should be clickable");
        if (inventoryTitle != null) {
            inventoryTitle.click();
            int expanded = document.querySelectorAll("#detailBody .inventory-grid .slot").size();
            check(expanded >= MAIN_INVENTORY_CELLS,
                    "expanding should show the whole inventory (" + MAIN_INVENTORY_CELLS + "+ cells), got " + expanded);
            Element caret = document.querySelector("#detailBody .inv-caret");
            check(caret != null && caret.getTextContent().contains("▼"),
                    "the expanded caret should point down, got: " + (caret == null ? "none" : caret.getTextContent()));
            Element again = document.querySelector("#detailBody .inv-title");
            if (again != null) {
                again.click();
            }
            check(document.querySelectorAll("#detailBody .inventory-grid .slot").size() == HOTBAR_CELLS,
                    "clicking the title again should collapse it back to the hotbar row");
        }

        // 点另一张卡片：详情面板要跟着换
        Element emptyCard = document.querySelector("#slotGrid [data-slot=\"s8\"]");
        if (emptyCard != null) {
            emptyCard.click();
            check(document.querySelector("#detailBody .empty-note") != null,
                    "selecting an empty slot should show the empty note in the detail panel");
            Element backToQuick = document.querySelector("#specialGrid [data-slot=\"" + Rewind.SLOT + "\"]");
            if (backToQuick != null) {
                backToQuick.click();
            }
        }

        // 保存时间只显示绝对时间（2026-09-30 00:18），不带「（15 分钟前）」那种补充
        String savedAtLabel = Component.translatable("rewind.ui.detail.saved_at").getString();
        Element savedAtRow = null;
        for (Element row : document.querySelectorAll("#detailBody .list-group-item")) {
            if (row.getTextContent().trim().startsWith(savedAtLabel)) {
                savedAtRow = row;
            }
        }
        check(savedAtRow != null, "the detail panel should show the saved-at row, got "
                + document.querySelectorAll("#detailBody .list-group-item").size() + " rows");
        if (savedAtRow != null) {
            String value = savedAtRow.getTextContent().substring(savedAtLabel.length()).trim();
            check(!savedAtRow.getTextContent().contains("（"),
                    "the saved-at row should carry the absolute time only, got: " + value);
            check(value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                    "the saved-at row should look like 2026-09-30 00:18, got: " + value);
        }

        verifyTreeSettings(document);
        verifyAutoCard(document);
        verifyAutoSettings(document);
        verifyCover(document);
        // 最后才做这一趟：它会把界面换成原版屏再换回来，回来时文档是重建过的（见方法注释）
        verifyQuickKeys(tree);
        Rewind.LOGGER.info("Rewind self-test: time tree verified (special={}, manual={}, chips={}, cells={}, items={})",
                special.size(), manual.size(), chips.size(), cells, items);
    }

    /** 切到「节点树布局」：点标题旁边那个开关。 */
    private static void openTimeline(RewindTreeScreen tree) {
        Document document = tree.getLinkedDocument();
        check(document != null, "the tree screen has no document when opening the timeline");
        if (document == null) {
            return;
        }
        Element toggle = document.querySelector(".layout-switch [data-act=\"switch-layout\"]");
        check(toggle != null, "the layout switch is missing from the tree screen");
        if (toggle != null) {
            toggle.click();
        }
    }

    /**
     * 「节点树布局」：同一批存档点按 {@code parentSlot} 拼成的时间线。
     *
     * <p>查两件事：布局切换真的把页面切过去了；以及时间线上的节点、父子关系、方向、装树
     * 都跟磁盘上的数据对得上。连线本身是 CSS 画的，断言查不出来——那部分交给截图。
     */
    private static void verifyTimeline(RewindTreeScreen tree) {
        Document document = tree.getLinkedDocument();
        check(document != null, "the tree screen has no document during the timeline checks");
        if (document == null) {
            return;
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return;
        }
        Path world = RewindApi.worldRoot(server);

        // 布局切换：body 上挂 tree-mode，两套布局都在文档里，亮的是当前那套
        check(document.body.getClassList().contains("tree-mode"),
                "switching the layout should put the page in tree mode");
        Element treeView = document.querySelector("#treeView");
        Element archiveView = document.querySelector(".archive-view");
        check(treeView != null && archiveView != null, "both layouts should be in the document");
        Element activeLabel = document.querySelector(".layout-switch-label[data-layout=\"tree\"]");
        check(activeLabel != null && activeLabel.getClassList().contains("active"),
                "the label of the active layout should be highlighted");

        // 一个存档点 = 一个节点，另外树上永远多一个「当前进度」
        Map<String, SnapshotMeta> metas = RewindApi.describeAll(world);
        List<Element> nodes = document.querySelectorAll("#treeChart .tree-node");
        List<Element> cards = document.querySelectorAll("#treeChart .tree-card");
        List<Element> nowCards = document.querySelectorAll("#treeChart .now-card");
        int expectedNodes = metas.size() + 1;
        check(nodes.size() == expectedNodes,
                "the timeline should show every checkpoint plus the current-position node, got " + nodes.size()
                        + " nodes for " + metas.size() + " checkpoints");
        check(cards.size() == expectedNodes, "each checkpoint should be one node, got " + cards.size());
        check(nowCards.size() == 1, "the timeline should always carry exactly one current-position node, got "
                + nowCards.size());
        List<Element> roots = document.querySelectorAll("#treeChart > .tree-branch > .tree-node");
        check(roots.size() == 1, "the timeline should hang off a single root, got " + roots.size());

        // 关系：第一个存档点（快速）是根，跟着原版自动保存建的那个挂在它下面
        SnapshotMeta quick = RewindApi.describe(world, Rewind.SLOT);
        SnapshotMeta auto = RewindApi.describe(world, SnapshotLayout.SLOT_AUTO);
        check(quick != null && quick.parentSlot.isEmpty(),
                "the first checkpoint should be the root of the timeline, got parent="
                        + (quick == null ? "absent" : quick.parentSlot));
        check(auto != null && Rewind.SLOT.equals(auto.parentSlot),
                "the auto checkpoint should derive from the quick one, got parent="
                        + (auto == null ? "absent" : auto.parentSlot));
        Element rootCard = document.querySelector("#treeChart > .tree-branch > .tree-node > .tree-card");
        String rootSlot = rootCard == null ? null : rootCard.getDataset().get("slot");
        check(Rewind.SLOT.equals(rootSlot),
                "the root node should be the quick checkpoint, got " + rootSlot);

        // 「当前进度」必须挂在时间线的头下面。这一轮里头是**自动存档**：它建在快速存档之后
        // （VERIFY_AUTO 里那次「跟着原版自动保存建点」），所以世界现在站在自动存档上。
        String head = RewindApi.currentSlot(world);
        check(SnapshotLayout.SLOT_AUTO.equals(head),
                "the timeline head should be the auto slot - it was the checkpoint written last, got \"" + head + "\"");
        if (nowCards.size() == 1) {
            Element nowNode = nowCards.get(0).closest(".tree-node");
            Element branch = nowNode == null ? null : nowNode.getParentElement();
            Element headNode = branch == null ? null : branch.getParentElement();
            Element headCard = headNode == null ? null : headNode.querySelector(".tree-card");
            String attachedTo = headCard == null ? null : headCard.getDataset().get("slot");
            check(head.equals(attachedTo),
                    "the current-position node should hang under the timeline head, expected \"" + head
                            + "\", got \"" + attachedTo + "\"");
        }

        // 方向：默认从上到下，点一下顺时针转 90°，按钮上的文字跟着换
        check(treeView != null && "down".equals(treeView.getAttribute("data-dir")),
                "the timeline should start top-to-bottom, got "
                        + (treeView == null ? "no #treeView" : treeView.getAttribute("data-dir")));
        Element rotate = document.querySelector("#treeView [data-act=\"rotate-tree\"]");
        check(rotate != null, "the timeline should offer a rotate button");
        if (rotate != null && treeView != null) {
            rotate.click();
            check("right".equals(treeView.getAttribute("data-dir")),
                    "one rotation should turn the timeline to left-to-right, got " + treeView.getAttribute("data-dir"));
            Element label = document.querySelector(".rotate-label");
            String expected = Component.translatable("rewind.ui.tree.dir.right").getString();
            check(label != null && label.getTextContent().contains(expected),
                    "the rotate button should name the current direction, got "
                            + (label == null ? "no .rotate-label" : label.getTextContent()));
            // 转回从上到下：后面那张截图给人看的，方向别是歪的
            rotate.click();
            rotate.click();
            rotate.click();
        }

        // 装树：画布的宽度高度算得出来之后，fitFlow 会给 flowWorld 写上 translate/scale
        Element flowWorld = document.querySelector("#flowWorld");
        check(flowWorld != null, "the flow canvas should have a world container");
        String transform = flowWorld == null ? null : flowWorld.getInlineStylePropertyValue("transform");
        check(transform != null && transform.startsWith("translate(") && transform.contains("scale("),
                "fitting the timeline should translate/scale the flow world, got: " + transform);

        // 点时间线上的节点，详情面板要跟着换
        Element autoCard = document.querySelector("#treeChart [data-slot=\"" + SnapshotLayout.SLOT_AUTO + "\"]");
        check(autoCard != null, "the timeline should hold the auto checkpoint as a node");
        if (autoCard != null) {
            autoCard.click();
            Element badge = document.querySelector("#detailBadge");
            String expected = Component.translatable("rewind.ui.slot.auto").getString();
            check(badge != null && expected.equals(badge.getTextContent().trim()),
                    "selecting a timeline node should move the detail panel to it, expected \"" + expected
                            + "\", got \"" + (badge == null ? "no #detailBadge" : badge.getTextContent()) + "\"");
        }
        Rewind.LOGGER.info("Rewind self-test: timeline verified (nodes={}, root={}, transform={})",
                nodes.size(), rootSlot, transform);
    }

    /** 「自动存档」那张卡：跟着原版自动保存建点之后，卡片上应该有内容（不是空槽位的样子）。 */
    private static void verifyAutoCard(Document document) {
        Element card = document.querySelector("#specialGrid [data-slot=\"" + SnapshotLayout.SLOT_AUTO + "\"]");
        check(card != null, "the auto slot card is missing");
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (card == null || server == null) {
            return;
        }
        String worldName = server.getWorldData().getLevelName();
        check(card.getTextContent().contains(worldName),
                "the auto card should show the checkpoint the vanilla autosave wrote, got: " + card.getTextContent());
        // 自动建的那次也该抓封面（封面是建点的人请求的，跟是谁触发的无关）
        Path world = RewindApi.worldRoot(server);
        SnapshotMeta auto = RewindApi.describe(world, SnapshotLayout.SLOT_AUTO);
        check(auto != null && CoverCapture.hasCover(String.valueOf(world.getFileName()),
                        SnapshotLayout.SLOT_AUTO, auto.savedAtMillis),
                "the auto checkpoint should have captured a cover image too");
    }

    /** 封面：建点时抓的那张图应该在，而且卡片上真的拿它当 background-image 用了。 */
    private static void verifyCover(Document document) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return;
        }
        Path world = RewindApi.worldRoot(server);
        String worldDir = String.valueOf(world.getFileName());
        SnapshotMeta meta = RewindApi.describe(world, Rewind.SLOT);
        check(meta != null, "the quick slot should be on disk before checking the cover");
        if (meta == null) {
            return;
        }
        check(CoverCapture.hasCover(worldDir, Rewind.SLOT, meta.savedAtMillis),
                "the checkpoint should have captured a cover image");
        Element cover = document.querySelector("#specialGrid [data-slot=\"" + Rewind.SLOT + "\"] .special-cover");
        check(cover != null, "the quick card should have a cover area");
        if (cover != null) {
            String marker = Rewind.SLOT + "-" + meta.savedAtMillis + ".png";
            Element shot = cover.querySelector("img.cover-shot");
            check(shot != null, "the cover area should hold the screenshot <img>");
            if (shot != null) {
                String src = shot.getAttribute("src");
                check(src != null && src.contains(marker),
                        "the cover <img> should point at the screenshot, got: " + src);
                Rewind.LOGGER.info("Rewind self-test: cover img src={} ready={}", src,
                        src == null ? "n/a"
                                : String.valueOf(ImageDrawer.isTextureReady(
                                        Loader.resolve(document.getPath(), src), shot)));
            }
        }
        Rewind.LOGGER.info("Rewind self-test: cover verified ({})",
                CoverCapture.coverUrl(worldDir, Rewind.SLOT, meta.savedAtMillis));
    }

    /** 「自动存档」卡的设置：那个开关能翻，而且真的写进配置（翻完再翻回来）。 */
    private static void verifyAutoSettings(Document document) {
        Element settings = document.querySelector(
                "#specialGrid [data-slot=\"" + SnapshotLayout.SLOT_AUTO + "\"] [data-act=\"settings\"]");
        check(settings != null, "the auto card should offer a settings button");
        if (settings == null) {
            return;
        }
        boolean before = RewindServerConfig.autoCheckpointEnabled();
        settings.click();
        Element toggle = document.querySelector("#modalSettings [data-act=\"toggle-auto\"]");
        check(toggle != null, "the auto settings should offer the follow-autosave toggle");
        if (toggle == null) {
            return;
        }

        // 第二个配置项：自动保存间隔。它只在「跟随」开着时作用到原版上
        Element interval = document.querySelector("#modalSettings #autoInterval");
        Element apply = document.querySelector("#modalSettings [data-act=\"apply-interval\"]");
        check(interval != null && apply != null,
                "the auto settings should offer the autosave interval (input=" + interval + " apply=" + apply + ")");
        if (interval != null && apply != null) {
            check(RewindServerConfig.autoSaveIntervalMinutes() == RewindServerConfig.DEFAULT_AUTO_SAVE_INTERVAL_MINUTES,
                    "the autosave interval should default to the vanilla "
                            + RewindServerConfig.DEFAULT_AUTO_SAVE_INTERVAL_MINUTES + " minutes, got "
                            + RewindServerConfig.autoSaveIntervalMinutes());
            // 注意这里只测**比默认值大**的间隔：改成比 5 分钟小会把原版的自动保存倒计时压下来
            // （见 AutoCheckpointMixins 里那个 clamp），这一轮后面就可能凭空插一次真自动保存，
            // 把回溯的耗时断言搅黄。小间隔的换算在下面按「分钟 × TICKS_PER_MINUTE」直接查。
            interval.setValue("30");
            apply.click();
            check(RewindServerConfig.autoSaveIntervalMinutes() == 30,
                    "applying the interval should store 30 minutes, got "
                            + RewindServerConfig.autoSaveIntervalMinutes());
            check(RewindServerConfig.autoSaveIntervalTicks() == 30 * RewindServerConfig.TICKS_PER_MINUTE,
                    "30 minutes should be " + (30 * RewindServerConfig.TICKS_PER_MINUTE) + " ticks, got "
                            + RewindServerConfig.autoSaveIntervalTicks());
            // 夹范围：超出上限会被夹回来（同样不碰倒计时）
            Element clamped = document.querySelector("#modalSettings #autoInterval");
            Element applyClamped = document.querySelector("#modalSettings [data-act=\"apply-interval\"]");
            if (clamped != null && applyClamped != null) {
                clamped.setValue("99");
                applyClamped.click();
                check(RewindServerConfig.autoSaveIntervalMinutes() == RewindServerConfig.MAX_AUTO_SAVE_INTERVAL_MINUTES,
                        "an out-of-range interval should be clamped to "
                                + RewindServerConfig.MAX_AUTO_SAVE_INTERVAL_MINUTES + ", got "
                                + RewindServerConfig.autoSaveIntervalMinutes());
            }
        }

        // 上面那几次「应用」会重开弹窗，弹窗里的节点全是新铺的：之前抓住的那个 toggle 已经不在文档里了
        Element toggleNow = document.querySelector("#modalSettings [data-act=\"toggle-auto\"]");
        check(toggleNow != null, "the follow-autosave toggle is missing after applying the interval");
        if (toggleNow == null) {
            return;
        }
        toggleNow.click();
        check(RewindServerConfig.autoCheckpointEnabled() != before,
                "clicking the toggle should flip the follow-autosave setting");
        // 关着的时候间隔不该碰原版（0 = 让原版按自己的 5 分钟走）
        check(RewindServerConfig.autoCheckpointEnabled()
                        ? RewindServerConfig.autoSaveIntervalTicks() == RewindServerConfig.autoSaveIntervalMinutes()
                                * RewindServerConfig.TICKS_PER_MINUTE
                        : RewindServerConfig.autoSaveIntervalTicks() == 0,
                "the autosave interval should only reach vanilla while the toggle is on, got "
                        + RewindServerConfig.autoSaveIntervalTicks() + " ticks");
        Element again = document.querySelector("#modalSettings [data-act=\"toggle-auto\"]");
        if (again != null) {
            again.click();
        }
        check(RewindServerConfig.autoCheckpointEnabled() == before,
                "the toggle should flip back, leaving the setting as it was");
        // 把间隔改回默认值：后面几轮还要靠原版自动保存建点，别把它的节奏改了
        RewindServerConfig.setAutoSaveIntervalMinutes(RewindServerConfig.DEFAULT_AUTO_SAVE_INTERVAL_MINUTES);
        Element close = document.querySelector("#modalSettings [data-act=\"modal-close\"]");
        if (close != null) {
            close.click();
        }
    }

    /**
     * 特殊槽位的「设置」：自动 / 快速是固定角色的槽位，没有名字可改，第三个按钮是设置。
     *
     * <p>这里只查接线：按钮在、弹窗能开、里面有内容、能关掉。
     */
    private static void verifyTreeSettings(Document document) {
        String card = "#specialGrid [data-slot=\"" + Rewind.SLOT + "\"] ";
        Element settings = document.querySelector(card + "[data-act=\"settings\"]");
        check(settings != null, "the special cards should offer a settings button");
        check(document.querySelector(card + "[data-act=\"rename\"]") == null,
                "the special cards should not offer renaming");
        if (settings == null) {
            return;
        }
        settings.click();
        check(modalOpen(document, "modalSettings"), "clicking settings should open the settings modal");
        Element info = document.querySelector("#settingsInfo");
        check(info != null && !info.getTextContent().isBlank(), "the settings modal should describe the slot");

        // 快速槽的三个设置项：两条过渡强度滑块（主题的 .slider）+ 改键入口
        List<Element> sliders = document.querySelectorAll("#modalSettings #settingsInfo .slider");
        check(sliders.size() == 2, "the quick settings should offer both transition sliders, got " + sliders.size());
        Element saturation = document.querySelector("#modalSettings #saturationBoost");
        Element blur = document.querySelector("#modalSettings #blurRadius");
        check(saturation != null && blur != null, "the transition sliders should carry their ids");
        if (saturation != null) {
            // 滑块画出来的位置要和配置里的值对得上（process 的宽度就是它）
            Element process = saturation.querySelector(".slider-process");
            double shown = process == null ? Double.NaN : parsePercent(process.getInlineStylePropertyValue("width"));
            double expected = RewindClientConfig.saturationBoost() / RewindClientConfig.MAX_SATURATION_BOOST * 100.0D;
            check(Math.abs(shown - expected) < 1.0D,
                    "the saturation slider should show the current config value, got " + shown + "% vs " + expected + "%");
            // 像玩家那样拖一下：按下 → 松手。位置取轨道正中 = 量程中点
            Element.DOMRect rect = saturation.getBoundingClientRect();
            check(rect.width > 0.0D, "the saturation slider has no width while the modal is open");
            float before = RewindClientConfig.saturationBoost();
            if (rect.width > 0.0D) {
                double middle = rect.x + rect.width / 2.0D;
                saturation.dispatchEvent(new MouseEvent("mousedown", new Position(middle, rect.y), 0));
                check(Math.abs(RewindClientConfig.saturationBoost()
                                - RewindClientConfig.MAX_SATURATION_BOOST / 2.0F) < 0.2F,
                        "dragging the saturation slider should preview the mid value, got "
                                + RewindClientConfig.saturationBoost());
                saturation.dispatchEvent(new MouseEvent("mouseup", new Position(middle, rect.y), 0));
                check(Math.abs(RewindClientConfig.saturationBoost()
                                - RewindClientConfig.MAX_SATURATION_BOOST / 2.0F) < 0.2F,
                        "releasing the saturation slider should commit the value, got "
                                + RewindClientConfig.saturationBoost());
            }
            RewindClientConfig.commitSaturationBoost(before);
            check(Math.abs(RewindClientConfig.saturationBoost() - before) < 0.001F,
                    "the saturation should be back where it started, got " + RewindClientConfig.saturationBoost());
        }
        if (blur != null) {
            float before = RewindClientConfig.blurRadius();
            RewindClientConfig.commitBlurRadius(blur == null ? before : before + 1.0F);
            check(Math.abs(RewindClientConfig.blurRadius() - (before + 1.0F)) < 0.001F,
                    "the blur radius should be settable through the config, got " + RewindClientConfig.blurRadius());
            RewindClientConfig.commitBlurRadius(before);
        }

        Element close = document.querySelector("#modalSettings [data-act=\"modal-close\"]");
        check(close != null, "the settings modal has no close button");
        if (close != null) {
            close.click();
        }
        check(!modalOpen(document, "modalSettings"), "closing should hide the settings modal");
    }

    /**
     * 「改键」那个按钮：点下去该开一个只列 Rewind 两个热键的按键绑定页（本质是原版那一页，
     * 列表被 {@code KeyBindsListMixins} 筛过）。
     *
     * <p>放在 {@code verifyTree} 的最后跑：这一趟会把界面换成原版屏再换回来，换回来时时间树的文档
     * 是重建过的——之前抓的那些节点引用全作废，所以后面的断言都得重新取。
     */
    private static void verifyQuickKeys(RewindTreeScreen tree) {
        Document document = tree.getLinkedDocument();
        if (document == null) {
            return;
        }
        Element settings = document.querySelector(
                "#specialGrid [data-slot=\"" + Rewind.SLOT + "\"] [data-act=\"settings\"]");
        if (settings == null) {
            return;
        }
        settings.click();
        Element keys = document.querySelector("#modalSettings [data-act=\"open-keys\"]");
        check(keys != null, "the quick settings should offer a rebind button");
        if (keys == null) {
            return;
        }
        keys.click();
        Minecraft minecraft = Minecraft.getInstance();
        Screen opened = minecraft.screen;
        check(opened instanceof RewindKeyBindsScreen,
                "the rebind button should open the filtered key binds screen, got " + opened);
        if (opened instanceof RewindKeyBindsScreen) {
            KeyBindsList list = ((KeyBindsListMixins.KeyBindsScreenAccess) opened).rewind$list();
            int rows = list == null ? -1 : list.children().size();
            long keyRows = list == null ? -1L : list.children().stream()
                    .filter(entry -> entry instanceof KeyBindsList.KeyEntry)
                    .count();
            // 一条分类标题 + 两条按键
            check(rows == 3 && keyRows == 2,
                    "the key binds screen should list only the two quick-save hotkeys, got " + rows
                            + " row(s), " + keyRows + " of them key bindings");
            Rewind.LOGGER.info("Rewind self-test: the filtered key binds screen has {} row(s), {} key binding(s)",
                    rows, keyRows);
        }
        // 换回时间树：不换回去，这一轮剩下的阶段会一直等不到界面
        minecraft.setScreen(tree);
    }

    private static float parseFloat(String raw) {
        try {
            return Float.parseFloat(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /** {@code "19.0%"} → {@code 19.0}；解析不了给 NaN。 */
    private static double parsePercent(String raw) {
        try {
            return Double.parseDouble(raw == null ? "" : raw.trim().replace("%", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * 跑一次「原版自动保存」：参数与 {@code MinecraftServer.tickServer} 里那一句完全一致，
     * 所以走的就是挂点认出来的那条路。
     */
    private static void runAutosave(IntegratedServer server) {
        server.submit(() -> server.saveEverything(true, false, false));
    }

    /** 在探针槽位建一个存档点：界面上的覆盖 / 删除要有靶子。 */
    private static void writeProbeSlot(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            fail("the integrated server is gone before the management checks");
            return;
        }
        try {
            RewindResult result = server
                    .submit(() -> RewindApi.createCheckpoint(server, PROBE_SLOT, "selftest"))
                    .get(30, TimeUnit.SECONDS);
            check(result.success, "writing the probe slot " + PROBE_SLOT + " failed: " + result.failure);
            Rewind.LOGGER.info("Rewind self-test: probe slot {} written ({})", PROBE_SLOT, result.summary);
        } catch (Exception e) {
            fail("writing the probe slot " + PROBE_SLOT + " threw: " + e);
        }
    }

    /**
     * 界面上的覆盖 / 删除。
     *
     * <p>删除是就地做的（纯文件操作，走 {@code RewindApi.deleteCheckpoint}），所以能一路查到文件没了；
     * 覆盖只是把请求转给 F7 那条带过渡的管线，这里查到「确认弹窗弹出来、取消能收掉」为止——
     * 真的跑一遍过渡会和后面的断言抢时序，没有意义（F7 本身已经被前几轮覆盖了）。
     */
    private static void verifyTreeManage(RewindTreeScreen tree) {
        Document document = tree.getLinkedDocument();
        check(document != null, "the tree screen has no document during the management checks");
        if (document == null) {
            return;
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return;
        }
        Path world = RewindApi.worldRoot(server);
        check(RewindApi.hasCheckpoint(world, PROBE_SLOT), "the probe slot " + PROBE_SLOT + " should be on disk");
        // 探针是在回溯到快速存档之后建的：时间线的「头」那时在快速存档上，所以它的父节点就该是它
        SnapshotMeta probe = RewindApi.describe(world, PROBE_SLOT);
        check(probe != null && Rewind.SLOT.equals(probe.parentSlot),
                "a checkpoint written after rewinding to the quick slot should derive from it, got parent="
                        + (probe == null ? "absent" : probe.parentSlot));

        Element probeCard = document.querySelector("#slotGrid [data-slot=\"" + PROBE_SLOT + "\"]");
        check(probeCard != null, "the probe slot card is missing from the tree");
        if (probeCard == null) {
            return;
        }
        // 手动卡片显示的是「多久之前 · 生物群系 · 大小」（世界名只在特殊卡上），所以这里查结构：
        // 有存档点的卡片不会是空槽位那种封面，而且会多出一个时钟
        check(probeCard.querySelector(".slot-cover.empty") == null && probeCard.querySelector(".slot-clock") != null,
                "the probe card should render the checkpoint, got: " + probeCard.getTextContent());

        // 重命名：只有手动槽位有这个按钮（自动 / 快速是固定角色，第三个按钮是设置）
        Element rename = document.querySelector("#slotGrid [data-slot=\"" + PROBE_SLOT + "\"] [data-act=\"rename\"]");
        Element nameInput = document.querySelector("#renameInput");
        Element renameOk = document.querySelector("[data-act=\"rename-ok\"]");
        check(rename != null && nameInput != null && renameOk != null,
                "the probe card should offer renaming (rename=" + rename + " input=" + nameInput + " ok=" + renameOk + ")");
        if (rename != null && nameInput != null && renameOk != null) {
            rename.click();
            nameInput.setValue(RENAME_PROBE);
            renameOk.click();
            SnapshotMeta renamed = RewindApi.describe(world, PROBE_SLOT);
            check(renamed != null && RENAME_PROBE.equals(renamed.displayName),
                    "renaming through the tree did not stick, the index says: "
                            + (renamed == null ? "absent" : renamed.displayName));
        }

        // 删除：先选中探针槽位（删除按钮只在详情面板上），再确认
        Element select = document.querySelector("#slotGrid [data-slot=\"" + PROBE_SLOT + "\"]");
        if (select != null) {
            select.click();
        }
        Element delete = document.querySelector("#detailBody [data-act=\"delete\"]");
        check(delete != null, "the detail panel should offer a delete button");
        if (delete == null) {
            return;
        }
        delete.click();
        check(modalOpen(document, "modalConfirm"), "clicking delete should open the confirm modal");
        Element confirm = document.querySelector("#confirmOk");
        check(confirm != null, "the confirm modal has no confirm button");
        if (confirm != null) {
            confirm.click();
        }
        check(!RewindApi.hasCheckpoint(world, PROBE_SLOT),
                "deleting through the tree should remove the checkpoint from the index");
        check(!Files.exists(SnapshotLayout.slotDir(world, PROBE_SLOT)),
                "deleting through the tree should remove the slot directory");
        Element afterDelete = document.querySelector("#slotGrid [data-slot=\"" + PROBE_SLOT + "\"]");
        check(afterDelete != null && afterDelete.querySelector(".slot-cover.empty") != null,
                "after deleting, the probe card should go back to the empty state, got: "
                        + (afterDelete == null ? "missing card" : afterDelete.getTextContent()));
        Rewind.LOGGER.info("Rewind self-test: time tree management verified (rename, delete)");

        // 最后：从界面里覆盖一次（探针槽位刚被删掉，所以这一下是新建）。**界面不许退出去**，
        // 也不放过渡，写盘在服务端线程上做；写完由调用方（stage）等出来再断言。
        Element save = document.querySelector("#slotGrid [data-slot=\"" + PROBE_SLOT + "\"] [data-act=\"save\"]");
        check(save != null, "the overwrite button is missing on the probe card");
        if (save == null) {
            return;
        }
        save.click();
        check(modalOpen(document, "modalConfirm"), "clicking overwrite should open the confirm modal");
        Element confirmSave = document.querySelector("#confirmOk");
        check(confirmSave != null, "the confirm modal has no confirm button");
        if (confirmSave != null) {
            confirmSave.click();
        }
        check(!modalOpen(document, "modalConfirm"), "confirming should close the confirm modal");
        check(Minecraft.getInstance().screen instanceof RewindTreeScreen,
                "overwriting from the tree must keep the tree open, but the screen is "
                        + Minecraft.getInstance().screen);
        check(!RewindTransition.isActive(), "overwriting from the tree must not start a transition");
        Rewind.LOGGER.info("Rewind self-test: overwrite from the tree started, waiting for the background write");
    }

    private static boolean modalOpen(Document document, String id) {
        Element modal = document.querySelector("#" + id);
        return modal != null && modal.getClassList().contains("open");
    }

    /**
     * 给「时间树」存一张截图（{@code run/screenshots/<名字>.png}）。
     *
     * <p>结构对不对能用断言查，长什么样查不出来——留张图给人眼（或视觉模型）确认。这一刻读的是
     * 主渲染目标里上一帧的内容，而上一帧正是画着这个界面的那一帧。
     */
    private static void screenshotTree(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.getMainRenderTarget(), component -> {
        });
        Rewind.LOGGER.info("Rewind self-test: saved the tree screenshot to run/screenshots/{}.png", name);
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
            // 客户端的快捷栏选中槽位：服务端那份由 player.load 回滚，客户端这份得靠服务端推过来
            state.selectedSlot = inventory.selected;
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
        int extra = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity.getTags().contains(ENTITY_TAG)) {
                stands++;
                state.standX = entity.getX();
                state.standY = entity.getY();
                state.standZ = entity.getZ();
            }
            if (entity.getTags().contains(EXTRA_TAG)) {
                extra++;
            }
        }
        state.stands = stands;
        state.extra = extra;
        return state;
    }

    // ------------------------------------------------------------------ 基础设施

    /** 操作真的开跑之后确认一下：从界面里按下去的那一下，界面已经被收掉了。 */
    private static void checkGuiClosed(Minecraft minecraft, String what) {
        if (guiCloseChecked) {
            return;
        }
        guiCloseChecked = true;
        check(minecraft.screen == null,
                "pressing " + what + " from a GUI should close that GUI, but " + minecraft.screen + " is still open");
    }

    /**
     * 模拟一次真实按键：**先打开一个界面**，再投递 NeoForge 的原始按键事件。
     *
     * <p>走这条路的理由：原版只在没有界面时才累计 {@code KeyMapping} 的点击
     * （{@code KeyboardHandler} 里 {@code flag4 = screen == null}），所以「GUI 里也能生效」这件事
     * 必须靠原始输入事件来验证。用 {@code NeoForge.EVENT_BUS.post} 投递，走的就是真实注册的那个监听器。
     */
    private static void pressKeyFromGui(Minecraft minecraft, int keyCode, String what) {
        if (minecraft.player != null) {
            minecraft.setScreen(new InventoryScreen(minecraft.player));
        }
        Rewind.LOGGER.info("Rewind self-test: opened a GUI, then pressing {} via InputEvent.Key (screen={})",
                what, minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName());
        NeoForge.EVENT_BUS.post(new InputEvent.Key(keyCode, 0, InputConstants.PRESS, 0));
    }

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
        KeyMapping tree = RewindClient.treeKey();
        Rewind.LOGGER.info("Rewind self-test: keybindings snapshot={} key={} restore={} key={} tree={} key={}",
                snapshot == null ? "MISSING" : snapshot.getName(),
                snapshot == null ? "-" : String.valueOf(snapshot.getKey().getValue()),
                restore == null ? "MISSING" : restore.getName(),
                restore == null ? "-" : String.valueOf(restore.getKey().getValue()),
                tree == null ? "MISSING" : tree.getName(),
                tree == null ? "-" : String.valueOf(tree.getKey().getValue()));
    }

    private static void goTo(Stage next) {
        Rewind.LOGGER.info("Rewind self-test: stage {} -> {}", stage, next);
        stage = next;
        stageTicks = 0;
        guiCloseChecked = false;
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
        /** 建点之后才召唤的实体数量，回档后必须是 0。 */
        private int extra = -1;
        private double standX;
        private double standY;
        private double standZ;
        private double px;
        private double py;
        private double pz;
        private int diamonds = -1;
        /** 客户端的快捷栏选中槽位（原地回滚后必须和建点时的那个一致）。 */
        private int selectedSlot = -1;
        private long dayTime;

        private String describe() {
            return "block=" + block
                    + " score=" + score
                    + " stands=" + stands
                    + " standPos=" + String.format(Locale.ROOT, "%.2f/%.2f/%.2f", standX, standY, standZ)
                    + " extra=" + extra
                    + " player=" + String.format(Locale.ROOT, "%.2f/%.2f/%.2f", px, py, pz)
                    + " diamonds=" + diamonds
                    + " selectedSlot=" + selectedSlot
                    + " dayTime=" + dayTime;
        }
    }
}
