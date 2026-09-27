package cc.sighs.rewind.server;

import cc.sighs.rewind.Rewind;
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
 * <p>值在配置加载/重载时抄进 static 字段，读的时候就是普通字段访问——原版每次自动保存都会问一次，
 * 不适合每次都去查配置表。配置没加载成功时保持默认值，功能照常按默认值工作。
 */
public final class RewindServerConfig {
    /** 「跟着原版自动保存建点」的默认值。 */
    public static final boolean DEFAULT_AUTO_CHECKPOINT = true;

    private static ModConfigSpec.BooleanValue autoCheckpointEnabled;
    private static ModConfigSpec spec;
    /** 保存下来的配置对象：界面上改完开关要立刻落盘。 */
    private static volatile ModConfig modConfig;
    private static volatile boolean autoCheckpointEnabledValue = DEFAULT_AUTO_CHECKPOINT;

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
            autoCheckpointEnabledValue = autoCheckpointEnabled == null
                    ? DEFAULT_AUTO_CHECKPOINT
                    : autoCheckpointEnabled.get();
        } catch (Throwable t) {
            autoCheckpointEnabledValue = DEFAULT_AUTO_CHECKPOINT;
        }
        Rewind.LOGGER.info("Rewind: auto checkpoint enabled={}", autoCheckpointEnabledValue);
    }

    /** 跟着原版自动保存建点是不是开着。 */
    public static boolean autoCheckpointEnabled() {
        return autoCheckpointEnabledValue;
    }

    /** 改这个开关并立刻落盘（界面上「自动存档」卡的「设置」按钮走这里）。 */
    public static void setAutoCheckpointEnabled(boolean enabled) {
        autoCheckpointEnabledValue = enabled;
        if (autoCheckpointEnabled != null) {
            autoCheckpointEnabled.set(enabled);
        }
        ModConfig current = modConfig;
        if (current != null) {
            try {
                // FML 4 的 ModConfig 上没有 save()，落盘入口在 loadedConfig 上
                current.getLoadedConfig().save();
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to save the config after toggling the auto checkpoint", t);
            }
        }
        Rewind.LOGGER.info("Rewind: auto checkpoint enabled={} (toggled)", enabled);
    }
}
