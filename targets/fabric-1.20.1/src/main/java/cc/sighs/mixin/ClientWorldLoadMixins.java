package cc.sighs.mixin;

import cc.sighs.rewind.client.CheckpointController;
import net.minecraft.client.Minecraft;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「推进来的世界是从哪来的」——快速重启要复用的注册表 / 数据包资源从这儿抄一份。
 *
 * <p>主版本（NeoForge 1.21.1）在回滚前从活着的集成服务器上抄：
 * {@code server.getServerResources().managers()} + {@code server.registries()} + {@code server.getWorldData()}。
 * 1.20.1 的 {@code MinecraftServer} 没有公开入口能拿到 {@code ReloadableServerResources}
 * （它的字段类型 {@code MinecraftServer$ReloadableResources} 是包私有的，也没有 getter），
 * 所以改成在 {@code Minecraft.doWorldLoad(...)} 打开世界那一刻从 {@link WorldStem} 上抄——
 * {@code MinecraftServer} 的构造本来就是把这三样从 WorldStem 里取出来的，值完全等价，
 * 而它们在 {@code stopServer} 里都不会被关。
 *
 * <p>{@code require = 0}：挂不上时那三样保持 null，{@code tryFastRestart} 会直接放弃、
 * 退回原版 {@code WorldOpenFlows.loadLevel} 那条路。
 */
@Mixin(Minecraft.class)
public abstract class ClientWorldLoadMixins {
    @Inject(method = "doWorldLoad", at = @At("HEAD"), require = 0)
    private void rewind$captureWorldStem(String levelId, LevelStorageSource.LevelStorageAccess access,
            PackRepository packs, WorldStem stem, boolean isNewWorld, CallbackInfo ci) {
        CheckpointController.captureWorldStem(stem);
    }
}
