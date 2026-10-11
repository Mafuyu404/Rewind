package cc.sighs.rewind.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cc.sighs.rewind.common.spi.FlushOutcome;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.common.spi.RollbackOutcome;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * {@link RewindApi} 两个回溯入口与「开始 / 结束」钩子（加载器事件那一层）的接线。
 *
 * <p>守着三件事：开始钩子能取消——取消之后引擎一次都不跑、没有结束钩子、返回的失败里是
 * {@link RollbackCanceledException}；不取消时结束钩子拿到同一个请求对象与真实结果（成功失败都有）；
 * 只覆盖文件那条路在没有装平台实现时跳过事件、照旧干活。
 */
class RewindApiRollbackEventTest {

    @TempDir
    Path tempRoot;

    private Path world;

    @AfterEach
    void uninstallPlatform() {
        RewindPlatforms.install(null);
    }

    /** 假平台：只把这条链路用得上的方法做出来，另外记下引擎与两个钩子的调用痕迹。 */
    private final class FakePlatform implements RewindPlatform {
        int engineCalls;
        RollbackRequest seen;
        RollbackRequest reportedRequest;
        RewindResult reported;
        boolean cancel;
        String reason = "";
        Exception engineFailure;
        RollbackOutcome outcome = new RollbackOutcome("copied=0", 0, 0, 0, 1L);

        FakePlatform() {
            RewindPlatforms.install(this);
        }

        @Override
        public String modVersion() {
            return "test";
        }

        @Override
        public Path worldRoot(Object server) {
            return world;
        }

        @Override
        public boolean isSameThread(Object server) {
            return true;
        }

        @Override
        public boolean isDedicatedServer(Object server) {
            return false;
        }

        @Override
        public boolean isPublished(Object server) {
            return false;
        }

        @Override
        public boolean hasPlayers(Object server) {
            return false;
        }

        @Override
        public FlushOutcome flushForCheckpoint(Object server, String slot, String source) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public boolean supportsInPlaceRollback() {
            return true;
        }

        @Override
        public RollbackOutcome rollbackInPlace(Object server, Path worldRoot, String slot) throws Exception {
            engineCalls++;
            if (engineFailure != null) {
                throw engineFailure;
            }
            return outcome;
        }

        @Override
        public ClassLoader modClassLoader(String modId) {
            return null;
        }

        @Override
        public boolean beforeRollback(RollbackRequest request) {
            seen = request;
            if (cancel) {
                request.cancelReason = reason;
            }
            return !cancel;
        }

        @Override
        public void afterRollback(RollbackRequest request, RewindResult result) {
            reportedRequest = request;
            reported = result;
        }
    }

    private Path freshWorld() throws IOException {
        world = Files.createDirectories(tempRoot.resolve("world"));
        return world;
    }

    /** 往索引里写一条完整的槽位。 */
    private static void writeMeta(Path worldRoot, String slot) throws IOException {
        Path file = SnapshotLayout.indexFile(worldRoot);
        SnapshotIndex index = SnapshotIndex.load(file);
        SnapshotMeta meta = new SnapshotMeta();
        meta.slot = slot;
        meta.status = SnapshotLayout.STATUS_COMPLETE;
        meta.savedAtMillis = 100L;
        index.put(meta, slot);
        index.save(file);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    void aCancelledRollbackNeverRunsTheEngine() throws IOException {
        freshWorld();
        writeMeta(world, "s1");
        FakePlatform platform = new FakePlatform();
        platform.cancel = true;
        platform.reason = "正在打 BOSS";

        RewindResult result = RewindApi.rollbackInPlace("server", "s1", "key");

        assertFalse(result.success);
        assertTrue(result.failure instanceof RollbackCanceledException, String.valueOf(result.failure));
        assertEquals("正在打 BOSS", result.failure.getMessage());
        assertNotNull(platform.seen, "开始钩子应该先拿到请求");
        assertEquals("s1", platform.seen.slot);
        assertEquals("key", platform.seen.source);
        assertTrue(platform.seen.inPlace);
        assertNotNull(platform.seen.meta, "监听者要能看到目标存档点的元数据");
        assertEquals(0, platform.engineCalls, "被取消就不该动引擎");
        assertNull(platform.reported, "没有开始就没有结束");
    }

    @Test
    void aCancelWithoutAReasonGetsADefaultOne() throws IOException {
        freshWorld();
        FakePlatform platform = new FakePlatform();
        platform.cancel = true;

        RewindResult result = RewindApi.rollbackInPlace("server", "quick");

        assertTrue(result.failure instanceof RollbackCanceledException);
        assertEquals("canceled by a listener", result.failure.getMessage());
        assertEquals("api", platform.seen.source, "没写来源时按 api 记");
    }

    @Test
    void theEndHookSeesTheSameRequestAndTheResult() throws IOException {
        freshWorld();
        writeMeta(world, "s1");
        FakePlatform platform = new FakePlatform();
        platform.outcome = new RollbackOutcome("copied=3 skipped=1", 3, 1, 4, 12L);

        RewindResult result = RewindApi.rollbackInPlace("server", "s1", "command");

        assertTrue(result.success, String.valueOf(result.failure));
        assertEquals(1, platform.engineCalls);
        assertSame(platform.seen, platform.reportedRequest, "同一次回溯复用同一个请求对象");
        assertSame(result, platform.reported);
        assertEquals(3, platform.reported.copiedFiles);
        assertEquals(12L, platform.reported.millis);
    }

    @Test
    void aFailedEngineStillReportsTheEnd() throws IOException {
        freshWorld();
        FakePlatform platform = new FakePlatform();
        platform.engineFailure = new IOException("engine blew up");

        RewindResult result = RewindApi.rollbackInPlace("server", "s1");

        assertFalse(result.success);
        assertNotNull(platform.reported, "失败了也要有一次结束");
        assertFalse(platform.reported.success);
        assertSame(result, platform.reported);
    }

    @Test
    void aCancelledFileRestoreLeavesTheWorldAlone() throws IOException {
        Path worldRoot = freshWorld();
        write(worldRoot.resolve("level.dat"), "current");
        write(SnapshotLayout.slotDir(worldRoot, "s1").resolve("level.dat"), "snapshot");
        FakePlatform platform = new FakePlatform();
        platform.cancel = true;

        RewindResult result = RewindApi.restoreFiles(worldRoot, "s1", "repair");

        assertFalse(result.success);
        assertTrue(result.failure instanceof RollbackCanceledException);
        assertEquals("repair", platform.seen.source);
        assertFalse(platform.seen.inPlace, "只覆盖文件那条路 inPlace=false");
        assertEquals("current", read(worldRoot.resolve("level.dat")), "取消就一个文件都不该动");
    }

    @Test
    void theFileRestoreStillWorksWithoutAPlatform() throws IOException {
        Path worldRoot = freshWorld();
        write(worldRoot.resolve("level.dat"), "current");
        write(SnapshotLayout.slotDir(worldRoot, "s1").resolve("level.dat"), "snapshot");
        RewindPlatforms.install(null);

        RewindResult result = RewindApi.restoreFiles(worldRoot, "s1", "repair");

        assertTrue(result.success, String.valueOf(result.failure));
        assertEquals("snapshot", read(worldRoot.resolve("level.dat")));
    }
}
