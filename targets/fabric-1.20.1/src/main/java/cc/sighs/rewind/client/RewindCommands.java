package cc.sighs.rewind.client;

import java.nio.file.Path;
import java.util.List;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /rewind} 命令：给手动测试和没有热键的场景用。
 *
 * <p>只做命令层的事——参数、权限、把结果念给玩家听；真正的活全部转给 {@link RewindApi}。
 * {@code /rewind ui} 打开时间树（等价于按 F9）。
 *
 * <p><b>与 NeoForge 1.21.1 的差异</b>：注册入口从 {@code RegisterCommandsEvent}（直接拿
 * {@code event.getDispatcher()}）换成 Fabric API 的 {@code CommandRegistrationCallback}，
 * 由 {@code RewindClient.setup()} 注册——所以和主版本一样，只有客户端才有这条命令。
 * 命令本体一行没动。
 */
public final class RewindCommands {
    private RewindCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rewind")
                // 加载期（数据包函数解析时，Brigadier 的 canUse 预检）拿到的是 getServer() 为 null 的 source，
                // 这里必须放行，否则任何引用 /rewind 的 .mcfunction 都会 NPE 加载失败
                .requires(source -> source.getServer() == null
                        || source.getServer().isSingleplayer()
                        || source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("ui").executes(context -> {
                    // 时间树只在客户端里有；服务端这边只写一条日志
                    RewindTreeScreen.open();
                    return 1;
                }))
                .then(Commands.literal("snapshot").executes(context -> {
                    // 带过渡的异步入口，等价于按 F7
                    RewindApi.requestCheckpoint("command");
                    return 1;
                }))
                .then(Commands.literal("restore").executes(context -> {
                    // 带过渡的异步入口，等价于按 F8
                    RewindApi.requestRollback("command");
                    return 1;
                })));
    }

    private static int status(CommandSourceStack source) {
        Path world = RewindApi.worldRoot(source.getServer());
        List<SnapshotMeta> metas = RewindApi.listCheckpoints(world);
        if (metas.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("rewind.cmd.status_empty"), false);
            return 0;
        }
        for (SnapshotMeta meta : metas) {
            source.sendSuccess(() -> Component.literal(meta.describe()), false);
        }
        RewindResult last = RewindApi.lastResult();
        source.sendSuccess(() -> Component.literal("phase=" + CheckpointController.phase()
                + " last=" + CheckpointController.lastOutcome()
                + (last == null ? "" : " " + last.describe())
                + " " + CheckpointController.lastMessage()), false);
        return metas.size();
    }
}
