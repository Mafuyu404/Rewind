package cc.sighs.rewind.server;

import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.common.config.AutoCheckpointSettings;
import cc.sighs.rewind.common.config.RollbackSettings;
import cc.sighs.rewind.common.config.SlotSettings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 服务端行为的配置，落在 {@code run/config/rewind-common.toml}。
 *
 * <p>目前分三组：{@code autoCheckpoint}（跟着原版自动保存建点、间隔分钟数）、{@code rollback}
 * （回溯时要不要重发配方书、要不要只同步读回玩家视野内的区块，以及读档冷却秒数）与 {@code slots}
 * （界面上那排手动编号卡片 {@code s1}…{@code sN} 的数量）。它是 COMMON 配置而不是 CLIENT，
 * 因为建点与回滚都发生在服务端线程上，跟玩家有没有客户端没关系。
 *
 * <p>默认值、可调范围与「分钟 → tick」的换算都在 common 的 {@link AutoCheckpointSettings} 里；
 * 本类只负责 NeoForge 的 {@code ModConfigSpec} 读写与落盘，界面/Mixin 读到的都是那一份共享值。
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

    private static ModConfigSpec.BooleanValue autoCheckpointEnabled;
    private static ModConfigSpec.IntValue autoSaveIntervalMinutes;
    private static ModConfigSpec.BooleanValue syncRecipeBook;
    private static ModConfigSpec.BooleanValue syncChunksNearPlayer;
    private static ModConfigSpec.IntValue rollbackCooldownSeconds;
    private static ModConfigSpec.IntValue manualSlotCount;
    private static ModConfigSpec spec;
    /** 保存下来的配置对象：界面上改完开关要立刻落盘。 */
    private static volatile ModConfig modConfig;

    private RewindServerConfig() {
    }

    /** 注册配置。必须在模组构造阶段调用，否则会赶不上配置加载事件。 */
    public static void register(ModContainer container, IEventBus modBus) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("自动存档点。", "Auto checkpoints.").push("autoCheckpoint");

        autoCheckpointEnabled = builder
                .comment("跟着原版自动保存建点。",
                        "Create a checkpoint after each vanilla autosave.")
                .define("enabled", DEFAULT_AUTO_CHECKPOINT);

        autoSaveIntervalMinutes = builder
                .comment("原版自动保存间隔（分钟，1-60）。",
                        "Vanilla autosave interval in minutes (1-60).")
                .defineInRange("intervalMinutes", DEFAULT_AUTO_SAVE_INTERVAL_MINUTES,
                        MIN_AUTO_SAVE_INTERVAL_MINUTES, MAX_AUTO_SAVE_INTERVAL_MINUTES);

        builder.pop();

        builder.comment("回溯行为。", "Rollback behaviour.").push("rollback");
        syncRecipeBook = builder
                .comment("回溯时重新同步玩家的配方书。",
                        "Re-sync the player's recipe book on rollback.")
                .define("syncRecipeBook", RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK);

        syncChunksNearPlayer = builder
                .comment("回溯时只同步读回玩家视野内的区块。",
                        "On rollback, sync-load only chunks near the player.")
                .define("syncChunksNearPlayer", RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER);

        rollbackCooldownSeconds = builder
                .comment("读档冷却（秒），0 = 关闭。",
                        "Rollback cooldown in seconds, 0 = off.")
                .defineInRange("cooldownSeconds", RollbackSettings.DEFAULT_COOLDOWN_SECONDS,
                        RollbackSettings.MIN_COOLDOWN_SECONDS, RollbackSettings.MAX_COOLDOWN_SECONDS);
        builder.pop();

        builder.comment("槽位布局。", "Slot layout.").push("slots");
        manualSlotCount = builder
                .comment("手动槽位数量。",
                        "Number of manual slots.")
                .defineInRange("manualSlotCount", SlotSettings.DEFAULT_MANUAL_SLOT_COUNT,
                        SlotSettings.MIN_MANUAL_SLOT_COUNT, SlotSettings.MAX_MANUAL_SLOT_COUNT);
        builder.pop();

        spec = builder.build();
        container.registerConfig(ModConfig.Type.COMMON, spec);

        modBus.addListener(ModConfigEvent.Loading.class, RewindServerConfig::onConfigEvent);
        modBus.addListener(ModConfigEvent.Reloading.class, RewindServerConfig::onConfigEvent);
    }

    private static void onConfigEvent(ModConfigEvent event) {
        // 这个事件对所有配置都会来一次，先认一下是不是自己那份
        if (event.getConfig().getSpec() != spec) {
            return;
        }
        modConfig = event.getConfig();
        apply();
    }

    private static void apply() {
        try {
            AutoCheckpointSettings.apply(
                    autoCheckpointEnabled == null ? DEFAULT_AUTO_CHECKPOINT : autoCheckpointEnabled.get(),
                    autoSaveIntervalMinutes == null
                            ? DEFAULT_AUTO_SAVE_INTERVAL_MINUTES
                            : autoSaveIntervalMinutes.get());
            RollbackSettings.apply(
                    syncRecipeBook == null ? RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK : syncRecipeBook.get(),
                    syncChunksNearPlayer == null
                            ? RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER
                            : syncChunksNearPlayer.get(),
                    rollbackCooldownSeconds == null
                            ? RollbackSettings.DEFAULT_COOLDOWN_SECONDS
                            : rollbackCooldownSeconds.get());
            SlotSettings.apply(manualSlotCount == null
                    ? SlotSettings.DEFAULT_MANUAL_SLOT_COUNT
                    : manualSlotCount.get());
        } catch (Throwable t) {
            AutoCheckpointSettings.apply(DEFAULT_AUTO_CHECKPOINT, DEFAULT_AUTO_SAVE_INTERVAL_MINUTES);
            RollbackSettings.apply(RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK,
                    RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER,
                    RollbackSettings.DEFAULT_COOLDOWN_SECONDS);
            SlotSettings.apply(SlotSettings.DEFAULT_MANUAL_SLOT_COUNT);
        }
        Rewind.LOGGER.info(
                "Rewind: auto checkpoint enabled={} interval={} min, rollback syncRecipeBook={} syncChunksNearPlayer={}, "
                        + "cooldown={}s, slots manualSlotCount={}",
                AutoCheckpointSettings.autoCheckpointEnabled(), AutoCheckpointSettings.autoSaveIntervalMinutes(),
                RollbackSettings.syncRecipeBook(), RollbackSettings.syncChunksNearPlayer(),
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
     * 每次原版要算下一次自动保存的间隔时都会问一次这里，所以是个普通字段读，不查配置表。
     */
    public static int autoSaveIntervalTicks() {
        return AutoCheckpointSettings.autoSaveIntervalTicks();
    }

    /** 改这个开关并立刻落盘（界面上「自动存档」卡的「设置」按钮走这里）。 */
    public static void setAutoCheckpointEnabled(boolean enabled) {
        AutoCheckpointSettings.setAutoCheckpointEnabled(enabled);
        if (autoCheckpointEnabled != null) {
            autoCheckpointEnabled.set(enabled);
        }
        save();
        Rewind.LOGGER.info("Rewind: auto checkpoint enabled={} (toggled)", enabled);
    }

    /** 改自动保存间隔（分钟）并立刻落盘。值会被夹到 {@link #MIN_AUTO_SAVE_INTERVAL_MINUTES} - {@link #MAX_AUTO_SAVE_INTERVAL_MINUTES}。 */
    public static void setAutoSaveIntervalMinutes(int minutes) {
        AutoCheckpointSettings.setAutoSaveIntervalMinutes(minutes);
        int clamped = AutoCheckpointSettings.autoSaveIntervalMinutes();
        if (autoSaveIntervalMinutes != null) {
            autoSaveIntervalMinutes.set(clamped);
        }
        save();
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
        if (syncChunksNearPlayer != null) {
            syncChunksNearPlayer.set(value);
        }
        save();
        Rewind.LOGGER.info("Rewind: rollback syncChunksNearPlayer={} (toggled)", value);
    }

    /** 改这个开关并立刻落盘。 */
    public static void setSyncRecipeBook(boolean value) {
        RollbackSettings.setSyncRecipeBook(value);
        if (syncRecipeBook != null) {
            syncRecipeBook.set(value);
        }
        save();
        Rewind.LOGGER.info("Rewind: rollback syncRecipeBook={} (toggled)", value);
    }

    /** 读档冷却时长（秒，已夹到范围内）；0 表示不冷却。 */
    public static int rollbackCooldownSeconds() {
        return RollbackSettings.cooldownSeconds();
    }

    /**
     * 改读档冷却时长（秒）并立刻落盘。值会被夹到
     * {@link RollbackSettings#MIN_COOLDOWN_SECONDS} - {@link RollbackSettings#MAX_COOLDOWN_SECONDS}。
     */
    public static void setRollbackCooldownSeconds(int value) {
        RollbackSettings.setCooldownSeconds(value);
        int clamped = RollbackSettings.cooldownSeconds();
        if (rollbackCooldownSeconds != null) {
            rollbackCooldownSeconds.set(clamped);
        }
        save();
        Rewind.LOGGER.info("Rewind: rollback cooldown={}s (toggled)", clamped);
    }

    /** 界面上的手动槽位数量（COMMON 配置可调，已夹到范围内）。 */
    public static int manualSlotCount() {
        return SlotSettings.manualSlotCount();
    }

    /** 改手动槽位数量并立刻落盘。值会被夹到 {@link SlotSettings#MIN_MANUAL_SLOT_COUNT} - {@link SlotSettings#MAX_MANUAL_SLOT_COUNT}。 */
    public static void setManualSlotCount(int value) {
        SlotSettings.setManualSlotCount(value);
        int clamped = SlotSettings.manualSlotCount();
        if (manualSlotCount != null) {
            manualSlotCount.set(clamped);
        }
        save();
        Rewind.LOGGER.info("Rewind: slots manualSlotCount={} (toggled)", clamped);
    }

    private static void save() {
        ModConfig current = modConfig;
        if (current == null) {
            return;
        }
        try {
            // FML 的 ModConfig 上没有 save()，落盘入口在 loadedConfig 上（26.1 的 FML 11 仍是这一套）
            current.getLoadedConfig().save();
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to save the config after a settings change", t);
        }
    }
}
