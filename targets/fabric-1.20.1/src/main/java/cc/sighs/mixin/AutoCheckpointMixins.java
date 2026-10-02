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
 * ——1.20.1 整个原版只有它这么传参：{@code stopServer} 走的是 {@code saveAllChunks(false, true, false)}，
 * 暂停游戏那次走 {@code saveEverything(false, false, false)}，{@code /save-all} 走的是
 * {@code saveEverything(true, <flush>, true)}，我们自己的 {@code WorldFlush} 走
 * {@code saveEverything(true, true, true)}。所以在返回处认这三个参数就等于认出了自动保存。
 *
 * <p>挑「{@code saveEverything} 的返回处」而不是 {@code tickServer} 里那一句的调用位置，是为了能测：
 * 自测直接调一次 {@code saveEverything(true, false, false)} 就走完整条路，不用等五分钟一次的真自动保存。
 * 代价是别的模组要是也用这三个参数调 {@code saveEverything}，也会跟着建一次点——那种调用本身就是
 * 「按自动保存的方式存一遍」，跟着建点并不算错。
 *
 * <p><b>与 NeoForge 1.21.1 的差异</b>：1.20.1 的 {@code MinecraftServer} 既没有
 * {@code computeNextAutosaveInterval()}，也没有 {@code ticksUntilAutosave} 字段——自动保存的节奏写死在
 * {@code tickServer} 里的 {@code if (this.tickCount % 6000 == 0)}（{@code AUTOSAVE_INTERVAL} 是
 * {@code private static final int}，常量在编译期就内联成了 {@code sipush 6000}，整个方法里只出现一次）。
 * 所以那边「等比缩放 + 每 tick 压倒计时」两个注入在这里换成**改那一个常量**：
 * 配置关着时原样返回（原版该多久存一次还是多久），开着时直接返回配置值的 tick 数。1.20.1 的原版本来
 * 就没有「冲刺时缩短间隔」这种行为，不需要等比缩放去保留它；{@code tickCount % 间隔} 的判定也让
 * 「改了设置」下一次命中就生效，不需要再守一个倒计时字段。
 *
 * <p>这个改常量的注入带 {@code require = 0}：万一将来这个方法的形态变了、常量对不上，
 * 退化成「间隔设置不生效」而不是启动崩溃。
 */
public final class AutoCheckpointMixins {
    private AutoCheckpointMixins() {
    }

    @Mixin(MinecraftServer.class)
    public abstract static class MinecraftServerAutosave {
        @Inject(method = "saveEverything(ZZZ)Z", at = @At("RETURN"))
        private void rewind$autoCheckpointAfterAutosave(boolean suppressLog, boolean flush, boolean forced,
                CallbackInfoReturnable<Boolean> cir) {
            if (suppressLog && !flush && !forced) {
                AutoCheckpoint.onVanillaAutosave((MinecraftServer) (Object) this);
            }
        }

        /**
         * 把 {@code tickServer} 里那个写死的自动保存间隔（6000 tick = 5 分钟）换成配置里的值。
         *
         * <p>{@code require = 0}：挂不上就当没这回事，功能缺失但不崩游戏。
         */
        @ModifyConstant(method = "tickServer", constant = @Constant(intValue = 6000), require = 0)
        private int rewind$configuredAutosaveInterval(int vanillaTicks) {
            int ticks = RewindServerConfig.autoSaveIntervalTicks();
            return ticks > 0 ? ticks : vanillaTicks;
        }
    }
}
