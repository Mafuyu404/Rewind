package cc.sighs.rewind.snapshot;

import java.util.Locale;

/**
 * 单个槽位的元数据，由 {@link SnapshotIndex} 持久化。
 *
 * <p>除槽位自身的信息外，它还带一条时间线的边：{@link #parentSlot}——这个存档点是站在
 * 哪个存档点上建出来的。界面上的「节点树布局」就是把这些边画成一棵树。
 */
public final class SnapshotMeta {
    public String slot = SnapshotLayout.DEFAULT_SLOT;
    public String status = SnapshotLayout.STATUS_INCOMPLETE;
    public long savedAtMillis;
    public String worldName = "";
    public String minecraftVersion = "";
    public String modVersion = "";
    /** 触发来源：key / command / selftest / ui。 */
    public String source = "";
    /** 玩家给槽位起的名字（重命名）；空表示用槽位自身的默认名。 */
    public String displayName = "";
    /**
     * 时间线上的父节点：建点时时间线的「头」（世界当前所在的那个存档点）所在的槽位。
     *
     * <p>空表示它是这条时间线的根。它只是一个槽位名，所以父槽位被覆盖或删掉之后，
     * 这条边就断了——界面上会把这个节点画成根，而不是指向一个不存在的东西。
     */
    public String parentSlot = "";
    /** 建点时玩家所在生物群系的 id（如 {@code minecraft:plains}）；取不到时为空。 */
    public String biomeId = "";
    /** 建点时该玩家的累计游玩时长（tick，原版 {@code play_time} 统计）。 */
    public long playtimeTicks;
    public long gameTime;
    public double playerX;
    public double playerY;
    public double playerZ;
    public int fileCount;
    public long totalBytes;

    public boolean isComplete() {
        return SnapshotLayout.STATUS_COMPLETE.equals(status);
    }

    public SnapshotMeta copy() {
        SnapshotMeta meta = new SnapshotMeta();
        meta.slot = slot;
        meta.status = status;
        meta.savedAtMillis = savedAtMillis;
        meta.worldName = worldName;
        meta.minecraftVersion = minecraftVersion;
        meta.modVersion = modVersion;
        meta.source = source;
        meta.displayName = displayName;
        meta.parentSlot = parentSlot;
        meta.biomeId = biomeId;
        meta.playtimeTicks = playtimeTicks;
        meta.gameTime = gameTime;
        meta.playerX = playerX;
        meta.playerY = playerY;
        meta.playerZ = playerZ;
        meta.fileCount = fileCount;
        meta.totalBytes = totalBytes;
        return meta;
    }

    /** 人类可读的一行摘要，命令与日志共用。 */
    public String describe() {
        return "slot=" + slot
                + " status=" + status
                + " savedAt=" + savedAtMillis
                + " world=" + worldName
                + " name=" + displayName
                + " parent=" + parentSlot
                + " mc=" + minecraftVersion
                + " mod=" + modVersion
                + " source=" + source
                + " gameTime=" + gameTime
                + " biome=" + biomeId
                + " playtime=" + playtimeTicks
                + " player=" + String.format(Locale.ROOT, "%.1f/%.1f/%.1f", playerX, playerY, playerZ)
                + " files=" + fileCount
                + " bytes=" + totalBytes;
    }
}
