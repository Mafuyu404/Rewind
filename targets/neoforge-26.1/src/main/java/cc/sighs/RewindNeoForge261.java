package cc.sighs;

import cc.sighs.rewind.client.RewindClient;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.server.NeoForge261RewindPlatform;
import cc.sighs.rewind.server.RewindServerConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * NeoForge 26.1 的入口。
 *
 * <p>服务端侧：SPI 平台实现 + COMMON 配置，dist 判定之外。
 * 客户端侧：热键、过渡后处理、时间树界面（{@code cc.sighs.rewind.client.*}）在 dist 判定之内接，
 * 这样专用服务器不会去解析 {@code net.minecraft.client.*} / {@code neoforge.client.event.*}。
 */
@Mod(RewindNeoForge261.MOD_ID)
public final class RewindNeoForge261 {
    public static final String MOD_ID = "rewind";

    public RewindNeoForge261(IEventBus modBus) {
        // common 侧所有需要碰 Minecraft 的能力都通过这个平台实现接入；必须在任何 RewindApi 调用之前装好。
        RewindPlatforms.install(new NeoForge261RewindPlatform());
        // 服务端行为的配置两边都要有（COMMON），所以放在 dist 判定之外
        ModList.get().getModContainerById(MOD_ID)
                .ifPresent(container -> RewindServerConfig.register(container, modBus));
        // 客户端专属注册放在这里做 dist 判定：专用服务器上客户端类不会被加载，
        // 也就不会去解析 net.minecraft.client.* / neoforge.client.event.*。
        // 26.1 上 FML 11 把 FMLEnvironment.dist 字段换成了 getDist()。
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            RewindClient.setup(modBus);
        }
    }
}
