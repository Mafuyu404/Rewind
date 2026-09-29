package cc.sighs.rewind.snapshot;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 4 KiB 内容寻址块存储。
 *
 * <p>为什么按块：存档里几乎全部字节都在 region / entities / poi 的 .mca 上，而 .mca 本来就是
 * 4 KiB 扇区结构的——写一个区块只重写少数几个扇区，整个文件却因此「变了」。整文件级的增量只能
 * 省下「从来没被写过的文件」（实测在一份 30 MB、全在活动区的小世界上只有 8%），按扇区切块之后
 * 同一份数据省 80%。
 *
 * <p><b>为什么块要打包，而不是一个块一个文件</b>：4 KiB 一个文件意味着每 30 MB 要创建约 7400 个
 * 小文件。本机实测「一文件一块」写 7400 块要 14.7 秒（1.99 ms/块，Windows 上创建小文件要过杀毒
 * 扫描），而按前两个十六进制字符分 256 个追加写的 pack 只要 0.60 秒——24 倍。读侧同理：重建
 * 28 MB 要打开 6800 个文件，实测把原地回滚的回拷阶段从 72 ms 拖到 717 ms。所以块按分片打包：
 *
 * <pre>
 * blocks/&lt;前两位&gt;/index       第一行 {@code #rewind-blocks 1 &lt;pack 文件名&gt;}，之后每行 &lt;哈希&gt; &lt;偏移&gt; &lt;长度&gt;
 * blocks/&lt;前两位&gt;/&lt;pack 名&gt;   4 KiB 块的原始拼接
 * </pre>
 *
 * <p><b>切换是原子的</b>：压缩时先写新的 pack 与新的 index，然后只原子替换 index 这一份文件——
 * index 里写着它配的是哪个 pack，所以「指过去了」「指回来」都只取决于这一个文件。崩在任何一步
 * 之前，旧的那一对都还是完整的；崩之后，没被 index 提到的 pack 就是垃圾，下次压缩顺手删掉。
 *
 * <p>块是不可变的（写进去就不再改），所以多个槽位引用同一个块永远安全。
 *
 * <p>用法是一次会话：{@link #open} → 若干次 {@link #encode} / {@link #decode} → {@link #close}
 * （close 才会把这次新收的块真正落盘）。
 */
public final class SnapshotBlockStore implements Closeable {
    /** 块大小：与原版 .mca 的扇区一致。 */
    public static final int BLOCK_SIZE = 4096;
    /** 哈希取前 16 字节（32 个十六进制字符）：足够避免碰撞，索引也不至于太大。 */
    private static final int HASH_BYTES = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String PACK_FILE = "pack";
    private static final String INDEX_FILE = "index";
    private static final String INDEX_HEADER = "#rewind-blocks 1 ";
    /** 写文件时用的临时后缀（{@code SnapshotLayout.isExcluded} 会排除它）。 */
    private static final String TEMP_SUFFIX = ".rewind-tmp";
    /**
     * 整包缓存的上限：重建一份 .mca 要读上千个块，这些块散在几百个分片里，每块一次随机读的代价
     * 按块数走。存储整体装得下就改成整包顺序读；装不下就退回随机读。
     */
    private static final long PACK_CACHE_LIMIT = 64L * 1024 * 1024;

    /** 一次 {@link #encode} 的结果。 */
    public static final class Stored {
        public final List<String> hashes;
        /** 其中真正新写盘的字节数（存储里已经有的块不算，它们是别的槽位付过的账）。 */
        public final long newBytes;

        Stored(List<String> hashes, long newBytes) {
            this.hashes = hashes;
            this.newBytes = newBytes;
        }
    }

    /** 一个分片的索引：它指向哪个 pack，以及每个块在 pack 里的位置。 */
    private static final class Shard {
        final String packName;
        final Map<String, long[]> entries;

        Shard(String packName, Map<String, long[]> entries) {
            this.packName = packName;
            this.entries = entries;
        }
    }

    private final Path blocksRoot;
    /** 这次会话会不会大量按序读（重建文件）；会，才去考虑把整包读进内存。 */
    private final boolean bulkReads;
    /** 分片 → 索引（按需加载）。 */
    private final Map<String, Shard> shards = new HashMap<>();
    /** 本次会话新收、还没落盘的块：分片 → 哈希 → 块数据。 */
    private final Map<String, Map<String, byte[]>> pending = new HashMap<>();
    /** 读时打开的 pack 句柄（按需打开，close 时统一关掉）。 */
    private final Map<Path, FileChannel> channels = new HashMap<>();
    /** 整包缓存：分片 → pack 的全部字节。 */
    private final Map<String, byte[]> packCache = new HashMap<>();
    /** {@code null} = 还没探过；true = 用整包缓存；false = 每块随机读。 */
    private Boolean wholePacks;
    private boolean closed;

    private SnapshotBlockStore(Path blocksRoot, boolean bulkReads) {
        this.blocksRoot = blocksRoot;
        this.bulkReads = bulkReads;
    }

    /** 打开一个块存储会话。用完必须 {@link #close()}，否则这次收的块不会落盘。 */
    public static SnapshotBlockStore open(Path blocksRoot) {
        return new SnapshotBlockStore(blocksRoot, false);
    }

    /**
     * 为了按序重建文件而打开的会话。
     *
     * <p>重建一份 .mca 要读上千个块，而这些块散在几百个分片里——每块一次随机读，代价按块数走。
     * 存储整体装得下内存的话，把 pack 成片读进来（每个分片一次顺序读）快得多；装不下就自动退回
     * 每块随机读，只影响速度，不影响正确性。
     */
    public static SnapshotBlockStore openForBulkRead(Path blocksRoot) {
        return new SnapshotBlockStore(blocksRoot, true);
    }

    /**
     * 这个相对路径要不要按块存取。
     *
     * <p>只覆盖 region / entities / poi 下的 .mca / .mcc：它们是存档字节的绝对大头，又本来就是
     * 4 KiB 扇区的。别的文件（level.dat、playerdata、数据包……）加起来只有几十 KB，当普通文件镜像即可。
     */
    public static boolean isEncoded(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String path = SnapshotLayout.normalize(relativePath);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (!name.endsWith(".mca") && !name.endsWith(".mcc")) {
            return false;
        }
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash);
        String dir = parent.substring(parent.lastIndexOf('/') + 1);
        return "region".equals(dir) || "entities".equals(dir) || "poi".equals(dir);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 把 sourceFile 按 4 KiB 切块收进这次会话，返回块哈希序列与真正新写盘的字节数。
     *
     * <p>已经存在的块不重复收——内容寻址天然去重：内容一样的块，无论来自哪个槽位、哪个版本，
     * 都落在同一个位置。<b>真正落盘发生在 {@link #close()}</b>，所以期间不读也不写存储。
     *
     * <p>注意这里只做「收」，不做「落盘」：一份 .mca 里成片的空扇区是同一个全零块，如果每遇到
     * 一次重复就落一次盘，索引会被反复重写，代价随块数平方增长。
     */
    public Stored encode(Path sourceFile) throws IOException {
        List<String> hashes = new ArrayList<>();
        long newBytes = 0L;
        byte[] buffer = new byte[BLOCK_SIZE];
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(sourceFile)) {
            int read;
            while ((read = readFully(in, buffer)) > 0) {
                // 必须复制：buffer 会被下一轮覆盖，而这些块要留到 close() 才写
                byte[] block = Arrays.copyOf(buffer, read);
                String hash = hex(digest.digest(block));
                hashes.add(hash);
                if (contains(hash)) {
                    continue;
                }
                pending.computeIfAbsent(shardOf(hash), key -> new LinkedHashMap<>()).put(hash, block);
                newBytes += read;
            }
        }
        return new Stored(hashes, newBytes);
    }

    /** 这个块已经有了吗（已经在存储里，或者这次会话已经收进去了）。 */
    private boolean contains(String hash) {
        Map<String, byte[]> buffered = pending.get(shardOf(hash));
        if (buffered != null && buffered.containsKey(hash)) {
            return true;
        }
        return loadShard(shardOf(hash)).entries.containsKey(hash);
    }

    /** 把这次收的块按分片追加落盘，并把真实偏移回填进内存索引。 */
    private void flushPending() throws IOException {
        for (Map.Entry<String, Map<String, byte[]>> shardEntry : pending.entrySet()) {
            String shard = shardEntry.getKey();
            Shard index = loadShard(shard);
            Path dir = shardDir(shard);
            Files.createDirectories(dir);
            Path pack = dir.resolve(index.packName);
            long offset = Files.isRegularFile(pack) ? Files.size(pack) : 0L;
            StringBuilder lines = new StringBuilder();
            try (OutputStream out = Files.newOutputStream(pack,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (Map.Entry<String, byte[]> blockEntry : shardEntry.getValue().entrySet()) {
                    byte[] block = blockEntry.getValue();
                    out.write(block);
                    index.entries.put(blockEntry.getKey(), new long[]{offset, block.length});
                    lines.append(blockEntry.getKey()).append(' ').append(offset)
                            .append(' ').append(block.length).append('\n');
                    offset += block.length;
                }
            }
            // pack 先落、索引后追加：中途崩了只会留下「没人引用」的字节，不会留下指向空气的索引
            Files.write(dir.resolve(INDEX_FILE), lines.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        pending.clear();
    }

    // ------------------------------------------------------------------ 读

    /**
     * 按块哈希序列把文件重建成 target。
     *
     * <p>目标已经有一份**同样大小**的内容时逐块比对，只写真正不一样的块——这是这套存储真正的意义：
     * 一次强制落盘会把 region 文件的 mtime 拨走，但里面可能只有一两个扇区真的变了（甚至只有
     * 头部里的时间戳）。其余情况（目标不存在、大小不同）写临时文件再原子替换，中途失败不会在
     * 目标位置留下半个文件。
     *
     * <p>块缺失是存储损坏，直接抛出去——让回溯失败，而不是悄悄写出一份残缺的世界。逐块比对那条路
     * 会先把所有块查一遍再动手，所以缺块不会留下「改了一半」的目标。
     */
    public void decode(List<String> hashes, long size, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isRegularFile(target) && Files.size(target) == size) {
            patchInPlace(hashes, size, target);
            return;
        }
        writeFresh(hashes, size, target);
    }

    /** 目标已经有同样大小的内容：逐块比对，只写不一样的块。 */
    private void patchInPlace(List<String> hashes, long size, Path target) throws IOException {
        // 先把块都查一遍：缺块要在这里就抛出来，不能等改了一半才发现
        for (String hash : hashes) {
            require(hash, target);
        }
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            byte[] current = new byte[BLOCK_SIZE];
            byte[] wanted = new byte[BLOCK_SIZE];
            long offset = 0L;
            for (String hash : hashes) {
                long[] location = require(hash, target);
                int length = (int) location[1];
                readAt(hash, location[0], wanted, length);
                ByteBuffer currentBuffer = ByteBuffer.wrap(current, 0, length);
                int read = 0;
                while (read < length) {
                    int count = channel.read(currentBuffer, offset + read);
                    if (count < 0) {
                        throw new EOFException("target " + target + " is truncated");
                    }
                    read += count;
                }
                if (!sameBytes(current, wanted, length)) {
                    ByteBuffer source = ByteBuffer.wrap(wanted, 0, length);
                    int written = 0;
                    while (written < length) {
                        written += channel.write(source, offset + written);
                    }
                }
                offset += length;
            }
        }
    }

    /** 目标不存在或大小不同：写临时文件再原子替换。 */
    private void writeFresh(List<String> hashes, long size, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName().toString() + TEMP_SUFFIX);
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[BLOCK_SIZE];
                for (String hash : hashes) {
                    long[] location = require(hash, target);
                    int length = (int) location[1];
                    readAt(hash, location[0], buffer, length);
                    out.write(buffer, 0, length);
                }
            }
            long written = Files.size(temp);
            if (written != size) {
                throw new IOException("rebuilt " + target + " is " + written + " bytes, expected " + size);
            }
            moveAtomic(temp, target);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static boolean sameBytes(byte[] left, byte[] right, int length) {
        for (int i = 0; i < length; i++) {
            if (left[i] != right[i]) {
                return false;
            }
        }
        return true;
    }

    /** 只读前 {@code length} 个字节（原地回滚要拿快照的 .mca 头部做逐区块比对）。 */
    public byte[] readPrefix(List<String> hashes, int length) throws IOException {
        byte[] out = new byte[length];
        byte[] buffer = new byte[BLOCK_SIZE];
        int written = 0;
        for (String hash : hashes) {
            if (written >= length) {
                break;
            }
            long[] location = require(hash, null);
            int count = (int) Math.min(location[1], length - written);
            readAt(hash, location[0], buffer, count);
            System.arraycopy(buffer, 0, out, written, count);
            written += count;
        }
        return out;
    }

    private long[] require(String hash, Path target) throws IOException {
        if (!pending.isEmpty()) {
            // 读之前先把这次会话收的块落下去（正常用法里读与写不会同会话，这里只是兜底）
            flushPending();
        }
        long[] location = loadShard(shardOf(hash)).entries.get(hash);
        if (location == null) {
            throw new IOException("block storage is missing block " + hash
                    + (target == null ? "" : " needed by " + target));
        }
        return location;
    }

    private void readAt(String hash, long offset, byte[] buffer, int length) throws IOException {
        if (useWholePacks()) {
            byte[] pack = packBytes(hash);
            if (offset < 0 || offset + length > pack.length) {
                throw new EOFException("block " + hash + " is truncated");
            }
            System.arraycopy(pack, (int) offset, buffer, 0, length);
            return;
        }
        FileChannel channel = channel(hash);
        ByteBuffer target = ByteBuffer.wrap(buffer, 0, length);
        int read = 0;
        while (read < length) {
            int count = channel.read(target, offset + read);
            if (count < 0) {
                throw new EOFException("block " + hash + " is truncated");
            }
            read += count;
        }
    }

    private FileChannel channel(String hash) throws IOException {
        String shard = shardOf(hash);
        Path pack = shardDir(shard).resolve(loadShard(shard).packName);
        FileChannel cached = channels.get(pack);
        if (cached == null) {
            cached = FileChannel.open(pack, StandardOpenOption.READ);
            channels.put(pack, cached);
        }
        return cached;
    }

    /** 这次会话走不走整包缓存：只有为大段按序读开的会话才考虑，而且存储得整体装得下。 */
    private boolean useWholePacks() {
        Boolean decided = wholePacks;
        if (decided != null) {
            return decided;
        }
        boolean fits = false;
        if (bulkReads && Files.isDirectory(blocksRoot)) {
            long total = 0L;
            try (DirectoryStream<Path> shardDirs = Files.newDirectoryStream(blocksRoot)) {
                for (Path shardDir : shardDirs) {
                    if (!Files.isDirectory(shardDir)) {
                        continue;
                    }
                    Path pack = shardDir.resolve(readShard(shardDir).packName);
                    if (Files.isRegularFile(pack)) {
                        total += Files.size(pack);
                    }
                    if (total > PACK_CACHE_LIMIT) {
                        break;
                    }
                }
                fits = total <= PACK_CACHE_LIMIT;
            } catch (IOException e) {
                fits = false;
            }
        }
        wholePacks = fits;
        return fits;
    }

    /** 一个分片 pack 的全部字节（按分片缓存）。 */
    private byte[] packBytes(String hash) throws IOException {
        String shard = shardOf(hash);
        byte[] cached = packCache.get(shard);
        if (cached != null) {
            return cached;
        }
        Path pack = shardDir(shard).resolve(loadShard(shard).packName);
        byte[] bytes = Files.readAllBytes(pack);
        packCache.put(shard, bytes);
        return bytes;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flushPending();
        } finally {
            for (FileChannel channel : channels.values()) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // 关不掉也没关系
                }
            }
            channels.clear();
            packCache.clear();
        }
    }

    // ------------------------------------------------------------------ 分片与索引

    private static String shardOf(String hash) {
        return hash.substring(0, 2);
    }

    private Path shardDir(String shard) {
        return blocksRoot.resolve(shard);
    }

    /** 加载（并缓存）一个分片的索引；分片还没有就给出一个空索引。 */
    private Shard loadShard(String shard) {
        Shard cached = shards.get(shard);
        if (cached != null) {
            return cached;
        }
        Shard loaded = readShard(shardDir(shard));
        shards.put(shard, loaded);
        return loaded;
    }

    private static Shard readShard(Path shardDir) {
        Path indexFile = shardDir.resolve(INDEX_FILE);
        String packName = PACK_FILE;
        Map<String, long[]> entries = new LinkedHashMap<>();
        if (Files.isRegularFile(indexFile)) {
            try {
                for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.charAt(0) == '#') {
                        if (line.startsWith(INDEX_HEADER)) {
                            packName = line.substring(INDEX_HEADER.length()).trim();
                        }
                        continue;
                    }
                    int firstSpace = line.indexOf(' ');
                    int secondSpace = firstSpace < 0 ? -1 : line.indexOf(' ', firstSpace + 1);
                    if (firstSpace < 0 || secondSpace < 0) {
                        continue;
                    }
                    try {
                        entries.put(line.substring(0, firstSpace), new long[]{
                                Long.parseLong(line.substring(firstSpace + 1, secondSpace)),
                                Long.parseLong(line.substring(secondSpace + 1))});
                    } catch (NumberFormatException ignored) {
                        // 单行损坏不影响其余记录
                    }
                }
            } catch (IOException e) {
                return new Shard(PACK_FILE, new LinkedHashMap<>());
            }
        }
        return new Shard(packName, entries);
    }

    // ------------------------------------------------------------------ 垃圾回收

    /**
     * 标记-清除：删掉没有任何块映射引用的块。
     *
     * <p>调用方给的是**所有还活着的槽位**的映射合并出来的哈希集合。保守一点没坏处——漏掉一个存活
     * 哈希会让对应文件永远还原不回来，多留几个没人引用的块只是浪费一点空间。
     *
     * <p>只有确实存在死块的分片才会被重写；每个分片一次原子替换（替换 index 那一份文件）。
     */
    public static int collectGarbage(Path blocksRoot, Collection<String> liveHashes) throws IOException {
        if (!Files.isDirectory(blocksRoot)) {
            return 0;
        }
        Set<String> live = new HashSet<>(liveHashes);
        int removed = 0;
        try (DirectoryStream<Path> shardDirs = Files.newDirectoryStream(blocksRoot)) {
            for (Path shardDir : shardDirs) {
                if (!Files.isDirectory(shardDir)) {
                    continue;
                }
                Shard shard = readShard(shardDir);
                List<String> keep = new ArrayList<>();
                for (String hash : shard.entries.keySet()) {
                    if (live.contains(hash)) {
                        keep.add(hash);
                    } else {
                        removed++;
                    }
                }
                if (keep.size() == shard.entries.size()) {
                    continue;
                }
                rewriteShard(shardDir, shard, keep);
            }
        }
        return removed;
    }

    /** 重写一个分片：把存活块搬进新 pack，最后原子替换 index（index 里写着新 pack 的名字）。 */
    private static void rewriteShard(Path shardDir, Shard shard, List<String> keep) throws IOException {
        String newPackName = PACK_FILE + System.nanoTime();
        Path newPack = shardDir.resolve(newPackName);
        StringBuilder lines = new StringBuilder(INDEX_HEADER).append(newPackName).append('\n');
        long offset = 0L;
        try (FileChannel in = FileChannel.open(shardDir.resolve(shard.packName), StandardOpenOption.READ);
             OutputStream out = Files.newOutputStream(newPack)) {
            for (String hash : keep) {
                long[] location = shard.entries.get(hash);
                int length = (int) location[1];
                ByteBuffer buffer = ByteBuffer.allocate(length);
                int read = 0;
                while (read < length) {
                    int count = in.read(buffer, location[0] + read);
                    if (count < 0) {
                        throw new EOFException("block " + hash + " is truncated");
                    }
                    read += count;
                }
                out.write(buffer.array(), 0, length);
                lines.append(hash).append(' ').append(offset).append(' ').append(length).append('\n');
                offset += length;
            }
        }
        Path newIndex = shardDir.resolve(INDEX_FILE + TEMP_SUFFIX);
        Files.write(newIndex, lines.toString().getBytes(StandardCharsets.UTF_8));
        // 一次原子替换就完成了切换：index 里记着它配的是哪个 pack
        moveAtomic(newIndex, shardDir.resolve(INDEX_FILE));
        // 旧的 pack 现在没人引用了，顺手删掉（删不掉也只是占点空间，下次压缩再试）
        try {
            Files.deleteIfExists(shardDir.resolve(shard.packName));
        } catch (IOException ignored) {
            // 留着即可
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(HASH_BYTES * 2);
        for (int i = 0; i < HASH_BYTES; i++) {
            builder.append(HEX[(bytes[i] >> 4) & 0xF]).append(HEX[bytes[i] & 0xF]);
        }
        return builder.toString();
    }

    /** 读满缓冲区或读到文件末尾；返回实际读到的字节数。 */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static void moveAtomic(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
