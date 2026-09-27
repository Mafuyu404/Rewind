package cc.sighs.rewind.server;

import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import net.minecraft.server.MinecraftServer;

/**
 * 「跟着原版自动保存建点」：原版每次自动保存完，往 {@link SnapshotLayout#SLOT_AUTO} 写一个存档点。
 *
 * <p>挂点在 {@code cc.sighs.mixin.AutoCheckpointMixins}——注入 {@code MinecraftServer.saveEverything}
 * 的返回处，只认自动保存那一次调用的参数，所以本方法**已经在服务端线程上**。
 *
 * <p>建点会冻结服务端（强制落盘 + 增量拷贝，见 {@link RewindApi#createCheckpoint}），因此有几条必须
 * 跳过的情形：
 * <ul>
 *   <li>正在回溯（{@code Rewind.isDiscarding()}）——那会儿的世界状态马上要被丢掉，建了也是白建；</li>
 *   <li>局域网开放——冻结服务端会波及别的玩家；</li>
 *   <li>专用服务器——Rewind 的热键与界面本来就只服务单人世界，别去打扰别人的服务器；</li>
 *   <li>世界里没有玩家——没什么可记的。</li>
 * </ul>
 */
public final class AutoCheckpoint {
    private AutoCheckpoint() {
    }

    /** 原版自动保存刚存完。**必须在服务端线程上调用。** */
    public static void onVanillaAutosave(MinecraftServer server) {
        if (!RewindServerConfig.autoCheckpointEnabled()) {
            return;
        }
        if (Rewind.isDiscarding()) {
            return;
        }
        if (server.isDedicatedServer() || server.isPublished()) {
            return;
        }
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        RewindResult result = RewindApi.createCheckpoint(server, SnapshotLayout.SLOT_AUTO, "autosave");
        if (!result.success) {
            Rewind.LOGGER.error("Rewind: the checkpoint after the vanilla autosave failed: {}", result.failure);
        }
    }
}
