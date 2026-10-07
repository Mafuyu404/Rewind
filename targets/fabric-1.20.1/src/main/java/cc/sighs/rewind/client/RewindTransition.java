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
        GAUSSIAN_BLUR,
        /** 死亡回溯：高斯模糊 + 视野红边。 */
        DEATH
    }

    private enum Stage {
        IDLE,
        FADE_IN,
        HOLD,
        FADE_OUT
    }

    /** 保持阶段的安全上限：任何异常路径都不该让屏幕一直保持过渡效果。这不是可调项。 */
    private static final float HOLD_LIMIT_SECONDS = 25.0F;

    /** 死亡回溯：刚开始那一下红铺开的范围（以半屏高为单位）；别太大，中间要留出视野。 */
    private static final float DEATH_REACH_FLASH = 0.72F;
    /** 死亡回溯：退居成红边后留在边上的铺开程度。 */
    private static final float DEATH_REACH_HOLD = 0.30F;
    /** 死亡回溯：从那一大片退到红边要多久（秒）。 */
    private static final float DEATH_REACH_RETREAT_SECONDS = 0.35F;
    /** 死亡回溯的红边包络：回溯结束后淡出用多久（秒），要明显短于模糊的淡出。 */
    private static final float DEATH_REACH_FADE_OUT_SECONDS = 0.25F;

    private static Effect effect = Effect.NONE;
    private static Stage stage = Stage.IDLE;
    private static float elapsedSeconds;
    private static float strength;
    private static float deathReach;
    private static float deathElapsedSeconds;

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
        deathElapsedSeconds = 0.0F;
        deathReach = newEffect == Effect.DEATH ? DEATH_REACH_FLASH : 0.0F;
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
        deathReach = 0.0F;
        deathElapsedSeconds = 0.0F;
    }

    public static void tick(float deltaSeconds) {
        if (stage == Stage.IDLE) {
            return;
        }
        elapsedSeconds += deltaSeconds;
        deathElapsedSeconds += deltaSeconds;
        tickDeathReach();
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
                float fadeOut;
                if (effect == Effect.GAUSSIAN_BLUR) {
                    fadeOut = RewindClientConfig.blurFadeOutSeconds();
                } else if (effect == Effect.DEATH) {
                    fadeOut = RewindClientConfig.deathFadeOutSeconds();
                } else {
                    fadeOut = RewindClientConfig.saturationFadeOutSeconds();
                }
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

    private static void tickDeathReach() {
        if (effect != Effect.DEATH) {
            deathReach = 0.0F;
            return;
        }
        if (stage == Stage.FADE_OUT) {
            float out = Mth.clamp(elapsedSeconds / DEATH_REACH_FADE_OUT_SECONDS, 0.0F, 1.0F);
            // 平方衰减：一开始收得快、末尾轻轻收干净（线性会很机械）
            deathReach = DEATH_REACH_HOLD * (1.0F - out) * (1.0F - out);
            return;
        }
        float retreat = Mth.clamp(deathElapsedSeconds / DEATH_REACH_RETREAT_SECONDS, 0.0F, 1.0F);
        // 同样平方衰减：先从「一大片」快速退回红边，再慢慢贴到位
        deathReach = DEATH_REACH_HOLD + (DEATH_REACH_FLASH - DEATH_REACH_HOLD)
                * (1.0F - retreat) * (1.0F - retreat);
    }

    /** 死亡回溯：红边从屏幕四边往里铺到多深（单位是半屏高；1 = 铺到上/下边缘，0 = 没有）。 */
    public static float deathReach() {
        return deathReach;
    }

    /** 平滑一点的缓动，避免效果突然出现。 */
    private static float ease(float t) {
        return t * t * (3.0F - 2.0F * t);
    }
}
