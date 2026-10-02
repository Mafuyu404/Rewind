package cc.sighs;

import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.server.FabricRewindPlatform;
import cc.sighs.rewind.server.RewindServerConfig;
import cc.sighs.rewind.server.RewindServerSelfTest;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric 1.20.1 的入口。
 *
 * <p>装平台实现（common 侧所有需要碰 Minecraft 的能力都经过它）与读服务端配置。客户端入口在
 * {@code cc.sighs.RewindFabricClient}（{@code ClientModInitializer}），本类不引用任何
 * {@code net.minecraft.client.*} / {@code cc.sighs.rewind.client}，所以专用服务器上也能加载。
 *
 * <p>专用服务器上的端到端自测（{@code -Drewind.servertest=true}，构建脚本里是
 * {@code -PrwSelfTest=true}）也挂在这里：它本来就是纯服务端的东西，挂客户端入口反而上不去。
 */
public final class RewindFabric implements ModInitializer {
    public static final String MOD_ID = "rewind";

    @Override
    public void onInitialize() {
        // 必须在任何 RewindApi 调用之前装好
        RewindPlatforms.install(new FabricRewindPlatform());
        // 服务端行为的配置两边都要有，所以放在主入口而不是客户端入口里
        RewindServerConfig.register();
        // 专用服务器自测：只在构建脚本显式要求时安装（默认不装，正常游玩完全不受影响）
        if (Boolean.getBoolean("rewind.servertest")) {
            RewindServerSelfTest.install();
        }
    }
}
