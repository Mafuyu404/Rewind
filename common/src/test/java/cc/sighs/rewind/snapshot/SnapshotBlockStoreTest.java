package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link SnapshotBlockStore} 的切块、去重、重建、会话语义与垃圾回收。 */
class SnapshotBlockStoreTest {

    @TempDir
    Path tempRoot;

    private static byte[] bytes(int size, int seed) {
        byte[] content = new byte[size];
        new Random(seed).nextBytes(content);
        return content;
    }

    private Path blocks() {
        return tempRoot.resolve("blocks");
    }

    private static Path source(Path root, byte[] content) throws IOException {
        Path file = root.resolve("source.bin");
        Files.write(file, content);
        return file;
    }

    @Test
    void classifiesOnlyTheRegionLikeDirectories() {
        assertTrue(SnapshotBlockStore.isEncoded("region/r.0.0.mca"));
        assertTrue(SnapshotBlockStore.isEncoded("poi/r.0.0.mca"));
        assertTrue(SnapshotBlockStore.isEncoded("entities/r.0.0.mca"));
        assertTrue(SnapshotBlockStore.isEncoded("DIM-1/region/r.0.0.mca"), "维度子目录下也算");
        assertTrue(SnapshotBlockStore.isEncoded("region/c.0.0.mcc"), "超大区块也是按扇区存的");
        assertFalse(SnapshotBlockStore.isEncoded("level.dat"));
        assertFalse(SnapshotBlockStore.isEncoded("playerdata/abc.dat"));
        assertFalse(SnapshotBlockStore.isEncoded("datapacks/pack/data/x/r.0.0.mca"), "不在 region/entities/poi 下就不算");
        assertFalse(SnapshotBlockStore.isEncoded("region/readme.txt"));
    }

    @Test
    void rebuildsFilesOfEveryAwkwardSize() throws IOException {
        Path blocks = blocks();
        int[] sizes = {0, 1, 100, SnapshotBlockStore.BLOCK_SIZE - 1, SnapshotBlockStore.BLOCK_SIZE,
                SnapshotBlockStore.BLOCK_SIZE + 1, 10_000};
        for (int size : sizes) {
            byte[] content = bytes(size, size + 1);
            Path file = source(tempRoot, content);
            Path rebuilt = tempRoot.resolve("rebuilt.bin");
            try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
                SnapshotBlockStore.Stored stored = store.encode(file);
                assertEquals(size, stored.newBytes, "第一次编码，size=" + size + " 的块都该是新的");
                store.decode(stored.hashes, size, rebuilt);
            }
            assertArrayEquals(content, Files.readAllBytes(rebuilt), "size=" + size);
        }
    }

    @Test
    void blocksOnlyLandOnDiskWhenTheSessionIsClosed() throws IOException {
        Path blocks = blocks();
        byte[] content = bytes(9_000, 77);
        Path file = source(tempRoot, content);
        List<String> hashes;
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            hashes = store.encode(file).hashes;
            assertTrue(isEmptyDirectory(blocks), "块要到 close() 才落盘");
        }
        Path rebuilt = tempRoot.resolve("rebuilt-again.bin");
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            store.decode(hashes, content.length, rebuilt);
        }
        assertArrayEquals(content, Files.readAllBytes(rebuilt));
    }

    @Test
    void doesNotWriteBlocksItAlreadyHas() throws IOException {
        Path blocks = blocks();
        byte[] content = bytes(20_000, 42);
        Path file = source(tempRoot, content);

        List<String> first;
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            first = store.encode(file).hashes;
        }
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            SnapshotBlockStore.Stored second = store.encode(file);
            assertEquals(first, second.hashes);
            assertEquals(0L, second.newBytes, "内容没变，一个块都不该重写");
        }
    }

    @Test
    void onlyStoresTheBlocksThatDiffer() throws IOException {
        Path blocks = blocks();
        byte[] original = bytes(4 * SnapshotBlockStore.BLOCK_SIZE, 7);
        Path file = source(tempRoot, original);
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            store.encode(file);
        }

        byte[] changed = original.clone();
        changed[SnapshotBlockStore.BLOCK_SIZE * 2 + 3] = (byte) ~changed[SnapshotBlockStore.BLOCK_SIZE * 2 + 3];
        Files.write(file, changed);
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            assertEquals(SnapshotBlockStore.BLOCK_SIZE, store.encode(file).newBytes, "只有一个 4 KiB 块是新的");
        }
    }

    @Test
    void readsJustThePrefixForHeaderComparisons() throws IOException {
        Path blocks = blocks();
        byte[] content = bytes(12_000, 99);
        Path file = source(tempRoot, content);
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            List<String> hashes = store.encode(file).hashes;
            assertArrayEquals(Arrays.copyOf(content, 8192), store.readPrefix(hashes, 8192));
        }
    }

    @Test
    void garbageCollectionKeepsLiveBlocksAndDropsTheRest() throws IOException {
        Path blocks = blocks();
        byte[] liveContent = bytes(9_000, 1);
        Path live = source(tempRoot, liveContent);
        List<String> liveHashes;
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            liveHashes = store.encode(live).hashes;
        }
        Path dead = tempRoot.resolve("dead.bin");
        Files.write(dead, bytes(9_000, 2));
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            store.encode(dead);
        }

        int removed = SnapshotBlockStore.collectGarbage(blocks, liveHashes);

        assertEquals(3, removed, "另一份文件的三个块都该被回收");
        Path rebuilt = tempRoot.resolve("rebuilt-live.bin");
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            store.decode(liveHashes, liveContent.length, rebuilt);
        }
        assertArrayEquals(liveContent, Files.readAllBytes(rebuilt), "压缩之后存活块还要读得回来");
    }

    @Test
    void bulkReadSessionsRebuildTheSameBytesAsRandomReads() throws IOException {
        Path blocks = blocks();
        byte[] content = bytes(20_000, 555);
        Path file = source(tempRoot, content);
        List<String> hashes;
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            hashes = store.encode(file).hashes;
        }

        Path random = tempRoot.resolve("random.bin");
        Path bulk = tempRoot.resolve("bulk.bin");
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            store.decode(hashes, content.length, random);
        }
        try (SnapshotBlockStore store = SnapshotBlockStore.openForBulkRead(blocks)) {
            store.decode(hashes, content.length, bulk);
        }

        assertArrayEquals(content, Files.readAllBytes(random), "每块随机读这条路");
        assertArrayEquals(content, Files.readAllBytes(bulk), "整包缓存这条路");
        assertArrayEquals(Files.readAllBytes(random), Files.readAllBytes(bulk));

        try (SnapshotBlockStore store = SnapshotBlockStore.openForBulkRead(blocks)) {
            assertArrayEquals(Arrays.copyOf(content, 8192), store.readPrefix(hashes, 8192));
        }
    }

    @Test
    void patchesAnExistingFileInPlaceWhenOnlySomeBlocksDiffer() throws IOException {
        Path blocks = blocks();
        byte[] content = bytes(12_000, 321);
        Path file = source(tempRoot, content);
        List<String> hashes;
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            hashes = store.encode(file).hashes;
        }

        // 目标已经有一份同样大小的内容，其中第二个块不一样
        byte[] stale = content.clone();
        stale[SnapshotBlockStore.BLOCK_SIZE + 7] = (byte) ~stale[SnapshotBlockStore.BLOCK_SIZE + 7];
        Path target = tempRoot.resolve("target.bin");
        Files.write(target, stale);

        try (SnapshotBlockStore store = SnapshotBlockStore.openForBulkRead(blocks)) {
            store.decode(hashes, content.length, target);
        }

        assertArrayEquals(content, Files.readAllBytes(target), "就地比对之后必须是快照里的那一份");
    }

    @Test
    void garbageCollectionIsSafeOnAMissingStore() throws IOException {
        assertEquals(0, SnapshotBlockStore.collectGarbage(tempRoot.resolve("nope"), Collections.emptyList()));
    }

    private static boolean isEmptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return true;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        }
    }
}
