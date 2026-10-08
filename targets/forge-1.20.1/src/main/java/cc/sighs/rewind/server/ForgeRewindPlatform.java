package cc.sighs.rewind.server;

import java.nio.file.Path;
import cc.sighs.rewind.common.spi.FlushOutcome;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RollbackOutcome;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Forge 1.20.1 的 {@link RewindPlatform} 实现：把 common 需要知道的全部平台能力接进来。
 *
 * <p>这里是「版本差异」的唯一落点——common 侧的建点 / 查询 / 索引编排完全不知道自己在哪个
 * Minecraft 版本上跑。换一个版本时，改的就是这个类加上 {@code cc.sighs.mixin} 与 {@code client} 包。
 *
 * <p>参数在入口处统一转型：common 只把服务器对象当不透明句柄，传回来的就是这里的 {@link MinecraftServer}。
 *
 * <p>与 NeoForge 1.21.1 那份在能力上完全对齐，包括原地回滚：{@link InPlaceRollback} 已按 1.20.1 的
 * 原版内部结构移植（只有写法上的替换，顺序与保护条件一字未动，差别逐条写在那个类的类注释里），
 * 支撑它的原版内部入口在 {@code cc.sighs.mixin.RollbackAccessMixins}，
 * 回滚窗口内的写盘拦截在 {@code cc.sighs.mixin.RollbackDiscardMixins}。
 */
public final class ForgeRewindPlatform implements RewindPlatform {
    @Override
    public String modVersion() {
        return RewindVersion.of();
    }

    @Override
    public Path worldRoot(Object server) {
        return server(server).getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
    }

    @Override
    public boolean isSameThread(Object server) {
        return server(server).isSameThread();
    }

    @Override
    public boolean isDedicatedServer(Object server) {
        return server(server).isDedicatedServer();
    }

    @Override
    public boolean isPublished(Object server) {
        return server(server).isPublished();
    }

    @Override
    public boolean hasPlayers(Object server) {
        return !server(server).getPlayerList().getPlayers().isEmpty();
    }

    @Override
    public FlushOutcome flushForCheckpoint(Object server, String slot, String source) {
        WorldFlush.Result flushed = WorldFlush.flush(server(server), slot, source);
        return new FlushOutcome(flushed.worldRoot, flushed.meta, flushed.inventory);
    }

    @Override
    public boolean supportsInPlaceRollback() {
        return true;
    }

    @Override
    public RollbackOutcome rollbackInPlace(Object server, Path worldRoot, String slot) throws Exception {
        InPlaceRollback.Result rolled = InPlaceRollback.run(server(server), worldRoot, slot);
        return new RollbackOutcome(rolled.summary(), rolled.mirrorCopied, rolled.mirrorSkipped,
                rolled.mirrorFiles, rolled.totalMs);
    }

    /**
     * 某个模组的类加载器：Forge / NeoForge 的模组都是 JPMS 模块，模块名就是 modid（崩溃报告里那串
     * {@code TRANSFORMER/rewind@1.0.0} 就是它的模块名）。跨模组反射要用它，见
     * {@code cc.sighs.rewind.common.compat.SophisticatedCoreCompat}——common 自己那个加载器看不见别的模组。
     */
    @Override
    public ClassLoader modClassLoader(String modId) {
        try {
            ModuleLayer layer = FMLLoader.getGameLayer();
            return layer == null ? null : layer.findLoader(modId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static MinecraftServer server(Object server) {
        return (MinecraftServer) server;
    }
}
