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
    // 默认值：必须与改造前写死的常量一致
    public static final float DEFAULT_FADE_IN_SECONDS = 0.22F;
    public static final float DEFAULT_SATURATION_FADE_OUT_SECONDS = 0.15F;
    public static final float DEFAULT_SATURATION_BOOST = 1.5F;
    public static final float DEFAULT_BLUR_FADE_OUT_SECONDS = 0.05F;
    public static final float DEFAULT_BLUR_RADIUS = 13.0F;
    public static final int DEFAULT_RESTORE_SETTLE_TICKS = 10;
    /** 「存档过渡强度」的可调范围（就是配置里那个饱和度倍数）。 */
    public static final float MIN_SATURATION_BOOST = 0.0F;
    public static final float MAX_SATURATION_BOOST = 8.0F;
    /** 「读档过渡强度」的可调范围（就是配置里那个模糊半径，像素）。 */
    public static final float MIN_BLUR_RADIUS = 0.0F;
    public static final float MAX_BLUR_RADIUS = 64.0F;

    private static ForgeConfigSpec.DoubleValue fadeIn;
    private static ForgeConfigSpec.DoubleValue saturationFadeOut;
    private static ForgeConfigSpec.DoubleValue saturationBoost;
    private static ForgeConfigSpec.DoubleValue blurFadeOut;
    private static ForgeConfigSpec.DoubleValue blurRadius;
    private static ForgeConfigSpec.IntValue restoreSettleTicks;
    private static ForgeConfigSpec spec;
    /** 保存下来的配置对象：界面上拖完滚动条要立刻落盘。 */
    private static volatile ModConfig modConfig;

    private static float fadeInSeconds = DEFAULT_FADE_IN_SECONDS;
    private static float saturationFadeOutSeconds = DEFAULT_SATURATION_FADE_OUT_SECONDS;
    private static float saturationBoostValue = DEFAULT_SATURATION_BOOST;
    private static float blurFadeOutSeconds = DEFAULT_BLUR_FADE_OUT_SECONDS;
    private static float blurRadiusValue = DEFAULT_BLUR_RADIUS;
    private static int restoreSettleTicksValue = DEFAULT_RESTORE_SETTLE_TICKS;

    private RewindClientConfig() {
    }

    /** 注册配置。必须在模组构造阶段调用，否则会赶不上配置加载事件。 */
    public static void register(IEventBus modBus) {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Rewind 的过渡参数（客户端）。存档 = 饱和度提高，读档 = 高斯模糊。")
                .push("transition");

        fadeIn = builder
                .comment("淡入时长（秒），两种过渡共用。等它走完才真正开始动世界，所以这个值也决定了"
                        + "按下热键到操作开始之间的延迟。")
                .defineInRange("fadeInSeconds", (double) DEFAULT_FADE_IN_SECONDS, 0.01D, 5.0D);

        saturationFadeOut = builder
                .comment("存档过渡的淡出时长（秒）：饱和度从满强度回到正常。")
                .defineInRange("saturationFadeOutSeconds", (double) DEFAULT_SATURATION_FADE_OUT_SECONDS, 0.01D, 5.0D);

        saturationBoost = builder
                .comment("存档过渡满强度时的饱和度倍数（在 1 的基础上再加这么多）：1.5 = 2.5 倍。",
                        "再往上调，偏暗的通道会先被截断、开始出现色块，不是越大越好。")
                .defineInRange("saturationBoost", (double) DEFAULT_SATURATION_BOOST,
                        (double) MIN_SATURATION_BOOST, (double) MAX_SATURATION_BOOST);

        blurFadeOut = builder
                .comment("读档过渡的淡出时长（秒）：世界回来之后模糊消失得有多快。")
                .defineInRange("blurFadeOutSeconds", (double) DEFAULT_BLUR_FADE_OUT_SECONDS, 0.01D, 5.0D);

        blurRadius = builder
                .comment("读档过渡满强度时的模糊半径（像素）：着色器按这个距离做 13 抽样高斯。",
                        "画面分辨率越高，同样的像素半径看起来越轻。")
                .defineInRange("blurRadius", (double) DEFAULT_BLUR_RADIUS,
                        (double) MIN_BLUR_RADIUS, (double) MAX_BLUR_RADIUS);

        restoreSettleTicks = builder
                .comment("读档时「世界已经回来」之后、开始淡出之前，等区块到位的**上限**（20 tick = 1 秒）。",
                        "正常情况下用不到这个上限：客户端已加载的区块数连续两 tick 不再增长就淡出，",
                        "所以读档完成后通常只还糊 0.1-0.2 秒（再加下面的淡出时长）；这个值只是大视距 / 卡顿时的兜底。")
                .defineInRange("restoreSettleTicks", DEFAULT_RESTORE_SETTLE_TICKS, 0, 200);

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
        Rewind.LOGGER.info(
                "Rewind: transition config fadeIn={}s saturationFadeOut={}s saturationBoost={} blurFadeOut={}s blurRadius={} restoreSettleTicks={}",
                fadeInSeconds, saturationFadeOutSeconds, saturationBoostValue, blurFadeOutSeconds, blurRadiusValue,
                restoreSettleTicksValue);
    }

    private static float read(ForgeConfigSpec.DoubleValue value, float fallback) {
        try {
            return value == null ? fallback : value.get().floatValue();
        } catch (Throwable t) {
            return fallback;
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
