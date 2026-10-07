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
import cc.sighs.rewind.common.config.SlotSettings;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 服务端行为的配置，落在 {@code config/rewind-common.properties}。
 *
 * <p>目前分三组：{@code autoCheckpoint}（跟着原版自动保存建点、间隔分钟数）、{@code rollback}
 * （回溯时要不要重发配方书、要不要只同步读回玩家视野内的区块、死亡后要不要自动回溯到时间线的头，
 * 以及两次回溯之间的读档冷却）与
 * {@code slots}（界面上那排手动编号卡片 {@code s1}…{@code sN} 的数量）。它跟玩家有没有客户端
 * 没关系，两边都要有。
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
    private static final String KEY_ROLLBACK_ON_DEATH = "rollback.rollbackOnDeath";
    private static final String KEY_ROLLBACK_COOLDOWN_SECONDS = "rollback.cooldownSeconds";
    private static final String KEY_MANUAL_SLOT_COUNT = "slots.manualSlotCount";
    /** 写进文件开头的说明；一行一条，写的时候前面各加一个 {@code # }。 */
    private static final List<String> HEADER_LINES = Arrays.asList(
            "自动存档点。",
            "Auto checkpoints.",
            KEY_ENABLED + "：跟着原版自动保存建点。",
            KEY_ENABLED + ": Create a checkpoint after each vanilla autosave.",
            KEY_INTERVAL_MINUTES + "：原版自动保存间隔（分钟，1-60）。",
            KEY_INTERVAL_MINUTES + ": Vanilla autosave interval in minutes (1-60).",
            "回溯行为。",
            "Rollback behaviour.",
            KEY_SYNC_RECIPE_BOOK + "：回溯时重新同步玩家的配方书。",
            KEY_SYNC_RECIPE_BOOK + ": Re-sync the player's recipe book on rollback.",
            KEY_SYNC_CHUNKS_NEAR_PLAYER + "：回溯时只同步读回玩家视野内的区块。",
            KEY_SYNC_CHUNKS_NEAR_PLAYER + ": On rollback, sync-load only chunks near the player.",
            KEY_ROLLBACK_ON_DEATH + "：死亡后自动回溯到时间线上最近的那个节点。",
            KEY_ROLLBACK_ON_DEATH + ": Roll back to the nearest node on the timeline after death.",
            KEY_ROLLBACK_COOLDOWN_SECONDS + "：读档冷却（秒），0 = 关闭。",
            KEY_ROLLBACK_COOLDOWN_SECONDS + ": Rollback cooldown in seconds, 0 = off.",
            "槽位布局。",
            "Slot layout.",
            KEY_MANUAL_SLOT_COUNT + "：手动槽位数量。",
            KEY_MANUAL_SLOT_COUNT + ": Number of manual slots.");

    /** 配置文件里那一份值：{@link #setAutoCheckpointEnabled} / {@link #setAutoSaveIntervalMinutes} 落盘时写的就是它。 */
    private static volatile boolean storedAutoCheckpointEnabled = DEFAULT_AUTO_CHECKPOINT;
    private static volatile int storedAutoSaveIntervalMinutes = DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
    /** 回溯行为的四份值：{@link #setSyncRecipeBook} / {@link #setSyncChunksNearPlayer} / {@link #setRollbackOnDeath} / {@link #setRollbackCooldownSeconds} 落盘时写的就是它们。 */
    private static volatile boolean storedSyncRecipeBook = RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK;
    private static volatile boolean storedSyncChunksNearPlayer = RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;
    private static volatile boolean storedRollbackOnDeath = RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH;
    private static volatile int storedRollbackCooldownSeconds = RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
    /** 手动槽位数量那一份值：{@link #setManualSlotCount} 落盘时写的就是它。 */
    private static volatile int storedManualSlotCount = SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;

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
        boolean rollbackOnDeath = RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH;
        int cooldownSeconds = RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
        int manualSlots = SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;
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
                rollbackOnDeath = parseBoolean(properties.getProperty(KEY_ROLLBACK_ON_DEATH),
                        RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH);
                cooldownSeconds = parseCooldownSeconds(properties.getProperty(KEY_ROLLBACK_COOLDOWN_SECONDS));
                manualSlots = parseManualSlotCount(properties.getProperty(KEY_MANUAL_SLOT_COUNT));
            }
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to read {}; falling back to the defaults", file, t);
            enabled = DEFAULT_AUTO_CHECKPOINT;
            minutes = DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
            recipeBook = RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK;
            chunksNearPlayer = RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;
            rollbackOnDeath = RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH;
            cooldownSeconds = RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
            manualSlots = SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;
        }

        storedAutoCheckpointEnabled = enabled;
        storedAutoSaveIntervalMinutes = AutoCheckpointSettings.clampMinutes(minutes);
        storedSyncRecipeBook = recipeBook;
        storedSyncChunksNearPlayer = chunksNearPlayer;
        storedRollbackOnDeath = rollbackOnDeath;
        storedRollbackCooldownSeconds = RollbackSettings.clampCooldownSeconds(cooldownSeconds);
        storedManualSlotCount = SlotSettings.clamp(manualSlots);
        AutoCheckpointSettings.apply(storedAutoCheckpointEnabled, storedAutoSaveIntervalMinutes);
        RollbackSettings.apply(storedSyncRecipeBook, storedSyncChunksNearPlayer, storedRollbackCooldownSeconds,
                storedRollbackOnDeath);
        SlotSettings.apply(storedManualSlotCount);
        // 无论刚才是读出来的还是兜底出来的，都写回去一次：文件缺失时补上，值被夹过时纠正
        write();

        Rewind.LOGGER.info(
                "Rewind: auto checkpoint enabled={} interval={} min, rollback syncRecipeBook={} syncChunksNearPlayer={} "
                        + "rollbackOnDeath={} cooldownSeconds={}, slots manualSlotCount={}",
                AutoCheckpointSettings.autoCheckpointEnabled(), AutoCheckpointSettings.autoSaveIntervalMinutes(),
                RollbackSettings.syncRecipeBook(), RollbackSettings.syncChunksNearPlayer(),
                RollbackSettings.rollbackOnDeath(),
                RollbackSettings.cooldownSeconds(),
                SlotSettings.manualSlotCount());
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

    /** 死亡后要不要自动回溯到时间线上最近的那个节点。 */
    public static boolean rollbackOnDeath() {
        return RollbackSettings.rollbackOnDeath();
    }

    /** 改「死亡后自动回溯」开关并立刻落盘。 */
    public static void setRollbackOnDeath(boolean value) {
        RollbackSettings.setRollbackOnDeath(value);
        storedRollbackOnDeath = value;
        write();
        Rewind.LOGGER.info("Rewind: rollback rollbackOnDeath={} (toggled)", value);
    }

    /** 读档冷却时长（秒；0 表示不冷却）。 */
    public static int rollbackCooldownSeconds() {
        return RollbackSettings.cooldownSeconds();
    }

    /** 改读档冷却时长并立刻落盘。值会被夹到 {@link RollbackSettings#MIN_COOLDOWN_SECONDS} - {@link RollbackSettings#MAX_COOLDOWN_SECONDS}。 */
    public static void setRollbackCooldownSeconds(int value) {
        RollbackSettings.setCooldownSeconds(value);
        int clamped = RollbackSettings.cooldownSeconds();
        storedRollbackCooldownSeconds = clamped;
        write();
        Rewind.LOGGER.info("Rewind: rollback cooldownSeconds={} (toggled)", clamped);
    }

    /** 界面上的手动槽位数量（COMMON 配置可调，已夹到范围内）。 */
    public static int manualSlotCount() {
        return SlotSettings.manualSlotCount();
    }

    /** 改手动槽位数量并立刻落盘。值会被夹到 {@link SlotSettings#MIN_MANUAL_SLOT_COUNT} - {@link SlotSettings#MAX_MANUAL_SLOT_COUNT}。 */
    public static void setManualSlotCount(int value) {
        SlotSettings.setManualSlotCount(value);
        int clamped = SlotSettings.manualSlotCount();
        storedManualSlotCount = clamped;
        write();
        Rewind.LOGGER.info("Rewind: slots manualSlotCount={} (toggled)", clamped);
    }

    /** 解析 {@code slots.manualSlotCount}：非法值回默认，越界夹到范围内。 */
    private static int parseManualSlotCount(String raw) {
        if (raw == null) {
            return SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;
        }
        try {
            return SlotSettings.clamp(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            Rewind.LOGGER.warn("Rewind: \"{}\" is not a valid manual slot count; using the default {}",
                    raw, SlotSettings.DEFAULT_MANUAL_SLOT_COUNT);
            return SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;
        }
    }

    /** 解析 {@code rollback.cooldownSeconds}：非法值回默认，越界夹到范围内。 */
    private static int parseCooldownSeconds(String raw) {
        if (raw == null) {
            return RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
        }
        try {
            return RollbackSettings.clampCooldownSeconds(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            Rewind.LOGGER.warn("Rewind: \"{}\" is not a valid rollback cooldown; using the default {} seconds",
                    raw, RollbackSettings.DEFAULT_COOLDOWN_SECONDS);
            return RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
        }
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
        lines.add(KEY_ROLLBACK_ON_DEATH + "=" + storedRollbackOnDeath);
        lines.add(KEY_ROLLBACK_COOLDOWN_SECONDS + "=" + storedRollbackCooldownSeconds);
        lines.add(KEY_MANUAL_SLOT_COUNT + "=" + storedManualSlotCount);
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
