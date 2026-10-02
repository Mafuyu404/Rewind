package cc.sighs.rewind.common.config;

/**
 * 「跟着原版自动保存建点」这个服务端行为的共享设置。
 *
 * <p>默认值、可调范围、分钟 → tick 的换算都放在这里；各 target 的配置类（例如 NeoForge 的
 * {@code ModConfigSpec}）只负责读写值时把它当存储与校验层，界面和 Mixin 读的也都是这里。
 * 值在配置加载/重载时抄进 static 字段，读的时候就是普通字段访问——原版每次自动保存都会问一次，
 * 不适合每次都去查配置表。配置没加载成功时保持默认值，功能照常按默认值工作。
 */
public final class AutoCheckpointSettings {
    /** 「跟着原版自动保存建点」的默认值。 */
    public static final boolean DEFAULT_AUTO_CHECKPOINT = true;
    /** 原版自动保存间隔的默认值（分钟）——原版自己就是 5 分钟。 */
    public static final int DEFAULT_AUTO_SAVE_INTERVAL_MINUTES = 5;
    /** 自动保存间隔的可调范围（分钟）。 */
    public static final int MIN_AUTO_SAVE_INTERVAL_MINUTES = 1;
    public static final int MAX_AUTO_SAVE_INTERVAL_MINUTES = 60;
    /** 一分钟多少 tick（原版 20 tps）；{@link #autoSaveIntervalTicks()} 就是「分钟数 × 这个」。 */
    public static final int TICKS_PER_MINUTE = 20 * 60;

    private static volatile boolean autoCheckpointEnabled = DEFAULT_AUTO_CHECKPOINT;
    private static volatile int autoSaveIntervalMinutes = DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;

    private AutoCheckpointSettings() {
    }

    /** 跟着原版自动保存建点是不是开着。 */
    public static boolean autoCheckpointEnabled() {
        return autoCheckpointEnabled;
    }

    /** 配置里的自动保存间隔（分钟，已夹到范围内）。 */
    public static int autoSaveIntervalMinutes() {
        return autoSaveIntervalMinutes;
    }

    /**
     * 原版自动保存的间隔（tick）；**0 表示不碰原版**（让它按自己的 5 分钟走）。
     *
     * <p>只有「跟着原版自动保存建点」开着的时候才返回有效值——关着的时候改这个间隔没有意义。
     * 每次原版要算下一次自动保存的间隔时都会问一次这里，所以是个普通字段读，不查配置表。
     */
    public static int autoSaveIntervalTicks() {
        return autoCheckpointEnabled ? autoSaveIntervalMinutes * TICKS_PER_MINUTE : 0;
    }

    /** 配置加载/重载时把值抄进来（会夹到范围内）。 */
    public static void apply(boolean enabled, int minutes) {
        autoCheckpointEnabled = enabled;
        autoSaveIntervalMinutes = clampMinutes(minutes);
    }

    /** 改开关（只改值，落盘由平台配置层负责）。 */
    public static void setAutoCheckpointEnabled(boolean enabled) {
        autoCheckpointEnabled = enabled;
    }

    /** 改自动保存间隔（分钟，只改值，落盘由平台配置层负责）。 */
    public static void setAutoSaveIntervalMinutes(int minutes) {
        autoSaveIntervalMinutes = clampMinutes(minutes);
    }

    public static int clampMinutes(int minutes) {
        return Math.max(MIN_AUTO_SAVE_INTERVAL_MINUTES, Math.min(MAX_AUTO_SAVE_INTERVAL_MINUTES, minutes));
    }
}
