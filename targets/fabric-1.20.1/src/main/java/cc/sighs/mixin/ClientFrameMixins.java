package cc.sighs.mixin;

import cc.sighs.rewind.client.RewindClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 帧开始 / 帧结束两个回调点，顶替 NeoForge 的 {@code RenderFrameEvent.Pre} / {@code Post}。
 *
 * <p>Fabric 没有「整帧渲染」事件，只能自己挂到 {@code Minecraft.runTick(boolean)} 上。
 * 1.20.1 的 runTick（已按字节码核对）大致是：
 *
 * <pre>
 *   RenderSystem.clear(...)
 *   mainRenderTarget.bindWrite(true)          ← ① 帧从这里开始
 *   FogRenderer.setupNoFog()
 *   if (!noRender) gameRenderer.render(...)   ← 世界 / HUD / 当前界面都在这里画完
 *   if (fpsPieResults != null) { ... }        ← F3 的帧率饼图
 *   profiler.push("blit")
 *   mainRenderTarget.unbindWrite()            ← ② 帧到此结束，再往后才 blitToScreen
 *   mainRenderTarget.blitToScreen(w, h)
 *   ...
 * </pre>
 *
 * <p>主版本的两个事件就在这两个位置：{@code Pre} 是「这一帧还没画」，
 * {@code Post} 是「这一帧画完了但还没 blit 到屏幕」。所以这里分别注入
 * {@code bindWrite} 之前（①）与 {@code unbindWrite} 之前（②）——都是无条件执行到的语句
 * （不像 {@code GameRenderer.render} 会被 {@code noRender} 跳过）。
 *
 * <p>两个注入都带 {@code require = 0}：万一将来 runTick 的形态变了、这两条 invoke 对不上，
 * 退化成「过渡与封面都不工作」（画面回到原版行为），而不是启动崩溃。
 *
 * <p>另外 {@code Minecraft.clearLevel(...)} / {@code updateScreenAndTick(...)} 会反复调
 * {@code runTick(false)} 忙等集成服务器线程结束，那时这两个钩子照样会被调到——
 * 这正是「世界正在被拆掉的那段也要继续出画」所需要的（和主版本走同一条原版循环）。
 */
@Mixin(Minecraft.class)
public abstract class ClientFrameMixins {
    @Inject(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"),
            require = 0)
    private void rewind$framePre(boolean renderLevel, CallbackInfo ci) {
        RewindClient.onFramePre((Minecraft) (Object) this);
    }

    @Inject(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;unbindWrite()V"),
            require = 0)
    private void rewind$framePost(boolean renderLevel, CallbackInfo ci) {
        RewindClient.onFramePost((Minecraft) (Object) this);
    }
}
