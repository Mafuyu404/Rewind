package cc.sighs.rewind.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /rewind} 命令：给手动测试和没有热键的场景用。
 *
 * <p>只在客户端（含集成服务器）注册；snapshot/restore 会转交给客户端状态机执行。
 */
public final class RewindCommands {
    private RewindCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("rewind")
                .requires(source -> source.getServer().isSingleplayer() || source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("snapshot").executes(context -> {
                    Minecraft.getInstance().execute(() -> CheckpointController.requestSnapshot("command"));
                    return 1;
                }))
                .then(Commands.literal("restore").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    Minecraft.getInstance().execute(() -> CheckpointController.requestRestore("command"));
                    return 1;
                })));
    }

    private static int status(CommandSourceStack source) {
        Path world = source.getServer().getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        try {
            SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(world));
            List<String> slots = index.slots();
            if (slots.isEmpty()) {
                source.sendSuccess(() -> Component.translatable("rewind.cmd.status_empty"), false);
                return 0;
            }
            for (String slot : slots) {
                SnapshotMeta meta = index.get(slot);
                if (meta == null) {
                    continue;
                }
                source.sendSuccess(() -> Component.literal(meta.describe()), false);
            }
            source.sendSuccess(() -> Component.literal("phase=" + CheckpointController.phase()
                    + " last=" + CheckpointController.lastOutcome()
                    + " " + CheckpointController.lastMessage()), false);
            return slots.size();
        } catch (IOException e) {
            Rewind.LOGGER.error("Rewind: failed to read snapshot index", e);
            source.sendFailure(Component.literal("failed to read snapshot index: " + e));
            return 0;
        }
    }
}
