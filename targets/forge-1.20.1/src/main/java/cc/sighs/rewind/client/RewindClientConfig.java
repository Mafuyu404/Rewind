package cc.sighs.rewind.client;

import cc.sighs.rewind.Rewind;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * 过渡参数的客户端配置，落在 {@code run/config/rewind-client.toml}。
 *
 * <p>两种过渡（存档的饱和度提高、读档的高斯模糊）的所有可调项都在这里，默认值与代码里原本写死的
 * 常量一致，所以不改配置就是原来的观感。
 *
 * <p>值只在配置加载/重载时抄进 static 字段，读的时候就是普通字段访问——后处理每帧都要读它，
 * 不适合每帧去查一次配置表。配置没加载成功时字段保持默认值，过渡照常工作。
 *
 * <p>与 NeoForge 1.21.1 那份的差别只在接线（可调项、默认值、范围、界面用的 preview/commit 全部一致）：
 * <ul>
 *   <li>规格类是 {@code net.minecraftforge.common.ForgeConfigSpec}（NeoForge 是 {@code ModConfigSpec}），
 *       方法名一样；</li>
 *   <li>Forge 1.20.1 的 {@code ModContainer} 上没有 {@code registerConfig}，注册走
 *       {@code ModLoadingContext.get().registerConfig(...)}；</li>
 *   <li>Forge 的事件总线没有 NeoForge 那个 {@code addListener(Class<T>, Consumer<T>)} 重载，监听哪个
 *       事件是从 consumer 的泛型参数反推出来的，所以 Loading / Reloading 各写一个包装方法；</li>
 *   <li>Forge 1.20.1 的 {@code ModConfig} **有** {@code save()}，不需要 NeoForge 那边的
 *       {@code getLoadedConfig().save()} 绕路。</li>
 * </ul>
 */
public final class RewindClientConfig {
    // 默认值：必须与改造前写死的常量一致。存成 double 而不是 float：配置表按 double 落盘，
    // float 会写出 0.2199999988079071 这种二进制误差尾巴（运行时字段仍是 float，抄值时收窄）。
    public static final double DEFAULT_FADE_IN_SECONDS = 0.22D;
    public static final double DEFAULT_SATURATION_FADE_OUT_SECONDS = 0.15D;
    public static final double DEFAULT_SATURATION_BOOST = 1.5D;
    public static final double DEFAULT_BLUR_FADE_OUT_SECONDS = 0.05D;
    public static final double DEFAULT_BLUR_RADIUS = 13.0D;
    public static final int DEFAULT_RESTORE_SETTLE_TICKS = 10;
    /** 「存档过渡强度」的可调范围（就是配置里那个饱和度倍数）。 */
    public static final float MIN_SATURATION_BOOST = 0.0F;
    public static final float MAX_SATURATION_BOOST = 8.0F;
    /** 「读档过渡强度」的可调范围（就是配置里那个模糊半径，像素）。 */
    public static final float MIN_BLUR_RADIUS = 0.0F;
    public static final float MAX_BLUR_RADIUS = 64.0F;
    /** 时间树界面记忆的默认值：档案布局、按槽位序号排序、时间轴从上到下。 */
    public static final boolean DEFAULT_TREE_LAYOUT = false;
    /** 排序方式存的是模板 {@code #sort} 的 option value（小写），与界面里 {@code SortMode.of} 认的形式一致。 */
    public static final String DEFAULT_SORT_MODE = "index";
    public static final String DEFAULT_TREE_DIRECTION = "down";

    private static ForgeConfigSpec.DoubleValue fadeIn;
    private static ForgeConfigSpec.DoubleValue saturationFadeOut;
    private static ForgeConfigSpec.DoubleValue saturationBoost;
    private static ForgeConfigSpec.DoubleValue blurFadeOut;
    private static ForgeConfigSpec.DoubleValue blurRadius;
    private static ForgeConfigSpec.IntValue restoreSettleTicks;
    private static ForgeConfigSpec.BooleanValue treeLayout;
    private static ForgeConfigSpec.ConfigValue<String> sortMode;
    private static ForgeConfigSpec.ConfigValue<String> treeDirection;
    private static ForgeConfigSpec spec;
    /** 保存下来的配置对象：界面上拖完滚动条 / 切完布局要立刻落盘。 */
    private static volatile ModConfig modConfig;

    private static float fadeInSeconds = (float) DEFAULT_FADE_IN_SECONDS;
    private static float saturationFadeOutSeconds = (float) DEFAULT_SATURATION_FADE_OUT_SECONDS;
    private static float saturationBoostValue = (float) DEFAULT_SATURATION_BOOST;
    private static float blurFadeOutSeconds = (float) DEFAULT_BLUR_FADE_OUT_SECONDS;
    private static float blurRadiusValue = (float) DEFAULT_BLUR_RADIUS;
    private static int restoreSettleTicksValue = DEFAULT_RESTORE_SETTLE_TICKS;
    private static boolean treeLayoutValue = DEFAULT_TREE_LAYOUT;
    private static String sortModeValue = DEFAULT_SORT_MODE;
    private static String treeDirectionValue = DEFAULT_TREE_DIRECTION;

    private RewindClientConfig() {
    }

    /** 注册配置。必须在模组构造阶段调用，否则会赶不上配置加载事件。 */
    public static void register(IEventBus modBus) {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("过渡参数（客户端）：存档 = 饱和度，读档 = 模糊。",
                        "Transition settings (client): save = saturation, restore = blur.")
                .push("transition");

        fadeIn = builder
                .comment("淡入时长（秒），两种过渡共用。",
                        "Fade-in duration in seconds, shared by both transitions.")
                .defineInRange("fadeInSeconds", DEFAULT_FADE_IN_SECONDS, 0.01D, 5.0D);

        saturationFadeOut = builder
                .comment("存档过渡的淡出时长（秒）。",
                        "Saturation fade-out duration in seconds.")
                .defineInRange("saturationFadeOutSeconds", DEFAULT_SATURATION_FADE_OUT_SECONDS, 0.01D, 5.0D);

        saturationBoost = builder
                .comment("存档过渡满强度时的饱和度倍数（1.5 = 2.5 倍）。",
                        "Saturation multiplier at full strength (1.5 = 2.5x).")
                .defineInRange("saturationBoost", DEFAULT_SATURATION_BOOST,
                        (double) MIN_SATURATION_BOOST, (double) MAX_SATURATION_BOOST);

        blurFadeOut = builder
                .comment("读档过渡的淡出时长（秒）。",
                        "Blur fade-out duration in seconds.")
                .defineInRange("blurFadeOutSeconds", DEFAULT_BLUR_FADE_OUT_SECONDS, 0.01D, 5.0D);

        blurRadius = builder
                .comment("读档过渡满强度时的模糊半径（像素）。",
                        "Blur radius at full strength, in pixels.")
                .defineInRange("blurRadius", DEFAULT_BLUR_RADIUS,
                        (double) MIN_BLUR_RADIUS, (double) MAX_BLUR_RADIUS);

        restoreSettleTicks = builder
                .comment("读档后等待区块到位的上限（tick）。",
                        "Cap in ticks to wait for chunks after a restore.")
                .defineInRange("restoreSettleTicks", DEFAULT_RESTORE_SETTLE_TICKS, 0, 200);

        builder.pop();

        builder.comment("时间树界面的记忆。", "Time-tree UI memory.")
                .push("gui");
        treeLayout = builder
                .comment("上次用的布局：false = 档案布局，true = 节点树布局。",
                        "Last used layout: false = slot cards, true = node tree.")
                .define("treeLayout", DEFAULT_TREE_LAYOUT);
        sortMode = builder
                .comment("档案布局的排序方式：recent / oldest / name / playtime / index。",
                        "Slot sort mode: recent / oldest / name / playtime / index.")
                .define("sortMode", DEFAULT_SORT_MODE);
        treeDirection = builder
                .comment("时间轴方向：down / right / up / left。",
                        "Timeline direction: down / right / up / left.")
                .define("treeDirection", DEFAULT_TREE_DIRECTION);
        builder.pop();

        spec = builder.build();
        // Forge 1.20.1 的 ModContainer 上没有 registerConfig，只能用 ModLoadingContext 的当前容器
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, spec);

        modBus.addListener(RewindClientConfig::onConfigLoading);
        modBus.addListener(RewindClientConfig::onConfigReloading);
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        onConfigEvent(event);
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        onConfigEvent(event);
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
        fadeInSeconds = read(fadeIn, DEFAULT_FADE_IN_SECONDS);
        saturationFadeOutSeconds = read(saturationFadeOut, DEFAULT_SATURATION_FADE_OUT_SECONDS);
        saturationBoostValue = read(saturationBoost, DEFAULT_SATURATION_BOOST);
        blurFadeOutSeconds = read(blurFadeOut, DEFAULT_BLUR_FADE_OUT_SECONDS);
        blurRadiusValue = read(blurRadius, DEFAULT_BLUR_RADIUS);
        restoreSettleTicksValue = readInt(restoreSettleTicks, DEFAULT_RESTORE_SETTLE_TICKS);
        treeLayoutValue = readBool(treeLayout, DEFAULT_TREE_LAYOUT);
        sortModeValue = readString(sortMode, DEFAULT_SORT_MODE);
        treeDirectionValue = readString(treeDirection, DEFAULT_TREE_DIRECTION);
        Rewind.LOGGER.info(
                "Rewind: transition config fadeIn={}s saturationFadeOut={}s saturationBoost={} blurFadeOut={}s blurRadius={} restoreSettleTicks={}, gui treeLayout={} sortMode={} treeDirection={}",
                fadeInSeconds, saturationFadeOutSeconds, saturationBoostValue, blurFadeOutSeconds, blurRadiusValue,
                restoreSettleTicksValue, treeLayoutValue, sortModeValue, treeDirectionValue);
    }

    private static boolean readBool(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value == null ? fallback : value.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String readString(ForgeConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            String raw = value == null ? null : value.get();
            return raw == null || raw.trim().isEmpty() ? fallback : raw.trim();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static float read(ForgeConfigSpec.DoubleValue value, double fallback) {
        try {
            return value == null ? (float) fallback : value.get().floatValue();
        } catch (Throwable t) {
            return (float) fallback;
        }
    }

    private static int readInt(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value == null ? fallback : value.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static float fadeInSeconds() {
        return fadeInSeconds;
    }

    public static float saturationFadeOutSeconds() {
        return saturationFadeOutSeconds;
    }

    public static float saturationBoost() {
        return saturationBoostValue;
    }

    public static float blurFadeOutSeconds() {
        return blurFadeOutSeconds;
    }

    public static float blurRadius() {
        return blurRadiusValue;
    }

    public static int restoreSettleTicks() {
        return restoreSettleTicksValue;
    }

    // ------------------------------------------------------------------ 时间树界面的记忆

    /** 上次用的布局：false = 档案布局，true = 节点树布局。 */
    public static boolean treeLayout() {
        return treeLayoutValue;
    }

    /** 上次用的排序方式（模板 {@code #sort} 的 option value，小写）。 */
    public static String sortMode() {
        return sortModeValue;
    }

    /** 上次用的时间轴方向（{@code down} / {@code right} / {@code up} / {@code left}）。 */
    public static String treeDirection() {
        return treeDirectionValue;
    }

    /** 切换布局之后调用：记住并立刻落盘。 */
    public static void setTreeLayout(boolean value) {
        treeLayoutValue = value;
        if (treeLayout != null) {
            treeLayout.set(value);
        }
        save();
        Rewind.LOGGER.info("Rewind: gui treeLayout={} (set)", value);
    }

    /** 改排序方式之后调用：记住并立刻落盘。 */
    public static void setSortMode(String value) {
        sortModeValue = value == null || value.trim().isEmpty() ? DEFAULT_SORT_MODE : value.trim();
        if (sortMode != null) {
            sortMode.set(sortModeValue);
        }
        save();
        Rewind.LOGGER.info("Rewind: gui sortMode={} (set)", sortModeValue);
    }

    /** 转时间轴方向之后调用：记住并立刻落盘。 */
    public static void setTreeDirection(String value) {
        treeDirectionValue = value == null || value.trim().isEmpty() ? DEFAULT_TREE_DIRECTION : value.trim();
        if (treeDirection != null) {
            treeDirection.set(treeDirectionValue);
        }
        save();
        Rewind.LOGGER.info("Rewind: gui treeDirection={} (set)", treeDirectionValue);
    }

    /**
     * 「存档过渡强度」拖动中：只改内存里的值，**不落盘**。
     *
     * <p>滚动条拖一次会来一串 {@code input} 事件，每个都落盘会连带触发配置文件监听重载、刷一屏日志。
     * 拖完（{@code change}）再 {@link #commitSaturationBoost}。
     */
    public static void previewSaturationBoost(float boost) {
        saturationBoostValue = clamp(boost, MIN_SATURATION_BOOST, MAX_SATURATION_BOOST);
    }

    /** 「存档过渡强度」拖完了：写进配置并落盘。后处理每帧读的就是这个 static 字段，所以立刻见效。 */
    public static void commitSaturationBoost(float boost) {
        previewSaturationBoost(boost);
        if (saturationBoost != null) {
            saturationBoost.set((double) saturationBoostValue);
        }
        save();
        Rewind.LOGGER.info("Rewind: transition saturationBoost={} (set)", saturationBoostValue);
    }

    /** 「读档过渡强度」拖动中：只改内存里的值，不落盘。 */
    public static void previewBlurRadius(float radius) {
        blurRadiusValue = clamp(radius, MIN_BLUR_RADIUS, MAX_BLUR_RADIUS);
    }

    /** 「读档过渡强度」拖完了：写进配置并落盘。 */
    public static void commitBlurRadius(float radius) {
        previewBlurRadius(radius);
        if (blurRadius != null) {
            blurRadius.set((double) blurRadiusValue);
        }
        save();
        Rewind.LOGGER.info("Rewind: transition blurRadius={} (set)", blurRadiusValue);
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static void save() {
        ModConfig current = modConfig;
        if (current == null) {
            return;
        }
        try {
            // Forge 1.20.1 的 ModConfig 自己就有 save()
            current.save();
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to save the client config after a settings change", t);
        }
    }
}
