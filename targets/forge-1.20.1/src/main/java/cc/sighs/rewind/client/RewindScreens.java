package cc.sighs.rewind.client;

import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraftforge.client.event.ScreenEvent;

/**
 * 过渡期间的「无界面」处理。
 *
 * <p>回溯要走原版「关世界 → 重开世界」那条路，原版在这条路上会依次挂
 * {@code GenericDirtMessageScreen}（保存中）、{@code LevelLoadingScreen}（区块网格）、
 * {@code ReceivingLevelScreen}（下载地形）这些加载屏。过渡要求全程没有任何界面，所以在
 * {@link ScreenEvent.Opening} 里把它们拦掉——画面由 {@link RewindTransitionRenderer} 负责，
 * 玩家看到的是「画面糊住 → 换世界 → 化开」。拦截只在过渡进行期间生效；一旦过渡结束（或失败），
 * 原版行为完全恢复。
 *
 * <p>注意 {@code Minecraft.setScreen(null)} 在关世界过程中会抛异常（而且没有世界时它会把标题屏
 * 顶上来），所以关世界时仍然传一个屏对象进去，靠这里拦下它，既不开界面也不会踩到那些分支。
 *
 * <p><b>类名差异</b>：1.21.1 那份拦的是 {@code GenericMessageScreen}，1.20.1 里对应的类还叫
 * {@code GenericDirtMessageScreen}（不带泥土背景的 {@code GenericMessageScreen} 是 1.20.2 才有的），
 * 所以这里换掉。另外三个类名一致，而且在这个版本都仍然真的会被挂出来（对过反编译源码）：
 * {@code doWorldLoad} 挂 {@code LevelLoadingScreen}、{@code ClientPacketListener} 挂
 * {@code ReceivingLevelScreen}、{@code doWorldLoad} 的收尾与 {@code clearLevel()} 的默认值挂
 * {@code ProgressScreen}。
 */
public final class RewindScreens {
    private RewindScreens() {
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!RewindTransition.isActive()) {
            return;
        }
        // 后处理不可用时不要拦：让原版加载屏照常显示，总比露出一段黑屏好
        if (!RewindTransitionRenderer.isEffectAvailable()) {
            return;
        }
        if (event.getNewScreen() instanceof GenericDirtMessageScreen
                || event.getNewScreen() instanceof LevelLoadingScreen
                || event.getNewScreen() instanceof ProgressScreen
                || event.getNewScreen() instanceof ReceivingLevelScreen) {
            // Forge 1.20.1 的语义：post 返回 true（取消）时 Minecraft.setScreen 直接返回，
            // 既不会换屏也不会调旧屏的 removed()——正是我们要的「这一屏别开」
            event.setCanceled(true);
        }
    }
}
