package cc.sighs;

import cc.sighs.rewind.client.RewindClient;
import cc.sighs.rewind.server.RewindServerConfig;
import cc.sighs.rewind.server.RewindServerSelfTest;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(RewindNeoForge121.MOD_ID)
public final class RewindNeoForge121 {
    public static final String MOD_ID = "rewind";

    public RewindNeoForge121(IEventBus modBus) {
        // 服务端行为的配置两边都要有（COMMON），所以放在 dist 判定之外
        ModList.get().getModContainerById(MOD_ID)
                .ifPresent(container -> RewindServerConfig.register(container, modBus));
        // 客户端专属注册放在这里做 dist 判定：专用服务器上 RewindClient 不会被加载，
        // 也就不会去解析 net.minecraft.client.* / neoforge.client.event.*。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RewindClient.setup(modBus);
        }
        // 专用服务器上的端到端自测。RewindApi 的同步入口本来就能在专用服务器上用，但那边没有
        // 任何天然触发点（热键、时间树、/rewind 都在客户端），所以要有一个显式的入口来驱动它。
        if (Boolean.getBoolean("rewind.servertest")) {
            RewindServerSelfTest.install();
        }
    }
}
