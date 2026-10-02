package cc.sighs.rewind.server;

import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.common.config.AutoCheckpointSettings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 服务端行为的配置，落在 {@code run/config/rewind-common.toml}。
 *
 * <p>目前只有一项：「跟着原版自动保存建点」。它是 COMMON 配置而不是 CLIENT，因为建点发生在服务端
 * 线程上，跟玩家有没有客户端没关系。
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
    private static ModConfigSpec spec;
    /** 保存下来的配置对象：界面上改完开关要立刻落盘。 */
    private static volatile ModConfig modConfig;

    private RewindServerConfig() {
    }

    /** 注册配置。必须在模组构造阶段调用，否则会赶不上配置加载事件。 */
    public static void register(ModContainer container, IEventBus modBus) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Rewind 的服务端行为。").push("autoCheckpoint");

        autoCheckpointEnabled = builder
                .comment("跟着原版自动保存建点：游戏每次自动保存完，往「自动存档」槽位写一个 Rewind 存档点。",
                        "建点在服务端线程上跑（落盘 + 增量拷贝），所以每次会有一次几十到几百毫秒的卡顿，",
                        "存档越大越明显。嫌它卡就关掉——界面上「自动存档」那张卡的「设置」里也有同一个开关。")
                .define("enabled", DEFAULT_AUTO_CHECKPOINT);

        autoSaveIntervalMinutes = builder
                .comment("原版自动保存的间隔（分钟）。上面的开关开着时，这个值**直接改原版的自动保存间隔**",
                        "（原版自己固定 5 分钟）；关着的时候原版保持它自己的间隔，这个值不生效。",
                        "范围 " + MIN_AUTO_SAVE_INTERVAL_MINUTES + " - " + MAX_AUTO_SAVE_INTERVAL_MINUTES + " 分钟。")
                .defineInRange("intervalMinutes", DEFAULT_AUTO_SAVE_INTERVAL_MINUTES,
                        MIN_AUTO_SAVE_INTERVAL_MINUTES, MAX_AUTO_SAVE_INTERVAL_MINUTES);

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
        } catch (Throwable t) {
            AutoCheckpointSettings.apply(DEFAULT_AUTO_CHECKPOINT, DEFAULT_AUTO_SAVE_INTERVAL_MINUTES);
        }
        Rewind.LOGGER.info("Rewind: auto checkpoint enabled={} interval={} min",
                AutoCheckpointSettings.autoCheckpointEnabled(), AutoCheckpointSettings.autoSaveIntervalMinutes());
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

    private static void save() {
        ModConfig current = modConfig;
        if (current == null) {
            return;
        }
        try {
            // FML 4 的 ModConfig 上没有 save()，落盘入口在 loadedConfig 上
            current.getLoadedConfig().save();
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to save the config after a settings change", t);
        }
    }
}
