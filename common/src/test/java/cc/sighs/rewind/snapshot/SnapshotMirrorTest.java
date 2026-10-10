package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SnapshotMirror} 的增量判定与 4 KiB 块编码。
 *
 * <p>重点守着三件事：块编码的文件能原样重建；改了一个扇区就只为那一个扇区付字节；
 * 老格式（槽位里是整文件、没有块映射）照旧能还原。
 */
class SnapshotMirrorTest {

    @TempDir
    Path tempRoot;

    private Path world;
    private Path slotA;
    private Path slotB;
    private Path blocksRoot;

    private void prepare() throws IOException {
        world = Files.createDirectories(tempRoot.resolve("world"));
        Path snapshots = Files.createDirectories(world.resolve(SnapshotLayout.ROOT_DIR_NAME));
        slotA = Files.createDirectories(snapshots.resolve("a"));
        slotB = Files.createDirectories(snapshots.resolve("b"));
        blocksRoot = Files.createDirectories(snapshots.resolve(SnapshotLayout.BLOCKS_DIR_NAME));
    }

    private static void write(Path file, String content, long modifiedMillis) throws IOException {
        writeBytes(file, content.getBytes(StandardCharsets.UTF_8), modifiedMillis);
    }

    private static void writeBytes(Path file, byte[] content, long modifiedMillis) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedMillis));
    }

    /** 伪随机内容：不能是常量，否则每个 4 KiB 块都一样、会被块去重吃掉，测不出想测的东西。 */
    private static byte[] regionBytes(int size, int seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static long modifiedOf(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toMillis();
    }

    private SnapshotMirror.Result snapshot(Path source, Path target, SnapshotManifest reference,
            SnapshotBlocks previousMap, SnapshotBlocks writtenMap) throws IOException {
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocksRoot)) {
            return SnapshotMirror.mirror(source, target, SnapshotMirror.Direction.TO_SNAPSHOT, reference, null,
                    new SnapshotMirror.Blocks(store, previousMap, writtenMap));
        }
    }

    private SnapshotMirror.Result restore(Path source, Path target, SnapshotManifest manifest, SnapshotBlocks map)
            throws IOException {
        try (SnapshotBlockStore store = SnapshotBlockStore.openForBulkRead(blocksRoot)) {
            return SnapshotMirror.mirror(source, target, SnapshotMirror.Direction.TO_WORLD, manifest, null,
                    new SnapshotMirror.Blocks(store, map, null));
        }
    }

    @Test
    void encodesRegionFilesAndRebuildsThemByteForByte() throws IOException {
        prepare();
        byte[] content = regionBytes(9000, 1);
        writeBytes(world.resolve("region/r.0.0.mca"), content, 1_000L);
        SnapshotBlocks map = SnapshotBlocks.empty();

        SnapshotMirror.Result first = snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(), map);

        assertEquals(1, first.encoded);
        assertEquals(1, first.copied);
        assertTrue(map.contains("region/r.0.0.mca"));
        assertFalse(Files.exists(slotA.resolve("region/r.0.0.mca")), "块编码之后槽位里不该再有实体文件");

        // 世界里那个文件变了，从快照还原回去
        writeBytes(world.resolve("region/r.0.0.mca"), regionBytes(9, 2), 2_000L);
        SnapshotMirror.Result restored = restore(slotA, world, first.manifest, map);

        assertEquals(1, restored.decoded);
        assertEquals(1, restored.copied);
        assertArrayEquals(content, Files.readAllBytes(world.resolve("region/r.0.0.mca")));
        assertEquals(1_000L, modifiedOf(world.resolve("region/r.0.0.mca")), "还原要把 mtime 拨回建点时的值");
    }

    @Test
    void onlyWritesTheBlocksThatActuallyChanged() throws IOException {
        prepare();
        byte[] original = regionBytes(64 * 1024, 3); // 16 个块
        writeBytes(world.resolve("region/r.0.0.mca"), original, 1_000L);
        SnapshotBlocks firstMap = SnapshotBlocks.empty();
        snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(), firstMap);

        byte[] changed = original.clone();
        changed[SnapshotBlockStore.BLOCK_SIZE * 5] = (byte) ~changed[SnapshotBlockStore.BLOCK_SIZE * 5];
        writeBytes(world.resolve("region/r.0.0.mca"), changed, 2_000L);
        SnapshotBlocks secondMap = SnapshotBlocks.empty();
        SnapshotMirror.Result second = snapshot(world, slotB, SnapshotManifest.empty(), SnapshotBlocks.empty(),
                secondMap);

        assertEquals(1, second.encoded);
        assertEquals(0, second.copied - second.encoded, "整文件拷贝不该发生");
        assertEquals(SnapshotBlockStore.BLOCK_SIZE, second.copiedBytes, "改一个字节只该多写一个 4 KiB 块");
        assertEquals(16, secondMap.blockCount());
    }

    @Test
    void reusesTheBlockMapWithoutWritingWhenTheFileIsUnchanged() throws IOException {
        prepare();
        writeBytes(world.resolve("region/r.0.0.mca"), regionBytes(20_000, 4), 1_000L);
        SnapshotBlocks firstMap = SnapshotBlocks.empty();
        SnapshotMirror.Result first = snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(),
                firstMap);

        SnapshotBlocks secondMap = SnapshotBlocks.empty();
        SnapshotMirror.Result second = snapshot(world, slotA, first.manifest, firstMap, secondMap);

        assertEquals(1, second.reused);
        assertEquals(0, second.encoded);
        assertEquals(0, second.copied);
        assertEquals(0L, second.copiedBytes);
        assertEquals(firstMap.get("region/r.0.0.mca").hashes, secondMap.get("region/r.0.0.mca").hashes);
    }

    @Test
    void sharesBlocksBetweenSlotsInsteadOfCopyingThemAgain() throws IOException {
        prepare();
        writeBytes(world.resolve("region/r.0.0.mca"), regionBytes(40_000, 5), 1_000L);
        SnapshotBlocks firstMap = SnapshotBlocks.empty();
        snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(), firstMap);

        SnapshotBlocks secondMap = SnapshotBlocks.empty();
        SnapshotMirror.Result second = snapshot(world, slotB, SnapshotManifest.empty(), SnapshotBlocks.empty(),
                secondMap);

        assertEquals(1, second.encoded);
        assertEquals(0L, second.copiedBytes, "内容一样的文件，第二个槽位一个字节都不该写");
        assertEquals(firstMap.get("region/r.0.0.mca").hashes, secondMap.get("region/r.0.0.mca").hashes);
    }

    @Test
    void copiesNonMcaFilesAndDropsDeletedFilesFromTheMap() throws IOException {
        prepare();
        write(world.resolve("level.dat"), "level", 1_000L);
        writeBytes(world.resolve("region/r.0.0.mca"), regionBytes(5_000, 6), 1_000L);
        writeBytes(world.resolve("region/r.0.1.mca"), regionBytes(5_000, 7), 1_000L);
        SnapshotBlocks map = SnapshotBlocks.empty();
        SnapshotMirror.Result first = snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(), map);

        assertTrue(Files.isRegularFile(slotA.resolve("level.dat")), "非 .mca 照旧整文件镜像");
        assertEquals(2, first.encoded);
        assertEquals(3, first.copied);

        Files.delete(world.resolve("region/r.0.1.mca"));
        SnapshotBlocks map2 = SnapshotBlocks.empty();
        snapshot(world, slotA, first.manifest, map, map2);

        assertEquals(1, map2.size());
        assertFalse(map2.contains("region/r.0.1.mca"));
    }

    @Test
    void restoresLegacySlotsThatHaveNoBlockMap() throws IOException {
        prepare();
        byte[] content = regionBytes(9_000, 8);
        // 老格式：槽位里是整文件，索引里也没有块映射
        writeBytes(slotA.resolve("region/r.0.0.mca"), content, 1_000L);
        SnapshotManifest manifest = SnapshotManifest.empty();
        manifest.put("region/r.0.0.mca", content.length, 1_000L);
        writeBytes(world.resolve("region/r.0.0.mca"), regionBytes(9, 9), 5_000L);

        SnapshotMirror.Result restored = restore(slotA, world, manifest, SnapshotBlocks.empty());

        assertEquals(0, restored.decoded);
        assertEquals(1, restored.copied);
        assertArrayEquals(content, Files.readAllBytes(world.resolve("region/r.0.0.mca")));
    }

    @Test
    void migratingAnOldSlotOverwritesItsWholeFilesWithBlocks() throws IOException {
        prepare();
        // 老槽位里留着 region 的整文件
        writeBytes(slotA.resolve("region/r.0.0.mca"), regionBytes(9_000, 10), 500L);
        byte[] content = regionBytes(9_000, 11);
        writeBytes(world.resolve("region/r.0.0.mca"), content, 1_000L);
        SnapshotBlocks map = SnapshotBlocks.empty();

        snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(), map);

        assertTrue(map.contains("region/r.0.0.mca"));
        assertFalse(Files.exists(slotA.resolve("region/r.0.0.mca")), "老格式的整文件必须被清掉，不能伪装成权威内容");
    }

    @Test
    void failsLoudlyWhenABlockIsMissingInsteadOfWritingAHalfFile() throws IOException {
        prepare();
        writeBytes(world.resolve("region/r.0.0.mca"), regionBytes(9_000, 12), 1_000L);
        SnapshotBlocks map = SnapshotBlocks.empty();
        snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(), map);

        // 指到一个空存储：这个文件的块全都「缺失」
        Path empty = Files.createDirectories(tempRoot.resolve("empty-blocks"));
        SnapshotBlocks.Entry entry = map.get("region/r.0.0.mca");
        Path live = world.resolve("region/r.0.0.mca");
        byte[] modified = regionBytes(9_000, 13);
        writeBytes(live, modified, 9_000L);

        try (SnapshotBlockStore store = SnapshotBlockStore.open(empty)) {
            assertThrows(IOException.class, () -> store.decode(entry.hashes, entry.size, live));
        }
        assertArrayEquals(modified, Files.readAllBytes(live), "重建失败不该在目标位置留下半个文件");
    }

    @Test
    void skipsFilesTheWorldAlreadyMatchesAndDeletesExtras() throws IOException {
        prepare();
        write(world.resolve("f.dat"), "aaa", 1_000L);
        write(world.resolve("drop.dat"), "drop", 1_000L);
        SnapshotMirror.Result first = snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(),
                SnapshotBlocks.empty());
        assertEquals(2, first.copied);

        SnapshotMirror.Result restored = restore(slotA, world, first.manifest, SnapshotBlocks.empty());
        assertEquals(2, restored.skipped, "世界里还是原样，一个都不该回拷");

        Files.delete(world.resolve("drop.dat"));
        SnapshotMirror.Result again = snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(),
                SnapshotBlocks.empty());
        assertEquals(1, again.deleted);
        assertFalse(Files.exists(slotA.resolve("drop.dat")));
    }

    @Test
    void neverCopiesTheSnapshotDirectoryOrStrandedTempFiles() throws IOException {
        prepare();
        write(world.resolve(SnapshotLayout.ROOT_DIR_NAME + "/stranded"), "x", 1_000L);
        write(world.resolve("session.lock"), "lock", 1_000L);
        write(world.resolve("region/r.0.0.mca" + SnapshotMirror.TEMP_SUFFIX), "half", 1_000L);
        write(world.resolve("region/r.0.0.mca"), "real", 1_000L);
        SnapshotBlocks map = SnapshotBlocks.empty();

        SnapshotMirror.Result result = snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(), map);

        assertEquals(1, result.files.size());
        assertEquals(1, result.encoded);
        assertFalse(Files.exists(slotA.resolve("session.lock")));
    }

    @Test
    void neverTouchesTheLodModsWorldData() throws IOException {
        prepare();
        write(world.resolve("level.dat"), "level", 1_000L);
        write(world.resolve("voxy/storage/LOCK"), "", 1_000L);
        write(world.resolve("voxy/storage/000001.sst"), "lod", 1_000L);

        SnapshotMirror.Result first = snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(),
                SnapshotBlocks.empty());

        assertEquals(1, first.files.size(), "只有 level.dat 进快照");
        assertFalse(Files.exists(slotA.resolve("voxy")), "跨视距模组的 LOD 库不该被镜像进槽位");
        assertFalse(first.manifest.paths().contains("voxy/storage/LOCK"), "清单里不该留下它的条目");

        SnapshotMirror.Result restored = restore(slotA, world, first.manifest, SnapshotBlocks.empty());

        assertEquals(0, restored.deleted, "回溯不该把模组自己的数据当多余文件删掉");
        assertTrue(Files.exists(world.resolve("voxy/storage/LOCK")));
        assertTrue(Files.exists(world.resolve("voxy/storage/000001.sst")));
    }

    @Test
    void ignoresExcludedPathsThatAnOldManifestStillReferences() throws IOException {
        prepare();
        write(world.resolve("voxy/storage/LOCK"), "", 1_000L);
        // 排除规则是后加的：老槽位的清单里还记着这个路径
        SnapshotManifest manifest = SnapshotManifest.empty();
        manifest.put("voxy/storage/LOCK", 0L, 1_000L);

        SnapshotMirror.Result restored = restore(slotA, world, manifest, SnapshotBlocks.empty());

        assertEquals(0, restored.copied, "老清单里记着的排除路径不该去回拷");
        assertEquals(0, restored.deleted);
        assertTrue(Files.exists(world.resolve("voxy/storage/LOCK")), "被排除的路径既不该回拷也不该删");
    }

    @Test
    void keepsGoingWhenAFileCannotBeCopied() throws IOException {
        prepare();
        write(world.resolve("level.dat"), "level", 1_000L);
        write(world.resolve("locked.dat"), "locked", 1_000L);
        // 目标位置上放一个**非空**目录：最后那步替换必然失败（空目录会被直接替换掉），
        // 用来造成一次「这个文件动不了」。真实场景是文件被别的进程锁着（Windows 上表现为共享冲突），
        // 纯 Java 造不出那种占用。
        Files.createDirectories(slotA.resolve("locked.dat/inner"));

        SnapshotMirror.Result first = snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(),
                SnapshotBlocks.empty());

        assertEquals(1, first.untouched, "一个文件动不了不该让整次建点失败");
        assertEquals(1, first.copied);
        assertEquals(1, first.files.size(), "只有 level.dat 进了快照");
        assertTrue(first.manifest.get("locked.dat").untouched(), "动不了的文件要在清单里留一条「没动它」");
        assertFalse(Files.exists(slotA.resolve("locked.dat" + SnapshotMirror.TEMP_SUFFIX)), "失败留下的临时文件要清掉");

        // 「没动它」要能落盘、能读回来：它是跨进程的约定，回溯时才认得出这个文件不能碰
        Path manifestFile = tempRoot.resolve("left-alone.manifest");
        first.manifest.save(manifestFile);
        SnapshotManifest reloaded = SnapshotManifest.load(manifestFile);
        assertTrue(reloaded.get("locked.dat").untouched());
        assertFalse(reloaded.matches("locked.dat", 6L, 1_000L), "「没动它」不该被当成「内容没变」");
    }

    @Test
    void leavesUntouchedFilesAloneWhenRestoring() throws IOException {
        prepare();
        write(world.resolve("locked.dat"), "current", 5_000L);
        // 槽位里还留着一份老内容，但清单记着建点时没动它：不能拿老内容盖回去，也不能删掉世界里的
        write(slotA.resolve("locked.dat"), "stale", 1_000L);
        SnapshotManifest manifest = SnapshotManifest.empty();
        manifest.putUntouched("locked.dat");

        SnapshotMirror.Result restored = restore(slotA, world, manifest, SnapshotBlocks.empty());

        assertEquals(0, restored.copied);
        assertEquals(0, restored.deleted);
        assertEquals(1, restored.untouched);
        assertEquals("current", new String(Files.readAllBytes(world.resolve("locked.dat")), StandardCharsets.UTF_8));
    }

    @Test
    void keepsGoingWhenAFileCannotBeDeleted() throws IOException {
        prepare();
        write(world.resolve("level.dat"), "level", 1_000L);
        Path extra = slotA.resolve("drop.dat");
        write(extra, "drop", 1_000L);
        DosFileAttributeView dos = Files.getFileAttributeView(extra, DosFileAttributeView.class);
        assumeTrue(dos != null, "「只读文件删不掉」是 Windows 的行为，别的文件系统上这条没有意义");
        dos.setReadOnly(true);
        try {
            SnapshotMirror.Result result = snapshot(world, slotA, SnapshotManifest.empty(), SnapshotBlocks.empty(),
                    SnapshotBlocks.empty());

            assertEquals(1, result.undeleted, "删不掉的多余文件留着就是，不该让整次建点失败");
            assertTrue(Files.exists(extra));
        } finally {
            // 留着只读会让 @TempDir 的清理也失败
            dos.setReadOnly(false);
        }
    }

    @Test
    void withoutBlocksContextItBehavesLikeTheOldWholeFileMirror() throws IOException {
        prepare();
        writeBytes(world.resolve("region/r.0.0.mca"), regionBytes(9_000, 14), 1_000L);
        SnapshotMirror.Result result = SnapshotMirror.mirror(world, slotA,
                SnapshotMirror.Direction.TO_SNAPSHOT, SnapshotManifest.empty(), null);
        assertEquals(1, result.copied);
        assertEquals(0, result.encoded);
        assertTrue(Files.isRegularFile(slotA.resolve("region/r.0.0.mca")));
    }
}
