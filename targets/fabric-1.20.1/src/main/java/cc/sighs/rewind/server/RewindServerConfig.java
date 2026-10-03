package cc.sighs.rewind.server;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.common.config.AutoCheckpointSettings;
import cc.sighs.rewind.common.config.RollbackSettings;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 服务端行为的配置，落在 {@code config/rewind-common.properties}。
 *
 * <p>目前分两组：{@code autoCheckpoint}（跟着原版自动保存建点、间隔分钟数）与 {@code rollback}
 * （回溯时要不要重发配方书、要不要只同步读回玩家视野内的区块）。它跟玩家有没有客户端没关系，两边都要有。
 *
 * <p>默认值、可调范围与「分钟 → tick」的换算都在 common 的 {@link AutoCheckpointSettings} 里；
 * 本类只负责那一个 properties 文件的读写与落盘，界面/Mixin 读到的都是那一份共享值。
 *
 * <p>Fabric 没有内置的配置系统（NeoForge 那边是 {@code ModConfigSpec}），所以这里用一个最小的
 * {@link Properties} 实现顶上。键名与主版本保持一致（{@code autoCheckpoint.enabled} /
 * {@code autoCheckpoint.intervalMinutes}），只是文件格式从 TOML 换成了 properties。
 */
public final class RewindServerConfig {
    /** 「跟着原版自动保存建点」的默认值。 */
    public static final boolean DEFAULT_AUTO_CHECKPOINT = AutoCheckpointSettings.DEFAULT_AUTO_CHECKPOINT;
    /** 原版自动保存间隔的默认值（分钟）——原版自己就是 5 分钟。 */
    public static final int DEFAULT_AUTO_SAVE_INTERVAL_MINUTES = AutoCheckpointSettings.DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
    /** 自动保存间隔的可调范围（分钟）。 */
    public static final int MIN_AUTO_SAVE_INTERVAL_MINUTES = AutoCheckpointSettings.MIN_AUTO_SAVE_INTERVAL_MINUTES;
    public static final int MAX_AUTO_SAVE_INTERVAL_MINUTES = AutoCheckpointSettings.MAX_AUTO_SAVE_INTERVAL_MINUTES;
    /** 一分钟多少 tick（原版 20 tps）；{@link #autoSaveIntervalTicks()} 就是「分钟数 × 这个」。 */
    public static final int TICKS_PER_MINUTE = AutoCheckpointSettings.TICKS_PER_MINUTE;

    private static final String FILE_NAME = "rewind-common.properties";
    private static final String KEY_ENABLED = "autoCheckpoint.enabled";
    private static final String KEY_INTERVAL_MINUTES = "autoCheckpoint.intervalMinutes";
    private static final String KEY_SYNC_RECIPE_BOOK = "rollback.syncRecipeBook";
    private static final String KEY_SYNC_CHUNKS_NEAR_PLAYER = "rollback.syncChunksNearPlayer";
    /** 写进文件开头的说明；一行一条，写的时候前面各加一个 {@code # }。 */
    private static final List<String> HEADER_LINES = Arrays.asList(
            "Rewind 的服务端行为。",
            KEY_ENABLED + "：跟着原版自动保存建点。每次自动保存完往「自动存档」槽位写一个存档点，",
            "  代价是那一下会有几十到几百毫秒的卡顿，嫌卡就改成 false。",
            KEY_INTERVAL_MINUTES + "：原版自动保存的间隔（分钟，"
                    + MIN_AUTO_SAVE_INTERVAL_MINUTES + "-" + MAX_AUTO_SAVE_INTERVAL_MINUTES + "）。",
            "  上面的开关打开时它直接改原版的自动保存间隔（原版自己固定 5 分钟）；关掉时这个值不生效。",
            KEY_SYNC_RECIPE_BOOK + "：回溯时要不要重新同步玩家的配方书（发给客户端那一份）。",
            "  默认 false：完全不碰配方书——回滚不给客户端重发这份包，客户端停在回溯前的状态；",
            "  服务端那份仍随 playerdata 一起回滚（它是玩家数据的一部分），下次登录 / 重连会自然对齐。",
            "  开着才走 sendInitialRecipeBook 把整份重发一遍——大整合包里这一下可能几百毫秒。",
            KEY_SYNC_CHUNKS_NEAR_PLAYER + "：回溯时只同步读回「玩家视野内」的受影响区块，其余交给原版流水线按 ticket 异步读回来。",
            "  默认 true。关掉则回到老行为：所有受影响区块都在回滚这一帧里同步读回来（回溯更慢，但结束后世界立即完整）。");

    /** 配置文件里那一份值：{@link #setAutoCheckpointEnabled} / {@link #setAutoSaveIntervalMinutes} 落盘时写的就是它。 */
    private static volatile boolean storedAutoCheckpointEnabled = DEFAULT_AUTO_CHECKPOINT;
    private static volatile int storedAutoSaveIntervalMinutes = DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
    /** 回溯行为的两份值：{@link #setSyncRecipeBook} / {@link #setSyncChunksNearPlayer} 落盘时写的就是它们。 */
    private static volatile boolean storedSyncRecipeBook = RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK;
    private static volatile boolean storedSyncChunksNearPlayer = RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;

    private RewindServerConfig() {
    }

    /**
     * 读配置。必须在模组构造阶段调用，否则 Mixin 里的自动建点会赶不上第一轮自动保存。
     *
     * <p>文件不存在时写一份默认值出来（玩家一眼就能看到有什么可改的）；读不动就用默认值顶上，
     * 不让一次配置文件的 I/O 失败挡住整个模组初始化。
     */
    public static void register() {
        Path file = configFile();
        boolean enabled = DEFAULT_AUTO_CHECKPOINT;
        int minutes = DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
        boolean recipeBook = RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK;
        boolean chunksNearPlayer = RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;
        try {
            if (file != null && Files.isRegularFile(file)) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                enabled = parseBoolean(properties.getProperty(KEY_ENABLED), DEFAULT_AUTO_CHECKPOINT);
                minutes = parseMinutes(properties.getProperty(KEY_INTERVAL_MINUTES));
                recipeBook = parseBoolean(properties.getProperty(KEY_SYNC_RECIPE_BOOK),
                        RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK);
                chunksNearPlayer = parseBoolean(properties.getProperty(KEY_SYNC_CHUNKS_NEAR_PLAYER),
                        RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER);
            }
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to read {}; falling back to the defaults", file, t);
            enabled = DEFAULT_AUTO_CHECKPOINT;
            minutes = DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
            recipeBook = RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK;
            chunksNearPlayer = RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;
        }

        storedAutoCheckpointEnabled = enabled;
        storedAutoSaveIntervalMinutes = AutoCheckpointSettings.clampMinutes(minutes);
        storedSyncRecipeBook = recipeBook;
        storedSyncChunksNearPlayer = chunksNearPlayer;
        AutoCheckpointSettings.apply(storedAutoCheckpointEnabled, storedAutoSaveIntervalMinutes);
        RollbackSettings.apply(storedSyncRecipeBook, storedSyncChunksNearPlayer);
        // 无论刚才是读出来的还是兜底出来的，都写回去一次：文件缺失时补上，值被夹过时纠正
        write();

        Rewind.LOGGER.info(
                "Rewind: auto checkpoint enabled={} interval={} min, rollback syncRecipeBook={} syncChunksNearPlayer={}",
                AutoCheckpointSettings.autoCheckpointEnabled(), AutoCheckpointSettings.autoSaveIntervalMinutes(),
                RollbackSettings.syncRecipeBook(), RollbackSettings.syncChunksNearPlayer());
    }

    /** 跟着原版自动保存建点是不是开着。 */
    public static boolean autoCheckpointEnabled() {
        return AutoCheckpointSettings.autoCheckpointEnabled();
    }

    /** 配置里的自动保存间隔（分钟，已夹到范围内）。 */
    public static int autoSaveIntervalMinutes() {
        return AutoCheckpointSettings.autoSaveIntervalMinutes();
    }

    /**
     * 原版自动保存的间隔（tick）；**0 表示不碰原版**（让它按自己的 5 分钟走）。
     *
     * <p>只有「跟着原版自动保存建点」开着的时候才返回有效值——关着的时候改这个间隔没有意义。
     * 每次原版要算下一次自动保存的间隔时都会问一次这里，所以是个普通字段读，不查配置文件。
     */
    public static int autoSaveIntervalTicks() {
        return AutoCheckpointSettings.autoSaveIntervalTicks();
    }

    /** 改这个开关并立刻落盘（界面上「自动存档」卡的「设置」按钮走这里）。 */
    public static void setAutoCheckpointEnabled(boolean enabled) {
        AutoCheckpointSettings.setAutoCheckpointEnabled(enabled);
        storedAutoCheckpointEnabled = enabled;
        write();
        Rewind.LOGGER.info("Rewind: auto checkpoint enabled={} (toggled)", enabled);
    }

    /** 改自动保存间隔（分钟）并立刻落盘。值会被夹到 {@link #MIN_AUTO_SAVE_INTERVAL_MINUTES} - {@link #MAX_AUTO_SAVE_INTERVAL_MINUTES}。 */
    public static void setAutoSaveIntervalMinutes(int minutes) {
        AutoCheckpointSettings.setAutoSaveIntervalMinutes(minutes);
        int clamped = AutoCheckpointSettings.autoSaveIntervalMinutes();
        storedAutoSaveIntervalMinutes = clamped;
        write();
        Rewind.LOGGER.info("Rewind: auto save interval = {} min ({} ticks)",
                clamped, AutoCheckpointSettings.autoSaveIntervalTicks());
    }

    /** 回溯时要不要处理配方书。 */
    public static boolean syncRecipeBook() {
        return RollbackSettings.syncRecipeBook();
    }

    /** 回溯时是不是只同步读回玩家视野内的受影响区块。 */
    public static boolean syncChunksNearPlayer() {
        return RollbackSettings.syncChunksNearPlayer();
    }

    /** 改「只同步读视野内区块」开关并立刻落盘。 */
    public static void setSyncChunksNearPlayer(boolean value) {
        RollbackSettings.setSyncChunksNearPlayer(value);
        storedSyncChunksNearPlayer = value;
        write();
        Rewind.LOGGER.info("Rewind: rollback syncChunksNearPlayer={} (toggled)", value);
    }

    /** 改这个开关并立刻落盘。 */
    public static void setSyncRecipeBook(boolean value) {
        RollbackSettings.setSyncRecipeBook(value);
        storedSyncRecipeBook = value;
        write();
        Rewind.LOGGER.info("Rewind: rollback syncRecipeBook={} (toggled)", value);
    }

    /** 配置文件的位置；取不到配置目录时返回 null（读写的调用方各自兜底）。 */
    private static Path configFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: could not resolve the config directory", t);
            return null;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static int parseMinutes(String raw) {
        if (raw == null) {
            return DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            Rewind.LOGGER.warn("Rewind: \"{}\" is not a valid auto save interval; using the default {} minutes",
                    raw, DEFAULT_AUTO_SAVE_INTERVAL_MINUTES);
            return DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
        }
    }

    /** 把内存里的两个值写回配置文件；写不动就只写一条日志（值本身已经在内存里生效了）。 */
    private static void write() {
        Path file = configFile();
        if (file == null) {
            return;
        }
        // 手写而不是 Properties.store：store 会把中文注释里的每个字转义成 Unicode 转义序列，人读不了
        List<String> lines = new ArrayList<>();
        for (String comment : HEADER_LINES) {
            lines.add("# " + comment);
        }
        lines.add(KEY_ENABLED + "=" + storedAutoCheckpointEnabled);
        lines.add(KEY_INTERVAL_MINUTES + "=" + storedAutoSaveIntervalMinutes);
        lines.add(KEY_SYNC_RECIPE_BOOK + "=" + storedSyncRecipeBook);
        lines.add(KEY_SYNC_CHUNKS_NEAR_PLAYER + "=" + storedSyncChunksNearPlayer);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to save {}", file, t);
        }
    }
}
