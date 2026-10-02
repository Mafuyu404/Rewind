package cc.sighs.mixin;

import cc.sighs.rewind.client.RewindScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「过渡期间不许出现任何界面」的挂点，顶替 NeoForge 的 {@code ScreenEvent.Opening}。
 *
 * <p>Fabric 没有可取消的开屏事件，所以直接拦 {@code Minecraft.setScreen(Screen)}：过渡进行期间
 * （且后处理可用时）把原版的加载屏取消掉，判定在 {@link RewindScreens#blocks}。
 *
 * <p>取消的语义与原事件一致：**整个方法体都不执行**——原屏不会走 {@code removed()}，新屏也不会挂上，
 * 当前那个「什么都不画的逻辑屏」原地留着。要是只把参数换成 null 就糟糕了：
 * 1.20.1 的 {@code setScreen} 在「新屏为 null 且没有世界」时**会自己造一个 TitleScreen**。
 *
 * <p>{@code require = 0}：挂不上就退化成「原版加载屏照常显示」，功能少一块但不崩游戏。
 */
@Mixin(Minecraft.class)
public abstract class ClientScreenMixins {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void rewind$blockLoadingScreens(Screen newScreen, CallbackInfo ci) {
        if (RewindScreens.blocks(newScreen)) {
            ci.cancel();
        }
    }
}
