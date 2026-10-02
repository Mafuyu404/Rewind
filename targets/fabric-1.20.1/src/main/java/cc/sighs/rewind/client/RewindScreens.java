package cc.sighs.rewind.client;

import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * 过渡期间的「无界面」处理。
 *
 * <p>回溯要走原版「关世界 → 重开世界」那条路，原版在这条路上会依次挂
 * {@code GenericDirtMessageScreen}（保存中）、{@code ProgressScreen}、{@code LevelLoadingScreen}（区块网格）、
 * {@code ReceivingLevelScreen}（下载地形）这些加载屏。过渡要求全程没有任何界面，所以把它们拦掉——
 * 画面由 {@link RewindTransitionRenderer} 负责，玩家看到的是「画面糊住 → 换世界 → 化开」。
 * 拦截只在过渡进行期间生效；一旦过渡结束（或失败），原版行为完全恢复。
 *
 * <p>注意 {@code Minecraft.setScreen(null)} 在关世界过程中会把玩家丢到标题屏
 * （1.20.1 的 {@code setScreen} 在「新屏为 null 且没有世界」时会新建一个 {@code TitleScreen}），
 * 所以关世界时仍然传一个屏对象进去，靠这里拦下它——既不开界面，也不会踩到那条分支。
 *
 * <p><b>与 NeoForge 1.21.1 的差异</b>：
 * <ul>
 *   <li>Fabric 没有「可取消的开屏事件」（{@code ScreenEvent.Opening}），改由
 *       {@code cc.sighs.mixin.ClientScreenMixins} 注入 {@code Minecraft.setScreen(Screen)} 的 HEAD，
 *       取消语义与原事件一致（被取消时原屏不会被 {@code removed()}，新屏也不会挂上）。</li>
 *   <li>1.20.1 没有 {@code GenericMessageScreen}（那是 1.20.5+ 的名字），对应物是
 *       {@code GenericDirtMessageScreen}——原版「保存并退出」用的就是它。</li>
 * </ul>
 */
public final class RewindScreens {
    private RewindScreens() {
    }

    /**
     * 这一次 {@code setScreen} 要不要拦下来。
     *
     * <p>由 {@code ClientScreenMixins} 在 {@code Minecraft.setScreen} 的 HEAD 调用，
     * 返回 true 就取消这次开屏。
     */
    public static boolean blocks(Screen newScreen) {
        if (newScreen == null || !RewindTransition.isActive()) {
            return false;
        }
        // 后处理不可用时不要拦：让原版加载屏照常显示，总比露出一段黑屏好
        if (!RewindTransitionRenderer.isEffectAvailable()) {
            return false;
        }
        return newScreen instanceof GenericDirtMessageScreen
                || newScreen instanceof LevelLoadingScreen
                || newScreen instanceof ProgressScreen
                || newScreen instanceof ReceivingLevelScreen;
    }
}
