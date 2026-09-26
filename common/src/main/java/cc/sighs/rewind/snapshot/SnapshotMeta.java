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
    /** 触发来源：key / command / selftest。 */
    public String source = "";
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
                + " mc=" + minecraftVersion
                + " mod=" + modVersion
                + " source=" + source
                + " gameTime=" + gameTime
                + " player=" + String.format(Locale.ROOT, "%.1f/%.1f/%.1f", playerX, playerY, playerZ)
                + " files=" + fileCount
                + " bytes=" + totalBytes;
    }
}
