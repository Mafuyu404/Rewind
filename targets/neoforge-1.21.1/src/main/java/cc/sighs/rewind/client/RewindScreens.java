package cc.sighs.rewind.client;

import cc.sighs.rewind.Rewind;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 过渡期间的「无界面」处理。
 *
 * <p>回溯要走原版「关世界 → 重开世界」那条路，原版在这条路上会依次挂 {@code GenericMessageScreen}
 * （保存中）、{@code LevelLoadingScreen}（区块网格）、{@code ReceivingLevelScreen}（下载地形）
 * 这些加载屏。过渡要求全程没有任何界面，所以在 {@link ScreenEvent.Opening} 里把它们拦掉——
 * 画面由 {@link RewindTransitionRenderer} 负责，玩家看到的是「画面糊住 → 换世界 → 化开」。
 * 拦截只在过渡进行期间生效；一旦过渡结束（或失败），原版行为完全恢复。
 *
 * <p>注意 {@code Minecraft.setScreen(null)} 在关世界过程中会抛异常，所以关世界时仍然传一个屏对象进去，
 * 靠这里拦下它，既不开界面也不会踩到那个断言。
 */
public final class RewindScreens {
    private RewindScreens() {
    }

    /** 满强度时要拉到的视场角：比原版滑块上限（110）再大一点，保证「拉到最大」一定看得出来。 */
    private static final double FOV_MAX = 120.0D;

    private static boolean fovLogged;

    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!RewindTransition.isActive()) {
            return;
        }
        // 后处理不可用时不要拦：让原版加载屏照常显示，总比露出一段黑屏好
        if (!RewindTransitionRenderer.isEffectAvailable()) {
            return;
        }
        if (event.getNewScreen() instanceof GenericMessageScreen
                || event.getNewScreen() instanceof LevelLoadingScreen
                || event.getNewScreen() instanceof ProgressScreen
                || event.getNewScreen() instanceof ReceivingLevelScreen) {
            event.setCanceled(true);
        }
    }

    /**
     * 存档的「广角」过渡：直接把客户端摄像机的视场角拉到最大，不做后处理。
     * 这里改的是每帧计算出来的 FOV、不动玩家的设置，所以效果结束就自动恢复。
     */
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (RewindTransition.effect() != RewindTransition.Effect.WIDE_ANGLE) {
            return;
        }
        float strength = RewindTransition.strength();
        if (strength <= 0.0F) {
            fovLogged = false;
            return;
        }
        double fov = event.getFOV();
        if (fov <= 0.0D) {
            return;
        }
        double target = Math.max(FOV_MAX, fov);
        double widened = fov + (target - fov) * strength;
        event.setFOV(widened);
        if (!fovLogged && strength >= 0.95F) {
            fovLogged = true;
            Rewind.LOGGER.info("Rewind: wide-angle fov {} -> {}", String.format("%.1f", fov),
                    String.format("%.1f", widened));
        }
    }
}
