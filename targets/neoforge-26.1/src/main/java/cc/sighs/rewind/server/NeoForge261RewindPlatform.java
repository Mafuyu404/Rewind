package cc.sighs.rewind.server;

import java.nio.file.Path;
import cc.sighs.rewind.common.spi.FlushOutcome;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RollbackOutcome;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLLoader;

/**
 * NeoForge 26.1 的 {@link RewindPlatform} 实现：把 common 需要知道的全部平台能力接进来。
 *
 * <p>这里是「版本差异」的唯一落点——common 侧的建点 / 查询 / 索引编排完全不知道自己在哪个
 * Minecraft 版本上跑。换一个版本时，改的就是这个类加上 {@code cc.sighs.mixin} 与 {@code client} 包。
 *
 * <p>参数在入口处统一转型：common 只把服务器对象当不透明句柄，传回来的就是这里的 {@link MinecraftServer}。
 *
 * <p>原地回滚（{@code InPlaceRollback}）已在 26.1 上接通：{@link #supportsInPlaceRollback()} 返回
 * {@code true}，{@link #rollbackInPlace} 走 26.1 版的引擎（区块卸载 / 重载与玩家、时间天气、
 * 存档数据的内存回灌都按 26.1 的原版内部结构重写过）。
 */
public final class NeoForge261RewindPlatform implements RewindPlatform {
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

    /**
     * 26.1 的原地回滚引擎已经接通（见 {@link InPlaceRollback}）。
     *
     * <p>返回 true 之后 {@code RewindApi.rollbackInPlace} 会真的在活着的服务器里把世界倒回去，
     * 客户端也不再回退到「关世界 → 覆盖 → 重开」那条路。
     */
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
     * 某个模组的类加载器：NeoForge 的模组是 JPMS 模块，模块名就是 modid。26.1 的 FML 把
     * {@code getGameLayer()} 改成了实例方法，所以先拿 {@code FMLLoader.getCurrentOrNull()}。
     * 跨模组反射要用它，见 {@code cc.sighs.rewind.common.compat.SophisticatedCoreCompat}。
     */
    @Override
    public ClassLoader modClassLoader(String modId) {
        try {
            FMLLoader fml = FMLLoader.getCurrentOrNull();
            ModuleLayer layer = fml == null ? null : fml.getGameLayer();
            return layer == null ? null : layer.findLoader(modId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static MinecraftServer server(Object server) {
        return (MinecraftServer) server;
    }
}
