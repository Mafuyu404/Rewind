package cc.sighs.mixin;

import java.util.function.BooleanSupplier;
import cc.sighs.rewind.server.AutoCheckpoint;
import cc.sighs.rewind.server.RewindServerConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「跟着原版自动保存建点」的挂点，外加**改原版的自动保存间隔**。
 *
 * <p>原版自动保存是 {@code MinecraftServer.tickServer} 里的 {@code saveEverything(true, false, false)}
 * ——整个原版只有它这么传参：{@code stopServer} 走的是 {@code saveAllChunks(false, true, false)}，
 * 我们自己的 {@code WorldFlush} 走 {@code saveEverything(true, true, true)}，而暂停游戏那次走的是
 * {@code saveEverything(false, false, false)}。所以在返回处认这三个参数就等于认出了自动保存。
 *
 * <p>挑「{@code saveEverything} 的返回处」而不是 {@code tickServer} 里那一句的调用位置，是为了能测：
 * 自测直接调一次 {@code saveEverything(true, false, false)} 就走完整条路，不用等五分钟一次的真自动保存。
 * 代价是别的模组要是也用这三个参数调 {@code saveEverything}，也会跟着建一次点——那种调用本身就是
 * 「按自动保存的方式存一遍」，跟着建点并不算错。
 *
 * <p>26.1 上的三个挂点都核对过 26.1 的反编译源码：
 * {@code saveEverything(ZZZ)Z}、{@code computeNextAutosaveInterval()I}（private）、
 * {@code tickServer(BooleanSupplier)V}（protected）以及 private 字段 {@code ticksUntilAutosave} 都还在，
 * 签名与 1.21.1 一致，所以这里保持严格注入（{@code require} 用 mixins.json 的默认值 1），不做软挂降级。
 */
public final class AutoCheckpointMixins {
    private AutoCheckpointMixins() {
    }

    @Mixin(MinecraftServer.class)
    public abstract static class MinecraftServerAutosave {
        /** 原版自己那个自动保存间隔：{@code tickrate(20) * 300 秒} = 5 分钟。 */
        private static final int VANILLA_AUTOSAVE_TICKS = 5 * 20 * 60;

        /** 原版「距离下次自动保存还有多少 tick」的倒计时。 */
        @Shadow
        private int ticksUntilAutosave;

        @Inject(method = "saveEverything(ZZZ)Z", at = @At("RETURN"))
        private void rewind$autoCheckpointAfterAutosave(boolean suppressLog, boolean flush, boolean forced,
                CallbackInfoReturnable<Boolean> cir) {
            if (suppressLog && !flush && !forced) {
                AutoCheckpoint.onVanillaAutosave((MinecraftServer) (Object) this);
            }
        }

        /**
         * 把原版算出来的自动保存间隔换成配置里的值。
         *
         * <p>原版那句是 {@code max(100, (int)(ticksPerSecond * 300))}——那个 300 是**秒**，也就是 5 分钟。
         * 这里按「配置值 / 5 分钟」等比缩放，而不是直接返回一个固定 tick 数：原版在**冲刺**时
         * （tickrate 被拉高、追赶落后进度）会算出更短的间隔来防止内存涨爆，等比缩放能把这个行为一起留下。
         */
        @Inject(method = "computeNextAutosaveInterval", at = @At("RETURN"), cancellable = true)
        private void rewind$scaleAutosaveInterval(CallbackInfoReturnable<Integer> cir) {
            int ticks = RewindServerConfig.autoSaveIntervalTicks();
            if (ticks <= 0) {
                return;
            }
            double scale = ticks / (double) VANILLA_AUTOSAVE_TICKS;
            cir.setReturnValue(Math.max(100, (int) Math.round(cir.getReturnValue() * scale)));
        }

        /**
         * 让设置**立刻**生效：世界刚开（或者间隔刚被改小）的时候，倒计时还停在原版那个 5 分钟上，
         * 所以每 tick 把它压到配置值以内。只压不抬——原版冲刺时算出来的更短间隔要留着。
         *
         * <p>代价是「把间隔改大」不会立刻把已经跑着的倒计时拉长，那一次自动保存会来得比配置值早一点，
         * 之后才按新值走。为了不在每 tick 上分辨「这个倒计时是谁设的」，这个偏差就认了。
         */
        @Inject(method = "tickServer", at = @At("HEAD"))
        private void rewind$clampAutosaveCountdown(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
            int ticks = RewindServerConfig.autoSaveIntervalTicks();
            if (ticks > 0 && this.ticksUntilAutosave > ticks) {
                this.ticksUntilAutosave = ticks;
            }
        }
    }
}
