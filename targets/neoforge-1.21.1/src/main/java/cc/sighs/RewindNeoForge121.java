package cc.sighs;

import cc.sighs.rewind.client.RewindClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(RewindNeoForge121.MOD_ID)
public final class RewindNeoForge121 {
    public static final String MOD_ID = "rewind";

    public RewindNeoForge121(IEventBus modBus) {
        // 客户端专属注册放在这里做 dist 判定：专用服务器上 RewindClient 不会被加载，
        // 也就不会去解析 net.minecraft.client.* / neoforge.client.event.*。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RewindClient.setup(modBus);
        }
    }
}
