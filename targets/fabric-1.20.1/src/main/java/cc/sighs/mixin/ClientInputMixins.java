package cc.sighs.mixin;

import cc.sighs.rewind.client.RewindClient;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原始按键输入，顶替 NeoForge 的 {@code InputEvent.Key}。
 *
 * <p>为什么非要原始按键：原版只在 {@code screen == null} 时给 {@code KeyMapping} 累计点击
 * （{@code KeyboardHandler} 里 {@code flag4 = screen == null}），所以在 GUI 里按 F7/F8/F9
 * 靠 {@code KeyMapping.consumeClick()} 是收不到的。挂在 {@code KeyboardHandler.keyPress} 的
 * HEAD（也就是原版自己处理这一下按键之前）就绕开了这条限制，和主版本拿到的时机一致。
 *
 * <p>只有 {@code action == PRESS} 才会进 {@link RewindClient#onRawKey}（和 {@code InputEvent.Key}
 * 一样只认按下），窗口号也在那边核对过。参数名对应原版签名
 * {@code keyPress(long window, int key, int scanCode, int action, int modifiers)}。
 *
 * <p>{@code require = 0}：挂不上就退化成「只有没开界面时热键才生效」。
 */
@Mixin(KeyboardHandler.class)
public abstract class ClientInputMixins {
    @Inject(method = "keyPress", at = @At("HEAD"), require = 0)
    private void rewind$rawKey(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        RewindClient.onRawKey(window, key, scanCode, action);
    }
}
