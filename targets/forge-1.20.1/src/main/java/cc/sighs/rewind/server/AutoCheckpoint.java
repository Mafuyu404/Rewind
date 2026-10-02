package cc.sighs.rewind.server;

import net.minecraft.server.MinecraftServer;

/**
 * 「跟着原版自动保存建点」在 Forge 1.20.1 上的入口。
 *
 * <p>策略本体（开关、正在回溯、局域网、专用服务器、没有玩家这些跳过条件）在 common 的
 * {@code cc.sighs.rewind.common.core.AutoCheckpoint}；这里的挂点是 {@code cc.sighs.mixin.AutoCheckpointMixins}——注入
 * {@code MinecraftServer.saveEverything} 的返回处，只认自动保存那一次调用的参数，
 * 所以本方法**已经在服务端线程上**。
 */
public final class AutoCheckpoint {
    private AutoCheckpoint() {
    }

    /** 原版自动保存刚存完。**必须在服务端线程上调用。** */
    public static void onVanillaAutosave(MinecraftServer server) {
        cc.sighs.rewind.common.core.AutoCheckpoint.onVanillaAutosave(server);
    }
}
