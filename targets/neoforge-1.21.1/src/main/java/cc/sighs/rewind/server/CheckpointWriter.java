package cc.sighs.rewind.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.CoverRequest;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotManifest;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import cc.sighs.rewind.snapshot.SnapshotMirror;
import net.minecraft.server.MinecraftServer;

/**
 * 存档点的磁盘机制：把世界强制落盘后镜像进槽位，以及把槽位镜像回活动存档。
 *
 * <p>一个槽位在磁盘上是三样东西：{@code <槽位>/} 目录（世界文件镜像）、{@code <槽位>.manifest}
 * （增量清单）、{@code <槽位>.inventory}（背包快照，只在界面上展示）。后两个与槽位目录同级、
 * 不参与镜像。槽位的删除与改名在 {@link SnapshotStore}。
 *
 * <p>建点还会在索引里留下一条时间线的边：新存档点的 {@code parentSlot} 指向建点这一刻世界所在的
 * 那个存档点（索引里的 {@code head}），建完点 {@code head} 就移到新节点上。界面上的
 * 「节点树布局」画的就是这些边。
 *
 * <p>这里只有机制，没有任何客户端概念（过渡、界面、状态机）——那些在
 * {@code cc.sighs.rewind.client.CheckpointController}。对外的门面是
 * {@link cc.sighs.rewind.api.RewindApi}。
 */
public final class CheckpointWriter {
    /** 时间线成环保护：顺着 parentSlot 往上走最多这么多步。 */
    private static final int TIMELINE_WALK_LIMIT = 64;

    /** 一次磁盘操作的结果：{@code meta} 只有建立存档点时非 null。 */
    public static final class Result {
        public final SnapshotMeta meta;
        public final SnapshotMirror.Result mirror;
        public final long millis;

        Result(SnapshotMeta meta, SnapshotMirror.Result mirror, long millis) {
            this.meta = meta;
            this.mirror = mirror;
            this.millis = millis;
        }

        public String summary() {
            return mirror.summary();
        }
    }

    private CheckpointWriter() {
    }

    /**
     * 建立 / 覆盖存档点。
     *
     * <p><b>必须在服务端线程上调用。</b>线程被占住本身就是「拷贝期间没人写盘」的保证——
     * 没有界面就没法靠「暂停世界」做到这件事，让服务端线程忙在落盘和拷贝上等价于把它冻结。
     *
     * <p>先写 {@code incomplete} 再写 {@code complete}：中途崩了留下的槽位不会被当成可还原的存档点。
     */
    public static Result create(MinecraftServer server, String slot, String source) throws IOException {
        long startedNanos = System.nanoTime();
        WorldFlush.Result flushed = WorldFlush.flush(server, slot, source);
        Path world = flushed.worldRoot;
        SnapshotMeta base = flushed.meta.copy();
        // 封面：让客户端在下一帧抓一张没有界面的画面。那会儿我们正占着服务端线程、世界不 tick，
        // 所以抓到的就是存档点这一刻的样子。文件名用 savedAtMillis，索引里也是它，页面才拼得出来。
        CoverRequest.request(world.getFileName().toString(), slot, base.savedAtMillis);

        Path indexFile = SnapshotLayout.indexFile(world);
        Path manifestFile = SnapshotLayout.manifestFile(world, slot);
        Files.createDirectories(SnapshotLayout.snapshotRoot(world));

        // 覆盖一个改过名的槽位时把玩家的名字留着：名字记在索引里，不该被下一次覆盖抹掉
        SnapshotIndex index = SnapshotIndex.load(indexFile);
        SnapshotMeta previous = index.get(slot);
        if (previous != null) {
            base.displayName = previous.displayName;
        }
        // 时间线：新节点挂在建点这一刻世界所在的节点下面
        base.parentSlot = parentFor(index, slot, previous);

        SnapshotMeta incomplete = base.copy();
        incomplete.status = SnapshotLayout.STATUS_INCOMPLETE;
        index.put(incomplete, slot);
        index.save(indexFile);
        // 背包快照与槽位负载分开存（SNBT 体积大，且界面侧只在打开详情时才读）。
        // 先于镜像写：中途失败的话槽位还是 incomplete，不会被当成可还原的存档点。
        SnapshotInventory.of(flushed.inventory).save(SnapshotLayout.inventoryFile(world, slot));

        SnapshotManifest previousManifest = SnapshotManifest.load(manifestFile);
        SnapshotMirror.Result mirror = SnapshotMirror.mirror(
                world, SnapshotLayout.slotDir(world, slot), SnapshotMirror.Direction.TO_SNAPSHOT, previousManifest, null);
        mirror.manifest.save(manifestFile);

        SnapshotMeta complete = base.copy();
        complete.status = SnapshotLayout.STATUS_COMPLETE;
        complete.fileCount = mirror.files.size();
        complete.totalBytes = mirror.totalBytes;
        index = SnapshotIndex.load(indexFile);
        index.put(complete, slot);
        // 建完点，世界就站在这个节点上了：之后建的存档点都挂在它下面
        index.setHead(slot);
        index.save(indexFile);

        long millis = millisSince(startedNanos);
        Rewind.LOGGER.info("Rewind: checkpoint {} written ({}) in {} ms", slot, mirror.summary(), millis);
        return new Result(complete, mirror, millis);
    }

    /**
     * 只把槽位文件镜像回活动存档，不碰内存里的世界。
     *
     * <p>「关世界 → 覆盖 → 重开」那条回退路径用它（那时候世界已经关了，内存里没有东西要同步）；
     * 原地回滚有自己的回拷逻辑（{@link InPlaceRollback}），不走这里。
     */
    public static Result restoreFiles(Path worldRoot, String slot) throws IOException {
        long startedNanos = System.nanoTime();
        Path snapshotDir = SnapshotLayout.slotDir(worldRoot, slot);
        if (!Files.isDirectory(snapshotDir)) {
            throw new IOException("snapshot directory missing: " + snapshotDir);
        }
        // 用建点时记录的清单判断活动存档里哪些文件还是原样：没动过的不必回拷
        SnapshotManifest reference = SnapshotManifest.load(SnapshotLayout.manifestFile(worldRoot, slot));
        SnapshotMirror.Result mirror = SnapshotMirror.mirror(
                snapshotDir, worldRoot, SnapshotMirror.Direction.TO_WORLD, reference, null);
        long millis = millisSince(startedNanos);
        Rewind.LOGGER.info("Rewind: restored slot {} into {} ({}) in {} ms", slot, worldRoot, mirror.summary(), millis);
        return new Result(null, mirror, millis);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 新节点挂在谁下面：建点这一刻时间线的「头」（世界当前所在的节点）就是它的父节点。
     *
     * <p>覆盖头所在的那个槽位时，新节点顶替旧节点的位置——沿用旧节点的父节点，
     * 免得时间线上凭空多出一个自己指向自己的节点、或者把这条线的根弄丢。
     * 旧节点的父节点如果反过来挂在这个槽位下面，那宁可退回成根：断一条边好过成环。
     */
    private static String parentFor(SnapshotIndex index, String slot, SnapshotMeta previous) {
        String head = index.getHead();
        if (head.isEmpty() || index.get(head) == null) {
            // 头还没立起来（新世界），或者头所在的槽位已经被删了：这条线从这里重新起头
            return "";
        }
        if (!head.equals(slot)) {
            return head;
        }
        String previousParent = previous == null ? "" : previous.parentSlot;
        if (previousParent.isEmpty() || isDescendant(index, previousParent, slot)) {
            return "";
        }
        return previousParent;
    }

    /** {@code slot} 是不是挂在 {@code ancestor} 下面（顺着 parentSlot 往上走）。 */
    private static boolean isDescendant(SnapshotIndex index, String slot, String ancestor) {
        String current = slot;
        for (int step = 0; step < TIMELINE_WALK_LIMIT && current != null && !current.isEmpty(); step++) {
            if (current.equals(ancestor)) {
                return true;
            }
            SnapshotMeta meta = index.get(current);
            current = meta == null ? "" : meta.parentSlot;
        }
        return false;
    }
}
