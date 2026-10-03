package cc.sighs.rewind.common.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import cc.sighs.rewind.common.RewindLog;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.snapshot.SnapshotBlockStore;
import cc.sighs.rewind.snapshot.SnapshotBlocks;
import cc.sighs.rewind.snapshot.SnapshotLayout;

/**
 * 建点之后的回滚预热：在后台把「下一次回溯要读的东西」先摸一遍，把冷启动成本从玩家的那一次
 * F8 挪到建点之后。
 *
 * <p>为什么值得做：实测里原地回滚的耗时基本全在 `unloadMs + reloadMs`，而 `unloadMs` 里含着
 * 「逐区块比对 region 文件头」这一步——它要经块存储把快照里的 `.mca` 头部拼出来
 * （{@link SnapshotBlockStore} 是分片 pack + 每分片 index + 64 MB pack 缓存）。一次 JVM 会话里
 * 第一次跑这条路径时：分片 index 没读过、pack 没进缓存、解码与 NBT/RegionFile 那一堆类也还没加载，
 * 于是同一次操作要比后续慢 2–3 倍。
 *
 * <p>这里做的都是**只读**操作，不碰世界、不改任何文件：
 * <ul>
 *   <li>按块映射把每个 `.mca` 的前 {@value #HEADER_BYTES} 字节从块存储读出来（走真实的
 *       {@link SnapshotBlockStore#readPrefix} 路径：分片 index → pack → 解码），
 *       这正是回滚比对头部时要走的同一条路；</li>
 *   <li>把活动存档里对应文件的前 {@value #HEADER_BYTES} 字节读一遍（页缓存）；</li>
 *   <li>把回滚链路上的类先加载掉。</li>
 * </ul>
 *
 * <p>不做的事：区块的卸载/重载（`unloadMs` 的大头、`reloadMs` 全部）没法在不产生副作用的前提下
 * 空跑，那部分只能等真正回滚；世界已经跑过一段、页缓存被冲掉之后，这里预热的 I/O 也会失效——
 * 所以它优化的是「建点之后不久就回溯」这个最常见的节奏（自测就是 F7 之后紧接着 F8）。
 */
public final class RollbackWarmup {
    /** 与回滚比对区块头部时读的长度一致（region 文件头的 1024 偏移 + 1024 时间戳）。 */
    private static final int HEADER_BYTES = 8192;

    /** 同一时刻只跑一次预热；不排队——旧的还没跑完时新的那次建点没必要再热一遍。 */
    private static final AtomicBoolean running = new AtomicBoolean();

    /** 回滚链路上值得先加载掉的类；取不到就跳过（某些 target 没有原地回滚）。 */
    private static final String[] ROLLBACK_CLASSES = {
            "cc.sighs.rewind.common.store.SnapshotBlockIo",
            "cc.sighs.rewind.common.store.SnapshotStore",
            "cc.sighs.rewind.snapshot.SnapshotBlockStore",
            "cc.sighs.rewind.snapshot.SnapshotBlocks",
            "cc.sighs.rewind.snapshot.SnapshotManifest",
            "cc.sighs.rewind.snapshot.SnapshotMirror",
            "cc.sighs.rewind.server.InPlaceRollback",
            "net.minecraft.world.level.chunk.storage.RegionFile",
            "net.minecraft.nbt.NbtIo",
            "net.minecraft.nbt.NbtAccounter",
    };

    private RollbackWarmup() {
    }

    /**
     * 建点写完之后调用。立刻返回，实际工作在一条低优先级的守护线程上跑。
     *
     * @param worldRoot 活动存档目录
     * @param slot      刚写好的槽位
     */
    public static void afterCheckpoint(Path worldRoot, String slot) {
        if (worldRoot == null || slot == null) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            long startedNanos = System.nanoTime();
            try {
                warm(worldRoot, slot);
            } catch (Throwable t) {
                RewindLog.LOGGER.warn("Rewind: rollback warm-up failed", t);
            } finally {
                running.set(false);
                RewindLog.LOGGER.info("Rewind: rollback warm-up for {} finished in {} ms",
                        slot, (System.nanoTime() - startedNanos) / 1_000_000L);
            }
        }, "rewind-rollback-warmup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void warm(Path worldRoot, String slot) throws IOException {
        // 老格式槽位（整文件、没有块映射）没有块存储可热，直接跳过
        SnapshotBlocks blocks = SnapshotBlocks.load(SnapshotLayout.blockMapFile(worldRoot, slot));
        if (blocks.size() == 0) {
            return;
        }
        byte[] buffer = new byte[HEADER_BYTES];
        try (SnapshotBlockStore store = SnapshotBlockStore.openForBulkRead(SnapshotLayout.blocksRoot(worldRoot))) {
            for (String relative : blocks.paths()) {
                // 真回滚开始了就别再抢 I/O
                if (RewindState.isDiscarding()) {
                    return;
                }
                SnapshotBlocks.Entry entry = blocks.get(relative);
                if (entry == null || entry.hashes.isEmpty()) {
                    continue;
                }
                if (SnapshotBlockStore.isEncoded(relative)) {
                    // 走的就是回滚比对头部那条路：分片 index → pack → 解码
                    store.readPrefix(entry.hashes, HEADER_BYTES);
                }
                readPrefix(worldRoot.resolve(relative), buffer);
            }
        }
        loadRollbackClasses();
    }

    /** 读一个文件的前 {@value #HEADER_BYTES} 字节，只为把页缓存摸热。 */
    private static void readPrefix(Path file, byte[] buffer) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            int total = 0;
            while (total < buffer.length) {
                int read = in.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
        } catch (IOException ignored) {
            // 预热失败不影响任何功能
        }
    }

    private static void loadRollbackClasses() {
        for (String name : ROLLBACK_CLASSES) {
            try {
                // 只加载不初始化：静态初始化里可能有平台相关的东西
                Class.forName(name, false, RollbackWarmup.class.getClassLoader());
            } catch (Throwable ignored) {
                // 该 target 没有这个类（例如没有原地回滚），跳过
            }
        }
    }
}
