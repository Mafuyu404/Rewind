package cc.sighs;

import cc.sighs.rewind.client.RewindClient;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.server.ForgeRewindPlatform;
import cc.sighs.rewind.server.RewindServerConfig;
import cc.sighs.rewind.server.RewindServerSelfTest;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 1.20.1 的模组入口。
 *
 * <p>这里只做三件事：把平台实现装进 common、注册服务端行为的配置、以及（只在物理客户端上）
 * 接上客户端侧。前两件必须在**模组构造阶段**完成——平台晚装一步，前面任何 {@code RewindApi} 调用
 * 都会拿不到世界根；配置晚注册一步，就赶不上配置加载事件。
 *
 * <p>与 NeoForge 1.21.1 那份的差别：
 * <ul>
 *   <li>用无参构造 + {@code FMLJavaModLoadingContext.get().getModEventBus()} 拿模组事件总线
 *       （NeoForge 那份是让 FML 把 {@code IEventBus} 注入构造参数）；</li>
 *   <li>{@code FMLEnvironment.dist} 在 1.20.1 是一个 public static final **字段**（不是 getter），
 *       所以判定写成 {@code FMLEnvironment.dist == Dist.CLIENT}；{@code Dist} 的类型也换了包
 *       （{@code net.minecraftforge.api.distmarker.Dist}）；</li>
 *   <li>Forge 1.20.1 的 {@code ModContainer} 上没有 {@code registerConfig}，所以不需要先取
 *       {@code ModList.get().getModContainerById(...)}，配置注册自己走
 *       {@code ModLoadingContext.get().registerConfig(...)}（见 {@link RewindServerConfig#register}
 *       与 {@code RewindClientConfig#register}）。</li>
 * </ul>
 *
 * <p>客户端分支必须放在这里，而且 {@code RewindClient} 只在分支里被引用：专用服务器上那个类
 * 永远不会被加载，也就不会去解析 {@code net.minecraft.client.*} / {@code net.minecraftforge.client.event.*}。
 */
@Mod(RewindForge.MOD_ID)
public final class RewindForge {
    public static final String MOD_ID = "rewind";

    public RewindForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // common 侧所有需要碰 Minecraft 的能力都通过这个平台实现接入；必须在任何 RewindApi 调用之前装好。
        RewindPlatforms.install(new ForgeRewindPlatform());
        // 服务端行为的配置两边都要有（COMMON）
        RewindServerConfig.register(modBus);
        // 客户端专属注册放在这里做 dist 判定：专用服务器上 RewindClient 不会被加载。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RewindClient.setup(modBus);
        }
        // 专用服务器上的端到端自测。RewindApi 的同步入口本来就能在专用服务器上用，但那边没有
        // 任何天然触发点（热键、时间树、/rewind 都挂在客户端，客户端侧的自测要单人世界），
        // 所以要有一个显式的入口把它驱动起来。
        if (Boolean.getBoolean("rewind.servertest")) {
            RewindServerSelfTest.install();
        }
    }
}
