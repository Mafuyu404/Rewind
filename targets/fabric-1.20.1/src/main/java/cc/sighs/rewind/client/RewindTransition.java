package cc.sighs.rewind.client;

import net.minecraft.util.Mth;

/**
 * 存档 / 回溯的屏幕过渡：一个纯粹的「效果强度」包络，0 → 1 → 0。
 *
 * <p>没有任何界面参与：强度只由 {@link RewindTransitionRenderer} 每帧读一次，用来驱动后处理
 * （存档是饱和度提高，回溯是高斯模糊）。用真实时间推进而不是游戏 tick，这样服务端被冻结时
 * 包络仍然按墙钟时间走完。
 *
 * <p>各段时长按效果区分，全部来自 {@link RewindClientConfig}（{@code config/rewind-client.properties}）。
 *
 * <p>使用方式：
 * <pre>
 * start(SATURATION)   → 淡入
 * （在淡入结束时做事；真正的等待用 hold()/keepAlive() 撑住强度）
 * finish()            → 淡出，结束后自动回到 NONE
 * </pre>
 */
public final class RewindTransition {
    public enum Effect {
        NONE,
        /** 存档：饱和度提高。 */
        SATURATION,
        /** 读档：高斯模糊。 */
        GAUSSIAN_BLUR
    }

    private enum Stage {
        IDLE,
        FADE_IN,
        HOLD,
        FADE_OUT
    }

    /** 保持阶段的安全上限：任何异常路径都不该让屏幕一直保持过渡效果。这不是可调项。 */
    private static final float HOLD_LIMIT_SECONDS = 25.0F;

    private static Effect effect = Effect.NONE;
    private static Stage stage = Stage.IDLE;
    private static float elapsedSeconds;
    private static float strength;

    private RewindTransition() {
    }

    public static Effect effect() {
        return effect;
    }

    public static boolean isActive() {
        return stage != Stage.IDLE;
    }

    /** 0（无效果）→ 1（满强度）。 */
    public static float strength() {
        return strength;
    }

    /** 淡入是否已经到达满强度——真正危险的动作应该等到这一刻再动手。 */
    public static boolean isFadeInDone() {
        return stage == Stage.HOLD || stage == Stage.FADE_OUT;
    }

    public static void start(Effect newEffect) {
        effect = newEffect;
        stage = Stage.FADE_IN;
        elapsedSeconds = 0.0F;
        strength = 0.0F;
        cc.sighs.rewind.Rewind.LOGGER.info("Rewind: transition {} started", newEffect);
    }

    public static void finish() {
        if (stage == Stage.IDLE) {
            return;
        }
        stage = Stage.FADE_OUT;
        elapsedSeconds = 0.0F;
    }

    /** 立刻结束（失败路径兜底）。 */
    public static void abort() {
        if (stage != Stage.IDLE) {
            cc.sighs.rewind.Rewind.LOGGER.info("Rewind: transition {} ended", effect);
        }
        effect = Effect.NONE;
        stage = Stage.IDLE;
        elapsedSeconds = 0.0F;
        strength = 0.0F;
    }

    public static void tick(float deltaSeconds) {
        if (stage == Stage.IDLE) {
            return;
        }
        elapsedSeconds += deltaSeconds;
        switch (stage) {
            case FADE_IN: {
                float progress = Mth.clamp(elapsedSeconds / RewindClientConfig.fadeInSeconds(), 0.0F, 1.0F);
                strength = ease(progress);
                if (progress >= 1.0F) {
                    stage = Stage.HOLD;
                    elapsedSeconds = 0.0F;
                    strength = 1.0F;
                }
                return;
            }
            case HOLD: {
                strength = 1.0F;
                if (elapsedSeconds > HOLD_LIMIT_SECONDS) {
                    // 不该发生；宁可放弃效果也不要让画面一直糊着
                    abort();
                }
                return;
            }
            case FADE_OUT: {
                float fadeOut = effect == Effect.GAUSSIAN_BLUR
                        ? RewindClientConfig.blurFadeOutSeconds()
                        : RewindClientConfig.saturationFadeOutSeconds();
                float progress = Mth.clamp(elapsedSeconds / fadeOut, 0.0F, 1.0F);
                strength = 1.0F - ease(progress);
                if (progress >= 1.0F) {
                    abort();
                }
                return;
            }
            default:
        }
    }

    /** 平滑一点的缓动，避免效果突然出现。 */
    private static float ease(float t) {
        return t * t * (3.0F - 2.0F * t);
    }
}
