package cc.sighs.rewind.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * {@link RewindApi#repairSlot(Path)}：「用存档点修复这个世界」该挑哪个存档点。
 *
 * <p>守着三件事：优先时间线的头；头不可用（不存在 / 还没写完）时退回最新的完整存档点；
 * 一个可用的都没有时返回空串，而不是抛异常。
 */
class RewindApiRepairTest {

    @TempDir
    Path tempRoot;

    /** 往索引里写一条槽位；{@code head} 非 null 时同时把时间线的头挪过去。 */
    private void write(Path worldRoot, String slot, String status, long savedAt, String head) throws IOException {
        Path file = SnapshotLayout.indexFile(worldRoot);
        SnapshotIndex index = SnapshotIndex.load(file);
        SnapshotMeta meta = new SnapshotMeta();
        meta.slot = slot;
        meta.status = status;
        meta.savedAtMillis = savedAt;
        index.put(meta, slot);
        if (head != null) {
            index.setHead(head);
        }
        index.save(file);
    }

    private Path world() throws IOException {
        return Files.createDirectories(tempRoot.resolve("world"));
    }

    @Test
    void prefersTheTimelineHead() throws IOException {
        Path world = world();
        write(world, "quick", SnapshotLayout.STATUS_COMPLETE, 100L, "quick");
        // s1 更晚，但世界当前站在 quick 上
        write(world, "s1", SnapshotLayout.STATUS_COMPLETE, 200L, null);

        assertEquals("quick", RewindApi.repairSlot(world));
    }

    @Test
    void fallsBackToTheNewestCompleteWhenTheHeadIsIncomplete() throws IOException {
        Path world = world();
        write(world, "s1", SnapshotLayout.STATUS_COMPLETE, 100L, null);
        write(world, "s2", SnapshotLayout.STATUS_COMPLETE, 300L, "s2");
        // 头指向的那个槽位还在写：不能拿它当修复目标
        write(world, "quick", SnapshotLayout.STATUS_INCOMPLETE, 200L, null);

        assertEquals("s2", RewindApi.repairSlot(world));
    }

    @Test
    void fallsBackWhenTheHeadSlotWasDeleted() throws IOException {
        Path world = world();
        write(world, "s1", SnapshotLayout.STATUS_COMPLETE, 100L, null);
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(world));
        index.setHead("gone");
        index.save(SnapshotLayout.indexFile(world));

        assertEquals("s1", RewindApi.repairSlot(world));
    }

    @Test
    void ignoresIncompleteSlots() throws IOException {
        Path world = world();
        write(world, "quick", SnapshotLayout.STATUS_INCOMPLETE, 500L, null);

        assertEquals("", RewindApi.repairSlot(world));
    }

    @Test
    void returnsEmptyWithoutAnIndex() throws IOException {
        assertEquals("", RewindApi.repairSlot(world()));
        assertEquals("", RewindApi.repairSlot(null));
    }
}
