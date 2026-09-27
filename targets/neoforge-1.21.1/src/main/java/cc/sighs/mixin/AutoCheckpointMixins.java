package cc.sighs.mixin;

import cc.sighs.rewind.server.AutoCheckpoint;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「跟着原版自动保存建点」的挂点。
 *
 * <p>原版自动保存是 {@code MinecraftServer.tickServer} 里的 {@code saveEverything(true, false, false)}
 * ——整个原版只有它这么传参：{@code stopServer} 走的是 {@code saveAllChunks(false, true, false)}，
 * 我们自己的 {@code WorldFlush} 走 {@code saveEverything(true, true, true)}。所以在返回处认这三个
 * 参数就等于认出了自动保存。
 *
 * <p>挑「{@code saveEverything} 的返回处」而不是 {@code tickServer} 里那一句的调用位置，是为了能测：
 * 自测直接调一次 {@code saveEverything(true, false, false)} 就走完整条路，不用等五分钟一次的真自动保存。
 * 代价是别的模组要是也用这三个参数调 {@code saveEverything}，也会跟着建一次点——那种调用本身就是
 * 「按自动保存的方式存一遍」，跟着建点并不算错。
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
    }
}
