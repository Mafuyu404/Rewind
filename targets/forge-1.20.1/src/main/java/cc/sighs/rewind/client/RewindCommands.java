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
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * {@code /rewind} 命令：给手动测试和没有热键的场景用。
 *
 * <p>只做命令层的事——参数、权限、把结果念给玩家听；真正的活全部转给 {@link RewindApi}。
 * {@code /rewind ui} 打开时间树（等价于按 F9）。
 *
 * <p>与 NeoForge 1.21.1 那份的差别只有事件类的包名：NeoForge 是
 * {@code net.neoforged.neoforge.event.RegisterCommandsEvent}，Forge 1.20.1 是
 * {@code net.minecraftforge.event.RegisterCommandsEvent}，都在游戏事件总线上，方法名一样
 * （{@code getDispatcher()}）。
 */
public final class RewindCommands {
    private RewindCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("rewind")
                .requires(source -> source.getServer().isSingleplayer() || source.hasPermission(2))
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
