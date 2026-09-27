package cc.sighs.rewind.client;

import cc.sighs.rewind.Rewind;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 过渡参数的客户端配置，落在 {@code run/config/rewind-client.toml}。
 *
 * <p>两种过渡（存档的饱和度提高、读档的高斯模糊）的所有可调项都在这里，默认值与代码里原本写死的
 * 常量一致，所以不改配置就是原来的观感。
 *
 * <p>值只在配置加载/重载时抄进 static 字段，读的时候就是普通字段访问——后处理每帧都要读它，
 * 不适合每帧去查一次配置表。配置没加载成功时字段保持默认值，过渡照常工作。
 */
public final class RewindClientConfig {
    // 默认值：必须与改造前写死的常量一致
    public static final float DEFAULT_FADE_IN_SECONDS = 0.22F;
    public static final float DEFAULT_SATURATION_FADE_OUT_SECONDS = 0.15F;
    public static final float DEFAULT_SATURATION_BOOST = 1.5F;
    public static final float DEFAULT_BLUR_FADE_OUT_SECONDS = 0.05F;
    public static final float DEFAULT_BLUR_RADIUS = 13.0F;
    public static final int DEFAULT_RESTORE_SETTLE_TICKS = 10;

    private static ModConfigSpec.DoubleValue fadeIn;
    private static ModConfigSpec.DoubleValue saturationFadeOut;
    private static ModConfigSpec.DoubleValue saturationBoost;
    private static ModConfigSpec.DoubleValue blurFadeOut;
    private static ModConfigSpec.DoubleValue blurRadius;
    private static ModConfigSpec.IntValue restoreSettleTicks;

    private static float fadeInSeconds = DEFAULT_FADE_IN_SECONDS;
    private static float saturationFadeOutSeconds = DEFAULT_SATURATION_FADE_OUT_SECONDS;
    private static float saturationBoostValue = DEFAULT_SATURATION_BOOST;
    private static float blurFadeOutSeconds = DEFAULT_BLUR_FADE_OUT_SECONDS;
    private static float blurRadiusValue = DEFAULT_BLUR_RADIUS;
    private static int restoreSettleTicksValue = DEFAULT_RESTORE_SETTLE_TICKS;

    private RewindClientConfig() {
    }

    /** 注册配置。必须在模组构造阶段调用，否则会赶不上配置加载事件。 */
    public static void register(ModContainer container, IEventBus modBus) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
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
                .defineInRange("saturationBoost", (double) DEFAULT_SATURATION_BOOST, 0.0D, 8.0D);

        blurFadeOut = builder
                .comment("读档过渡的淡出时长（秒）：世界回来之后模糊消失得有多快。")
                .defineInRange("blurFadeOutSeconds", (double) DEFAULT_BLUR_FADE_OUT_SECONDS, 0.01D, 5.0D);

        blurRadius = builder
                .comment("读档过渡满强度时的模糊半径（像素）：着色器按这个距离做 13 抽样高斯。",
                        "画面分辨率越高，同样的像素半径看起来越轻。")
                .defineInRange("blurRadius", (double) DEFAULT_BLUR_RADIUS, 0.0D, 64.0D);

        restoreSettleTicks = builder
                .comment("读档时「世界已经回来」之后、开始淡出之前，等区块到位的**上限**（20 tick = 1 秒）。",
                        "正常情况下用不到这个上限：客户端已加载的区块数连续两 tick 不再增长就淡出，",
                        "所以读档完成后通常只还糊 0.1-0.2 秒（再加下面的淡出时长）；这个值只是大视距 / 卡顿时的兜底。")
                .defineInRange("restoreSettleTicks", DEFAULT_RESTORE_SETTLE_TICKS, 0, 200);

        builder.pop();
        container.registerConfig(ModConfig.Type.CLIENT, builder.build());

        modBus.addListener(ModConfigEvent.Loading.class, event -> apply());
        modBus.addListener(ModConfigEvent.Reloading.class, event -> apply());
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

    private static float read(ModConfigSpec.DoubleValue value, float fallback) {
        try {
            return value == null ? fallback : value.get().floatValue();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int readInt(ModConfigSpec.IntValue value, int fallback) {
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
}
