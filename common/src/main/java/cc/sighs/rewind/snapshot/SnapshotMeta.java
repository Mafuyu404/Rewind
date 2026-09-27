package cc.sighs.rewind.snapshot;

import java.util.Locale;

/** 单个槽位的元数据，由 {@link SnapshotIndex} 持久化。 */
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
