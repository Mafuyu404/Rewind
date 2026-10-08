package cc.sighs.rewind.client;

import java.nio.file.Path;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.storage.LevelSummary;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * 「用存档点修复这个世界」：世界选择界面那颗按钮背后干的事。
 *
 * <p>它干活的时机是**世界还没进**，所以一条都不能碰服务器：存档目录由存档名直接拼出来
 * （1.20.1 的 {@code LevelStorageSource.getLevelPath(String)} 是 private，所以走 {@code getBaseDir()}，
 * 与原版 {@code levelSummaryReader} 里那条路径同一个算法），还原走 {@link RewindApi#restoreFiles}——
 * 纯文件操作、任意线程可调。F8 那条带过渡的在线管线在这里用不了：它要求 {@code minecraft.level} 与
 * {@code player} 都在。
 *
 * <p>目标存档点交给 {@link RewindApi#repairSlot}：时间线的头（世界当前站着的那个），取不到时退回
 * 最新的完整存档点。存档被别的实例锁着（原版 {@code isDisabled()}）时不给修——那会儿世界文件正被
 * 另一个进程写着。
 */
public final class WorldRepair {
    /**
     * 世界列表那颗按钮按下时干的事。
     *
     * <p>做成常驻实例、而且方法体写在**这个真类**里，是因为 mixin 里不能放 lambda：mixin 里的
     * lambda（以及匿名类）会被编译成挂在 mixin 类上的合成成员，合并进目标类之后那份引用不保证
     * 还对得上。指向普通类的方法就没有这个问题。
     */
    public static final Button.OnPress PRESS = WorldRepair::handlePress;

    /**
     * 世界列表当前选中的存档，由 mixin 在选中项变化时喂进来。
     *
     * <p>它只是「那颗按钮按下去时用哪一行」的缓存——按钮的可用状态每次都按新鲜值算，所以这里最多
     * 滞后一次选中变化，不会指向一个不可用的存档。同一时刻只有一个世界列表在，静态放一份够了。
     */
    @Nullable
    private static volatile LevelSummary selection;

    private WorldRepair() {
    }

    /** 由 mixin 调用：记下当前选中的存档（null = 没选中）。 */
    public static void rememberSelection(@Nullable LevelSummary summary) {
        selection = summary;
    }

    private static void handlePress(Button button) {
        open(Minecraft.getInstance().screen, selection);
    }

    /** 存档在磁盘上的目录（{@code level.dat} 所在目录）；存档名不可用时返回 null。 */
    @Nullable
    public static Path worldRoot(@Nullable LevelSummary summary) {
        if (summary == null || summary.getLevelId().isEmpty()) {
            return null;
        }
        try {
            return Minecraft.getInstance().getLevelSource().getBaseDir().resolve(summary.getLevelId());
        } catch (Throwable t) {
            Rewind.LOGGER.warn("Rewind: could not resolve the folder of world \"{}\"", summary.getLevelId(), t);
            return null;
        }
    }

    /** 这个存档现在能不能修：没有被别的实例锁着，而且有可用的存档点。 */
    public static boolean canRepair(@Nullable LevelSummary summary) {
        if (summary == null || summary.isDisabled()) {
            return false;
        }
        Path root = worldRoot(summary);
        return root != null && !RewindApi.repairSlot(root).isEmpty();
    }

    /** 打开修复界面；没有可用存档点（或拿不到存档目录）时只写一条日志，不进界面。 */
    public static void open(Screen parent, @Nullable LevelSummary summary) {
        Path root = worldRoot(summary);
        String slot = root == null ? "" : RewindApi.repairSlot(root);
        if (root == null || slot.isEmpty()) {
            Rewind.LOGGER.warn("Rewind: world \"{}\" has no complete checkpoint to repair from",
                    summary == null ? "?" : summary.getLevelId());
            return;
        }
        Minecraft.getInstance().setScreen(new WorldRepairScreen(parent, root, summary.getLevelName(), slot));
    }

    /** 目标存档点的一行描述：名字 · 建点时刻。文案与时间格式跟时间树共用一套。 */
    public static String describeTarget(Path worldRoot, String slot) {
        SnapshotMeta meta = RewindApi.describe(worldRoot, slot);
        if (meta == null) {
            return slot;
        }
        return RewindTreeScreen.title(slot, meta) + " · " + RewindTreeScreen.absoluteTime(meta.savedAtMillis);
    }
}
