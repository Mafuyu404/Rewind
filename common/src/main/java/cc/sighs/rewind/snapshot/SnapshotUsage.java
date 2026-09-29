package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这个槽位自己的文件占了多少」——界面详情面板的「文件大小」。
 *
 * <p><b>只算这个槽位目录里的文件</b>，外加它的清单 / 块映射 / 背包快照（那三个与目录同级、也是它
 * 独有的）。也就是在文件管理器里点开 `<槽位>/` 看到的那个数。
 *
 * <p>region / entities / poi 的内容不在里面：它们已经搬进**跨槽位共享**的 4 KiB 块存储
 * （{@link SnapshotLayout#blocksRoot}），不属于任何一个槽位——所以一个只有非 .mca 文件的槽位目录
 * 就是几十 KB，而内容合计（{@link SnapshotMeta#totalBytes}）仍然是整个世界的量级。删掉某个槽位
 * 能腾出多少要看共享块的分摊，那是另一件事，别把这个数当它。
 *
 * <p>纯 JDK、只读，任意线程可调。
 */
public final class SnapshotUsage {
    private SnapshotUsage() {
    }

    /** 槽位 → 它自己那些文件的字节数。索引里没有的槽位不会出现在结果里。 */
    public static Map<String, Long> occupiedBytes(Path worldRoot) throws IOException {
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        Map<String, Long> occupied = new LinkedHashMap<>();
        for (String slot : index.slots()) {
            long total = ownFileBytes(SnapshotLayout.slotDir(worldRoot, slot));
            total += sizeOf(SnapshotLayout.manifestFile(worldRoot, slot));
            total += sizeOf(SnapshotLayout.blockMapFile(worldRoot, slot));
            total += sizeOf(SnapshotLayout.inventoryFile(worldRoot, slot));
            occupied.put(slot, total);
        }
        return occupied;
    }

    /** 槽位目录里文件的合计（块编码的 .mca 不在里面）。 */
    private static long ownFileBytes(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        long[] total = new long[1];
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private static long sizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }
}
