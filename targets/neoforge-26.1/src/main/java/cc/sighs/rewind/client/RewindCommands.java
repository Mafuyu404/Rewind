package cc.sighs.rewind.client;

import java.nio.file.Path;
import java.util.List;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.server.RewindServerConfig;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /rewind} 命令：模组的主要功能都有一条命令（没有热键的场景、想写进 .mcfunction 的场合）。
 *
 * <pre>
 * /rewind status                列出所有槽位、时间线的头与上一次操作
 * /rewind ui                    打开时间树（= 按 F9）
 * /rewind snapshot [&lt;槽位&gt;]      建立 / 覆盖存档点（= 按 F7；不给槽位就是 quick）
 * /rewind restore [&lt;槽位&gt;]       回溯到存档点（= 按 F8；不给槽位就是 quick）
 * /rewind info &lt;槽位&gt;           看一个槽位的详情
 * /rewind delete &lt;槽位&gt;         删掉一个槽位
 * /rewind rename &lt;槽位&gt; &lt;名字&gt;   给槽位改名
 * /rewind autosave on|off       跟着原版自动保存建点
 * </pre>
 *
 * <p>只做命令层的事——参数、权限、把结果念给玩家听；真正的活全部转给 {@link RewindApi} 与
 * {@link RewindServerConfig}。带过渡的那两个动作（{@code snapshot} / {@code restore}）走的就是
 * F7 / F8 那条路（{@link RewindApi#requestCheckpoint(String, String)} /
 * {@link RewindApi#requestRollback(String, String)}）；命令层自己先过一遍「能不能动手」的判定，
 * <b>只是为了能当场把原因说给玩家听</b>——热键那条路被挡下来时只写日志。判定本身在
 * {@code CheckpointController.ensureUsable} 与 {@code requestRestore} 里，两边必须保持一致
 * （忙 / 不在世界里 / 局域网已开放 / 没有存档点 / 读档冷却）；槽位管理（{@code info} /
 * {@code delete} / {@code rename}）与 {@code autosave} 是纯文件 / 配置操作。
 */
public final class RewindCommands {
    private RewindCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("rewind")
                // 26.1 把权限等级换成了 PermissionSet（原来的 hasPermission(int) 没了）
                // 加载期（数据包函数解析时，Brigadier 的 canUse 预检）拿到的是 getServer() 为 null 的 source，
                // 这里必须放行，否则任何引用 /rewind 的 .mcfunction 都会 NPE 加载失败
                .requires(source -> source.getServer() == null
                        || source.getServer().isSingleplayer()
                        || source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("ui").executes(context -> ui()))
                .then(Commands.literal("snapshot")
                        .executes(context -> snapshot(context.getSource(), RewindApi.DEFAULT_SLOT))
                        .then(slotArgument().executes(context -> snapshot(context.getSource(), slot(context)))))
                .then(Commands.literal("restore")
                        .executes(context -> restore(context.getSource(), RewindApi.DEFAULT_SLOT))
                        .then(slotArgument().executes(context -> restore(context.getSource(), slot(context)))))
                .then(Commands.literal("info")
                        .then(slotArgument().executes(context -> info(context.getSource(), slot(context)))))
                .then(Commands.literal("delete")
                        .then(slotArgument().executes(context -> delete(context.getSource(), slot(context)))))
                .then(Commands.literal("rename")
                        .then(slotArgument().then(
                                Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> rename(context.getSource(), slot(context),
                                                StringArgumentType.getString(context, "name"))))))
                .then(Commands.literal("autosave")
                        .then(Commands.literal("on").executes(context -> autosave(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> autosave(context.getSource(), false)))));
    }

    // ------------------------------------------------------------------ 命令树

    /** 槽位参数：一个不含空格的词；补全用界面上那套槽位（自动 / 快速 / 手动槽位）。 */
    private static RequiredArgumentBuilder<CommandSourceStack, String> slotArgument() {
        return Commands.<String>argument("slot", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(RewindApi.slots(), builder));
    }

    private static String slot(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "slot");
    }

    // ------------------------------------------------------------------ 动作

    private static int ui() {
        // 时间树只在客户端里有；服务端这边只写一条日志（和热键一样）
        RewindTreeScreen.open();
        return 1;
    }

    /** 建立 / 覆盖存档点，等价于按 F7。 */
    private static int snapshot(CommandSourceStack source, String slot) {
        Component blocker = worldActionBlocker(source);
        if (blocker != null) {
            source.sendFailure(blocker);
            return 0;
        }
        if (!RewindApi.requestCheckpoint(slot, "command")) {
            source.sendFailure(noWorld());
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("rewind.cmd.snapshot_started", slot), false);
        return 1;
    }

    /** 回溯到存档点，等价于按 F8。 */
    private static int restore(CommandSourceStack source, String slot) {
        Component blocker = worldActionBlocker(source);
        if (blocker != null) {
            source.sendFailure(blocker);
            return 0;
        }
        Path world = worldRoot(source);
        if (!RewindApi.hasCheckpoint(world, slot)) {
            source.sendFailure(Component.translatable("rewind.cmd.slot_missing", slot));
            return 0;
        }
        if (RewindApi.rollbackCooldownRemainingMillis(world) > 0L) {
            source.sendFailure(Component.translatable("rewind.error.rollback_cooldown"));
            return 0;
        }
        if (!RewindApi.requestRollback(slot, "command")) {
            source.sendFailure(noWorld());
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("rewind.cmd.restore_started", slot), false);
        return 1;
    }

    /** 看一个槽位的详情：一行元数据 + 背包快照装了几格。 */
    private static int info(CommandSourceStack source, String slot) {
        Path world = worldRoot(source);
        if (world == null) {
            source.sendFailure(noWorld());
            return 0;
        }
        SnapshotMeta meta = RewindApi.describe(world, slot);
        if (meta == null) {
            source.sendFailure(Component.translatable("rewind.cmd.slot_missing", slot));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(meta.describe()), false);
        SnapshotInventory inventory = RewindApi.readInventory(world, slot);
        source.sendSuccess(() -> Component.translatable("rewind.cmd.inventory",
                inventory.size(), SnapshotInventory.SLOT_COUNT), false);
        return 1;
    }

    private static int delete(CommandSourceStack source, String slot) {
        Component blocker = busyBlocker();
        if (blocker != null) {
            source.sendFailure(blocker);
            return 0;
        }
        Path world = worldRoot(source);
        if (world == null) {
            source.sendFailure(noWorld());
            return 0;
        }
        if (!RewindApi.deleteCheckpoint(world, slot)) {
            source.sendFailure(Component.translatable("rewind.cmd.slot_missing", slot));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("rewind.cmd.deleted", slot), false);
        return 1;
    }

    private static int rename(CommandSourceStack source, String slot, String name) {
        Component blocker = busyBlocker();
        if (blocker != null) {
            source.sendFailure(blocker);
            return 0;
        }
        Path world = worldRoot(source);
        if (world == null) {
            source.sendFailure(noWorld());
            return 0;
        }
        String trimmed = name.trim();
        if (!SnapshotLayout.isValidDisplayName(trimmed)) {
            source.sendFailure(Component.translatable("rewind.cmd.name_invalid", SnapshotLayout.NAME_MAX_LENGTH));
            return 0;
        }
        if (!RewindApi.renameCheckpoint(world, slot, trimmed)) {
            source.sendFailure(Component.translatable("rewind.cmd.slot_missing", slot));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("rewind.cmd.renamed", slot, trimmed), false);
        return 1;
    }

    /**
     * 开关「跟着原版自动保存建点」（界面上那张「自动存档」卡的设置，同一个配置项）。
     *
     * <p>它只写 COMMON 配置、不碰世界，所以没有别的命令那几道判定。
     */
    private static int autosave(CommandSourceStack source, boolean enabled) {
        RewindServerConfig.setAutoCheckpointEnabled(enabled);
        source.sendSuccess(() -> Component.translatable(
                enabled ? "rewind.cmd.autosave_on" : "rewind.cmd.autosave_off"), false);
        return enabled ? 1 : 2;
    }

    // ------------------------------------------------------------------ 查询与判定

    private static int status(CommandSourceStack source) {
        Path world = worldRoot(source);
        if (world == null) {
            source.sendFailure(noWorld());
            return 0;
        }
        List<SnapshotMeta> metas = RewindApi.listCheckpoints(world);
        String head = RewindApi.currentSlot(world);
        source.sendSuccess(() -> Component.literal("head=" + head), false);
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

    /** 命令只在世界里有意义：数据包函数解析期的 source 连 server 都没有。 */
    private static Path worldRoot(CommandSourceStack source) {
        return source.getServer() == null ? null : RewindApi.worldRoot(source.getServer());
    }

    /** 忙着的时候什么都别干：槽位管理与建点 / 回溯会写同一张索引。 */
    private static Component busyBlocker() {
        return RewindApi.isBusy() ? Component.translatable("rewind.error.busy") : null;
    }

    /** 建点 / 回溯还要额外满足：在世界里、且没开局域网（判定同 {@code CheckpointController}）。 */
    private static Component worldActionBlocker(CommandSourceStack source) {
        Component busy = busyBlocker();
        if (busy != null) {
            return busy;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noWorld();
        }
        return server.isPublished() ? Component.translatable("rewind.error.lan_published") : null;
    }

    /** 「不在世界里」与「没有客户端桥」（专用服务器 / 还没 setup）共用一句：两种情况下都只能这么办。 */
    private static Component noWorld() {
        return Component.translatable("rewind.error.no_world");
    }
}
