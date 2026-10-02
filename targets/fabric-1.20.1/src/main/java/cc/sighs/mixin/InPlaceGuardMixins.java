package cc.sighs.mixin;

import cc.sighs.rewind.Rewind;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原地回滚期间挡住「被回滚接管的区块被 ticket 系统重建」。
 *
 * <p>背景：玩家 ticket 一直认为这些区块「该加载」，而回滚把它们从内存里丢掉了。ticket 图的邻居传播
 * 随后会把 {@code ChunkMap.updateChunkScheduling} 又叫回来（{@code ChunkTicketTracker.setLevel} 发现
 * 「票说该是 31、当前却是未加载」就会重建 holder），重建出来的 holder 又会走一遍晋升流水线、拉起
 * worldgen 任务，把区块钉在正被卸载的状态上——{@code ChunkMap.processUnloads} 收不掉它，于是卸载
 * 永远跑不完（现象是 {@code stuck=} 一大堆、{@code unloadMs} 上千毫秒）。
 *
 * <p>只在 {@link Rewind#beginUnloadGuard} 登记过的位置、且是「重建」这一种情形下放行，降级 / 卸载
 * 完全不受影响；名单在回滚自己重建 holder 之前就清掉。
 *
 * <p><b>1.20.1</b>：{@code updateChunkScheduling(JILnet/minecraft/server/level/ChunkHolder;I)} 与
 * 1.21.1 同名同描述符（1.20.1 里是包私有、返回 {@code ChunkHolder}），{@code ChunkLevel.isLoaded(int)}
 * 也在。1.21.1 那份实现里提到的 {@code generationRefCount} 这个字段在 1.20.1 上不存在，
 * 但那只影响回滚引擎里的一条诊断日志，这里的守卫逻辑不需要它。
 */
public final class InPlaceGuardMixins {
    private InPlaceGuardMixins() {
    }

    @Mixin(ChunkMap.class)
    public abstract static class ChunkMapRecreationGuard {
        @Inject(
                method = "updateChunkScheduling(JILnet/minecraft/server/level/ChunkHolder;I)Lnet/minecraft/server/level/ChunkHolder;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$blockRecreationDuringRollback(long pos, int level, ChunkHolder holder, int oldLevel,
                CallbackInfoReturnable<ChunkHolder> cir) {
            if (holder == null && ChunkLevel.isLoaded(level) && Rewind.isRecreationBlocked(this, pos)) {
                cir.setReturnValue(null);
            }
        }
    }
}
