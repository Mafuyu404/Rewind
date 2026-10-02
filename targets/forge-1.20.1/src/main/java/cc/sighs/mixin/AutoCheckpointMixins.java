package cc.sighs.mixin;

import cc.sighs.rewind.server.AutoCheckpoint;
import cc.sighs.rewind.server.RewindServerConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「跟着原版自动保存建点」的挂点，外加**改原版的自动保存间隔**。
 *
 * <p>原版自动保存是 {@code MinecraftServer.tickServer} 里的 {@code saveEverything(true, false, false)}
 * ——整个原版只有它这么传参：{@code stopServer} 走的是 {@code saveAllChunks(false, true, false)}，
 * 我们自己的 {@code WorldFlush} 走 {@code saveEverything(true, true, true)}。所以在返回处认这三个参数
 * 就等于认出了自动保存。
 *
 * <p>挑「{@code saveEverything} 的返回处」而不是 {@code tickServer} 里那一句的调用位置，是为了能测：
 * 自测直接调一次 {@code saveEverything(true, false, false)} 就走完整条路，不用等五分钟一次的真自动保存。
 * 代价是别的模组要是也用这三个参数调 {@code saveEverything}，也会跟着建一次点——那种调用本身就是
 * 「按自动保存的方式存一遍」，跟着建点并不算错。
 *
 * <p>与 NeoForge 1.21.1 那份的差别——那条「按比例缩放 {@code computeNextAutosaveInterval()}」和
 * 「每 tick 把 {@code ticksUntilAutosave} 倒计时压到配置值以内」的注入，1.20.1 **一个都对不上**：
 * 那个版本既没有 {@code computeNextAutosaveInterval()}，也没有倒计时字段，自动保存的判据是
 * {@code tickServer} 里写死的常量——
 * <pre>
 * if (this.tickCount % 6000 == 0) { ... this.saveEverything(true, false, false); ... }
 * </pre>
 * 那个 {@code 6000} 就是 {@code tickrate(20) × 300 秒} = 5 分钟。所以「改间隔」在这里落成
 * <b>替换那一个常量</b>（{@link MinecraftServerAutosave#rewind$scaleAutosaveInterval}），
 * 判据的形态不变、也不引入任何新状态。
 */
public final class AutoCheckpointMixins {
    private AutoCheckpointMixins() {
    }

    @Mixin(MinecraftServer.class)
    public abstract static class MinecraftServerAutosave {
        /** 1.20.1 {@code tickServer} 里写死的自动保存周期（= 5 分钟 = {@code 20 tps × 300 秒}）。 */
        private static final int VANILLA_AUTOSAVE_TICKS = 5 * 20 * 60;

        @Inject(method = "saveEverything(ZZZ)Z", at = @At("RETURN"))
        private void rewind$autoCheckpointAfterAutosave(boolean suppressLog, boolean flush, boolean forced,
                CallbackInfoReturnable<Boolean> cir) {
            if (suppressLog && !flush && !forced) {
                AutoCheckpoint.onVanillaAutosave((MinecraftServer) (Object) this);
            }
        }

        /**
         * 把原版那个写死的自动保存周期换成配置里的值（tick）。
         *
         * <p>只替换那一个常量，不改判据本身：仍然是「累计 tick 数取模」，也就是仍然按固定周期走。
         * 副作用与 NeoForge 那边一致——把间隔改大不会缩短已经在跑的那一段等待，下一次触发点按新的模数算。
         *
         * <p>{@code require = 0}：这个常量是原版内部实现细节，万一上游换了写法（或别的模组也改了这里），
         * 注入不到就退回原版 5 分钟，不该让整个模组加载失败。
         *
         * <p>配置关着「跟着原版自动保存建点」时 {@link RewindServerConfig#autoSaveIntervalTicks()} 返回 0，
         * 这时原样放行，原版保持它自己的 5 分钟。
         */
        @ModifyConstant(method = "tickServer", constant = @Constant(intValue = VANILLA_AUTOSAVE_TICKS), require = 0)
        private int rewind$scaleAutosaveInterval(int original) {
            int ticks = RewindServerConfig.autoSaveIntervalTicks();
            return ticks > 0 ? ticks : original;
        }
    }
}
