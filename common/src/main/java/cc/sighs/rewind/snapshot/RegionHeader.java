package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import cc.sighs.rewind.common.RewindLog;

/**
 * {@code region} / {@code entities} / {@code poi} 的 {@code .mca} 头部那 8 KiB 的解析：
 * 每个区块一条 4 字节记录（文件里的偏移、时间戳），共 1024 个区块。
 *
 * <p>磁盘格式在四个 target 覆盖的版本之间**没有差异**（四个 target 里这三段实现逐字节相同），所以整块
 * 放在 common。原地回滚靠它判断「这个区块到底动过没有」：三个 region 文件里任何一处偏移/时间戳与快照
 * 不一致，就说明建点之后这个区块被写过。
 *
 * <p>快照里的 {@code .mca} **没有实体文件**（内容按 4 KiB 块存进 {@code blocks/}），所以还要能从块存储
 * 拼出前 8 KiB；老格式槽位（整文件镜像）则直接读槽位里的文件。
 */
public final class RegionHeader {
    /** {@code .mca} 头部大小（字节）。 */
    public static final int HEADER_BYTES = 8192;
    /** 头部能描述的区块数（32 × 32）。 */
    public static final int CHUNK_COUNT = 1024;
    /** 把头部当成 int 表读出来的长度（每 4 字节一个 int）。 */
    private static final int INT_COUNT = HEADER_BYTES / 4;

    private RegionHeader() {
    }

    /**
     * 读盘解析某个 {@code .mca} 的头部；文件不存在或读不满 8 KiB 时返回一张全零表。
     *
     * <p>同一次回滚里同一个文件会被问好几次（区块 / 实体 / POI 三趟），所以带一张按路径的缓存。
     */
    public static int[] read(Map<Path, int[]> cache, Path file) {
        int[] cached = cache.get(file);
        if (cached != null) {
            return cached;
        }
        int[] header = new int[INT_COUNT];
        if (Files.isRegularFile(file)) {
            byte[] bytes = new byte[HEADER_BYTES];
            try (InputStream in = Files.newInputStream(file)) {
                if (in.readNBytes(bytes, 0, HEADER_BYTES) == HEADER_BYTES) {
                    header = decode(bytes);
                }
            } catch (IOException e) {
                RewindLog.LOGGER.warn("Rewind: cannot read region header {}", file, e);
            }
        }
        cache.put(file, header);
        return header;
    }

    /** 把 8 KiB 头部解成 int 表（大端）；不足 8 KiB 时只解前面能解的部分。 */
    public static int[] decode(byte[] bytes) {
        int[] header = new int[INT_COUNT];
        int count = Math.min(INT_COUNT, bytes.length / 4);
        for (int i = 0; i < count; i++) {
            int base = i * 4;
            header[i] = ((bytes[base] & 0xFF) << 24)
                    | ((bytes[base + 1] & 0xFF) << 16)
                    | ((bytes[base + 2] & 0xFF) << 8)
                    | (bytes[base + 3] & 0xFF);
        }
        return header;
    }

    /**
     * 快照侧的头部：{@code snapshotDir} 下那个 {@code .mca} 在块映射里就走块存储拼前 8 KiB，
     * 没有块映射（老格式槽位）就退回读槽位里的整文件。
     */
    public static int[] fromSnapshot(Map<Path, int[]> cache, SnapshotBlockStore store, Path slotDir,
            SnapshotBlocks blocks, Path snapshotDir, String name) throws IOException {
        Path file = snapshotDir.resolve(name);
        SnapshotBlocks.Entry entry = blocks == null ? null : blocks.get(SnapshotLayout.relativize(slotDir, file));
        if (entry == null) {
            return read(cache, file);
        }
        int[] cached = cache.get(file);
        if (cached != null) {
            return cached;
        }
        byte[] prefix = store.readPrefix(entry.hashes, HEADER_BYTES);
        int[] header = decode(prefix);
        cache.put(file, header);
        return header;
    }
}
