package cc.sighs.rewind.client;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import cc.sighs.rewind.Rewind;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 过渡参数的客户端配置，落在 {@code config/rewind-client.properties}。
 *
 * <p>两种过渡（存档的饱和度提高、读档的高斯模糊）的所有可调项都在这里，默认值与代码里原本写死的
 * 常量一致，所以不改配置就是原来的观感。
 *
 * <p>值只在配置加载时抄进 static 字段，读的时候就是普通字段访问——后处理每帧都要读它，
 * 不适合每帧去查一次配置表。配置没加载成功时字段保持默认值，过渡照常工作。
 *
 * <p><b>与 NeoForge 1.21.1 的差异</b>：Fabric 没有内置配置系统（那边是 {@code ModConfigSpec} 配
 * {@code ModConfigEvent.Loading/Reloading}），所以这里用一个最小的 {@link Properties} 实现顶上，
 * 键名与主版本的 TOML 逐项对应（只是打平成了 {@code transition.fadeInSeconds} 这种形式）。
 * 因此也**没有「配置文件被外部改动后自动重载」**：值在 {@link #register()} 时读一次，
 * 之后只有界面上的滑块（{@link #commitSaturationBoost} / {@link #commitBlurRadius}）会改它并落盘。
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

    /** 时长类配置项的合法范围（秒），与主版本 TOML 里的 defineInRange 一致。 */
    private static final double MIN_SECONDS = 0.01D;
    private static final double MAX_SECONDS = 5.0D;

    private static final String FILE_NAME = "rewind-client.properties";
    private static final String KEY_FADE_IN = "transition.fadeInSeconds";
    private static final String KEY_SATURATION_FADE_OUT = "transition.saturationFadeOutSeconds";
    private static final String KEY_SATURATION_BOOST = "transition.saturationBoost";
    private static final String KEY_BLUR_FADE_OUT = "transition.blurFadeOutSeconds";
    private static final String KEY_BLUR_RADIUS = "transition.blurRadius";
    private static final String KEY_RESTORE_SETTLE_TICKS = "transition.restoreSettleTicks";

    /** 写进文件开头的说明；一行一条，写的时候前面各加一个 {@code # }。 */
    private static final List<String> HEADER_LINES = Arrays.asList(
            "Rewind 的过渡参数（客户端）。存档 = 饱和度提高，读档 = 高斯模糊。",
            KEY_FADE_IN + "：淡入时长（秒），两种过渡共用。等它走完才真正开始动世界。",
            KEY_SATURATION_FADE_OUT + "：存档过渡的淡出时长（秒）。",
            KEY_SATURATION_BOOST + "：存档过渡满强度时的饱和度倍数（在 1 的基础上再加这么多）：1.5 = 2.5 倍。",
            KEY_BLUR_FADE_OUT + "：读档过渡的淡出时长（秒）。",
            KEY_BLUR_RADIUS + "：读档过渡满强度时的模糊半径（像素）。",
            KEY_RESTORE_SETTLE_TICKS + "：读档时「世界已经回来」之后、开始淡出之前等区块到位的上限（tick）。");

    /** 落盘时写的就是这几个值（界面上拖完滚动条立刻见效）。 */
    private static volatile float storedFadeInSeconds = DEFAULT_FADE_IN_SECONDS;
    private static volatile float storedSaturationFadeOutSeconds = DEFAULT_SATURATION_FADE_OUT_SECONDS;
    private static volatile float storedSaturationBoost = DEFAULT_SATURATION_BOOST;
    private static volatile float storedBlurFadeOutSeconds = DEFAULT_BLUR_FADE_OUT_SECONDS;
    private static volatile float storedBlurRadius = DEFAULT_BLUR_RADIUS;
    private static volatile int storedRestoreSettleTicks = DEFAULT_RESTORE_SETTLE_TICKS;

    private static float fadeInSeconds = DEFAULT_FADE_IN_SECONDS;
    private static float saturationFadeOutSeconds = DEFAULT_SATURATION_FADE_OUT_SECONDS;
    private static float saturationBoostValue = DEFAULT_SATURATION_BOOST;
    private static float blurFadeOutSeconds = DEFAULT_BLUR_FADE_OUT_SECONDS;
    private static float blurRadiusValue = DEFAULT_BLUR_RADIUS;
    private static int restoreSettleTicksValue = DEFAULT_RESTORE_SETTLE_TICKS;

    private RewindClientConfig() {
    }

    /**
     * 读配置。客户端入口初始化时调用一次。
     *
     * <p>文件不存在时写一份默认值出来（玩家一眼就能看到有什么可改的）；读不动就用默认值顶上，
     * 不让一次配置文件的 I/O 失败挡住整个模组初始化。
     */
    public static void register() {
        Path file = configFile();
        float fadeIn = DEFAULT_FADE_IN_SECONDS;
        float saturationFadeOut = DEFAULT_SATURATION_FADE_OUT_SECONDS;
        float saturationBoost = DEFAULT_SATURATION_BOOST;
        float blurFadeOut = DEFAULT_BLUR_FADE_OUT_SECONDS;
        float blurRadius = DEFAULT_BLUR_RADIUS;
        int restoreSettleTicks = DEFAULT_RESTORE_SETTLE_TICKS;
        try {
            if (file != null && Files.isRegularFile(file)) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                fadeIn = parseFloat(properties.getProperty(KEY_FADE_IN), DEFAULT_FADE_IN_SECONDS);
                saturationFadeOut = parseFloat(properties.getProperty(KEY_SATURATION_FADE_OUT),
                        DEFAULT_SATURATION_FADE_OUT_SECONDS);
                saturationBoost = parseFloat(properties.getProperty(KEY_SATURATION_BOOST),
                        DEFAULT_SATURATION_BOOST);
                blurFadeOut = parseFloat(properties.getProperty(KEY_BLUR_FADE_OUT),
                        DEFAULT_BLUR_FADE_OUT_SECONDS);
                blurRadius = parseFloat(properties.getProperty(KEY_BLUR_RADIUS), DEFAULT_BLUR_RADIUS);
                restoreSettleTicks = parseInt(properties.getProperty(KEY_RESTORE_SETTLE_TICKS),
                        DEFAULT_RESTORE_SETTLE_TICKS);
            }
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to read {}; falling back to the defaults", file, t);
            fadeIn = DEFAULT_FADE_IN_SECONDS;
            saturationFadeOut = DEFAULT_SATURATION_FADE_OUT_SECONDS;
            saturationBoost = DEFAULT_SATURATION_BOOST;
            blurFadeOut = DEFAULT_BLUR_FADE_OUT_SECONDS;
            blurRadius = DEFAULT_BLUR_RADIUS;
            restoreSettleTicks = DEFAULT_RESTORE_SETTLE_TICKS;
        }

        storedFadeInSeconds = (float) clamp(fadeIn, MIN_SECONDS, MAX_SECONDS);
        storedSaturationFadeOutSeconds = (float) clamp(saturationFadeOut, MIN_SECONDS, MAX_SECONDS);
        storedSaturationBoost = clamp(saturationBoost, MIN_SATURATION_BOOST, MAX_SATURATION_BOOST);
        storedBlurFadeOutSeconds = (float) clamp(blurFadeOut, MIN_SECONDS, MAX_SECONDS);
        storedBlurRadius = clamp(blurRadius, MIN_BLUR_RADIUS, MAX_BLUR_RADIUS);
        storedRestoreSettleTicks = Math.max(0, Math.min(200, restoreSettleTicks));
        apply();
        // 无论刚才是读出来的还是兜底出来的，都写回去一次：文件缺失时补上，值被夹过时纠正
        write();
    }

    private static void apply() {
        fadeInSeconds = storedFadeInSeconds;
        saturationFadeOutSeconds = storedSaturationFadeOutSeconds;
        saturationBoostValue = storedSaturationBoost;
        blurFadeOutSeconds = storedBlurFadeOutSeconds;
        blurRadiusValue = storedBlurRadius;
        restoreSettleTicksValue = storedRestoreSettleTicks;
        Rewind.LOGGER.info(
                "Rewind: transition config fadeIn={}s saturationFadeOut={}s saturationBoost={} blurFadeOut={}s blurRadius={} restoreSettleTicks={}",
                fadeInSeconds, saturationFadeOutSeconds, saturationBoostValue, blurFadeOutSeconds, blurRadiusValue,
                restoreSettleTicksValue);
    }

    private static float parseFloat(String raw, float fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            Rewind.LOGGER.warn("Rewind: \"{}\" is not a valid transition value; using the default {}", raw, fallback);
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            Rewind.LOGGER.warn("Rewind: \"{}\" is not a valid transition value; using the default {}", raw, fallback);
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
     * <p>滚动条拖一次会来一串 {@code input} 事件，每个都落盘会刷一屏日志。拖完（{@code change}）
     * 再 {@link #commitSaturationBoost}。
     */
    public static void previewSaturationBoost(float boost) {
        saturationBoostValue = clamp(boost, MIN_SATURATION_BOOST, MAX_SATURATION_BOOST);
    }

    /** 「存档过渡强度」拖完了：写进配置并落盘。后处理每帧读的就是这个 static 字段，所以立刻见效。 */
    public static void commitSaturationBoost(float boost) {
        previewSaturationBoost(boost);
        storedSaturationBoost = saturationBoostValue;
        write();
        Rewind.LOGGER.info("Rewind: transition saturationBoost={} (set)", saturationBoostValue);
    }

    /** 「读档过渡强度」拖动中：只改内存里的值，不落盘。 */
    public static void previewBlurRadius(float radius) {
        blurRadiusValue = clamp(radius, MIN_BLUR_RADIUS, MAX_BLUR_RADIUS);
    }

    /** 「读档过渡强度」拖完了：写进配置并落盘。 */
    public static void commitBlurRadius(float radius) {
        previewBlurRadius(radius);
        storedBlurRadius = blurRadiusValue;
        write();
        Rewind.LOGGER.info("Rewind: transition blurRadius={} (set)", blurRadiusValue);
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
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

    /** 把内存里的值写回配置文件；写不动就只写一条日志（值本身已经在内存里生效了）。 */
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
        lines.add(KEY_FADE_IN + "=" + storedFadeInSeconds);
        lines.add(KEY_SATURATION_FADE_OUT + "=" + storedSaturationFadeOutSeconds);
        lines.add(KEY_SATURATION_BOOST + "=" + storedSaturationBoost);
        lines.add(KEY_BLUR_FADE_OUT + "=" + storedBlurFadeOutSeconds);
        lines.add(KEY_BLUR_RADIUS + "=" + storedBlurRadius);
        lines.add(KEY_RESTORE_SETTLE_TICKS + "=" + storedRestoreSettleTicks);
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
