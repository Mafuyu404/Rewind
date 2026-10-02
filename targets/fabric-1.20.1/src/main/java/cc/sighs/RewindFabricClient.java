package cc.sighs;

import cc.sighs.rewind.client.RewindClient;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 1.20.1 的客户端入口。
 *
 * <p>{@code fabric.mod.json} 里的 {@code client} 入口点，只在物理客户端上执行；专用服务器永远不会
 * 加载本类，也就不会连带加载 {@code cc.sighs.rewind.client.*} 那些碰 {@code net.minecraft.client.*}
 * 的类——这正是主版本用 {@code FMLEnvironment.getDist()} 判定守住的那条边界。
 *
 * <p>这里只做一件事：把 {@link RewindClient#setup()} 叫起来（配置、热键、tick、命令、以及
 * {@code RewindApi} 的客户端桥）。
 */
public final class RewindFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        RewindClient.setup();
    }
}
