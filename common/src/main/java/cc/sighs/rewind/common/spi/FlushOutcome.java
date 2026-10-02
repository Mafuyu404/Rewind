package cc.sighs.rewind.common.spi;

import java.nio.file.Path;
import java.util.List;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * {@link RewindPlatform#flushForCheckpoint} 的结果：落盘后的世界根目录、展示元数据与背包快照。
 *
 * <p>元数据里的槽位名、来源、时间戳等字段由平台实现填好后交给 common；common 只在此基础上补
 * 时间线的边与文件统计。
 */
public final class FlushOutcome {
    public final Path worldRoot;
    public final SnapshotMeta meta;
    /** 建点时玩家的背包快照（栏位序号 → 物品表达式）；世界里没有玩家时为空。 */
    public final List<SnapshotInventory.Entry> inventory;

    public FlushOutcome(Path worldRoot, SnapshotMeta meta, List<SnapshotInventory.Entry> inventory) {
        this.worldRoot = worldRoot;
        this.meta = meta;
        this.inventory = inventory;
    }
}
