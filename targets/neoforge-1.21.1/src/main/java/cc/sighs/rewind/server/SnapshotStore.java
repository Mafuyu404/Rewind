package cc.sighs.rewind.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * 槽位这条记录本身的增删改：删除、改名。
 *
 * <p>与 {@link CheckpointWriter} 的分工：那边管「世界内容 ↔ 槽位」的搬运，这边管槽位记录。
 * 全是纯文件操作，任意线程可调，但**不要和存档点操作并发**——两边都会写 {@code index.properties}
 * （调用前先看 {@code RewindApi.isBusy()}）。
 */
public final class SnapshotStore {
    private SnapshotStore() {
    }

    /**
     * 删除一个槽位：索引条目、槽位目录、清单、背包快照。
     *
     * <p>顺序是「先摘索引条目、再删文件」：中途崩了留下的是没人引用的目录，而不是索引里指向一个
     * 已经不存在的目录的槽位。
     *
     * @return 是否真的删掉了东西
     */
    public static boolean delete(Path worldRoot, String slot) throws IOException {
        Path indexFile = SnapshotLayout.indexFile(worldRoot);
        boolean removed = false;
        SnapshotIndex index = SnapshotIndex.load(indexFile);
        SnapshotMeta deleted = index.get(slot);
        if (index.remove(slot)) {
            // 时间线的「头」正好在这个槽位上：退回它的父节点（父节点也没了就退回「不知道」，
            // 下一个存档点会成为这条线的根）
            if (slot.equals(index.getHead())) {
                String parent = deleted == null ? "" : deleted.parentSlot;
                index.setHead(index.get(parent) == null ? "" : parent);
            }
            index.save(indexFile);
            removed = true;
        }
        removed |= deleteRecursively(SnapshotLayout.slotDir(worldRoot, slot));
        removed |= Files.deleteIfExists(SnapshotLayout.manifestFile(worldRoot, slot));
        removed |= Files.deleteIfExists(SnapshotLayout.inventoryFile(worldRoot, slot));
        if (removed) {
            Rewind.LOGGER.info("Rewind: deleted checkpoint {}", slot);
        }
        return removed;
    }

    /**
     * 给槽位改名。
     *
     * <p>只改索引里的显示名：槽位 id 与磁盘目录都不动，所以改名不会让槽位丢失，也不会触发重拷。
     * 名字会被 {@link CheckpointWriter#create} 保留——覆盖同一个槽位不会把玩家的名字抹掉。
     *
     * @return 槽位是否存在
     */
    public static boolean rename(Path worldRoot, String slot, String displayName) throws IOException {
        Path indexFile = SnapshotLayout.indexFile(worldRoot);
        SnapshotIndex index = SnapshotIndex.load(indexFile);
        SnapshotMeta meta = index.get(slot);
        if (meta == null) {
            return false;
        }
        meta.displayName = displayName == null ? "" : displayName.trim();
        index.put(meta, slot);
        index.save(indexFile);
        Rewind.LOGGER.info("Rewind: renamed checkpoint {} to \"{}\"", slot, meta.displayName);
        return true;
    }

    private static boolean deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            // walk 是前序：反过来遍历才能先删文件、后删目录
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        return true;
    }
}
