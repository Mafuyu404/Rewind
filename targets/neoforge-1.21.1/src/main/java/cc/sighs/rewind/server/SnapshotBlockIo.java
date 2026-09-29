package cc.sighs.rewind.server;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.snapshot.SnapshotBlockStore;
import cc.sighs.rewind.snapshot.SnapshotBlocks;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotManifest;
import cc.sighs.rewind.snapshot.SnapshotMirror;

/**
 * 把 {@link SnapshotMirror} 的块存储会话接到磁盘上：读入槽位已有的块映射、把这次写出来的映射落盘、
 * 以及删槽位时回收没人引用的块。
 *
 * <p>单独拎出来是为了让 {@link CheckpointWriter} 不用直接碰块映射的读写——它只要「给我一个上下文」
 * 和「收尾时落盘」，而块会话的开关都在这里。
 */
public final class SnapshotBlockIo implements Closeable {
    private final Path worldRoot;
    private final String slot;
    private final SnapshotBlockStore store;
    private final SnapshotBlocks previous;
    private final SnapshotBlocks written = SnapshotBlocks.empty();

    private SnapshotBlockIo(Path worldRoot, String slot) throws IOException {
        this.worldRoot = worldRoot;
        this.slot = slot;
        this.store = openStore(worldRoot);
        this.previous = SnapshotBlocks.load(SnapshotLayout.blockMapFile(worldRoot, slot));
    }

    /** 建点方向：读入本槽位上一次的映射（用来复用没变的文件的块序列）。用完必须 {@link #close()}。 */
    public static SnapshotBlockIo open(Path worldRoot, String slot) throws IOException {
        return new SnapshotBlockIo(worldRoot, slot);
    }

    public SnapshotMirror.Blocks context() {
        return new SnapshotMirror.Blocks(store, previous, written);
    }

    /**
     * 把这次写出来的映射落盘。
     *
     * <p>必须在 {@link #close()}（块真正写进 pack）**之后**、索引被标成 {@code complete} 之前调用：
     * 顺序反了会留下指向不存在块的映射，那种槽位读不回来。
     */
    public void save() throws IOException {
        written.save(SnapshotLayout.blockMapFile(worldRoot, slot));
    }

    /** 关掉块存储会话——这次收的块是在这一刻才真正写进 pack 的。 */
    @Override
    public void close() throws IOException {
        store.close();
    }

    /** 打开一个块存储会话（用完必须 close）。原地回滚要拿快照的 .mca 头部做比对，它自己开一个。 */
    public static SnapshotBlockStore openStore(Path worldRoot) {
        return SnapshotBlockStore.open(SnapshotLayout.blocksRoot(worldRoot));
    }

    /**
     * 把槽位还原进活动存档。
     *
     * <p>**所有回溯路径都必须从这里走**：块编码的 .mca 在槽位里没有实体文件，漏掉块存储上下文
     * 就会去读一个不存在的文件。集中到一个入口，就没有「某条路忘了带上下文」这种事了。
     */
    public static SnapshotMirror.Result restoreInto(Path worldRoot, String slot, SnapshotManifest manifest)
            throws IOException {
        SnapshotBlocks map = SnapshotBlocks.load(SnapshotLayout.blockMapFile(worldRoot, slot));
        try (SnapshotBlockStore store = SnapshotBlockStore.openForBulkRead(SnapshotLayout.blocksRoot(worldRoot))) {
            return SnapshotMirror.mirror(SnapshotLayout.slotDir(worldRoot, slot), worldRoot,
                    SnapshotMirror.Direction.TO_WORLD, manifest, null, new SnapshotMirror.Blocks(store, map, null));
        }
    }

    /**
     * 回收块存储里没人引用的块。
     *
     * <p>存活集合取**所有**槽位的映射——包括还没写完的：多留几个没人引用的块只是浪费一点空间，
     * 漏掉一个存活哈希就会让对应文件永远还原不回来。
     */
    public static void collectGarbage(Path worldRoot) {
        try {
            Path root = SnapshotLayout.snapshotRoot(worldRoot);
            if (!Files.isDirectory(root)) {
                return;
            }
            List<String> live = new ArrayList<>();
            try (DirectoryStream<Path> maps =
                         Files.newDirectoryStream(root, "*" + SnapshotLayout.BLOCK_MAP_SUFFIX)) {
                for (Path mapFile : maps) {
                    live.addAll(SnapshotBlocks.load(mapFile).allHashes());
                }
            }
            int removed = SnapshotBlockStore.collectGarbage(SnapshotLayout.blocksRoot(worldRoot), live);
            if (removed > 0) {
                Rewind.LOGGER.info("Rewind: block storage reclaimed {} unreferenced blocks", removed);
            }
        } catch (IOException e) {
            Rewind.LOGGER.warn("Rewind: block storage garbage collection failed", e);
        }
    }
}
