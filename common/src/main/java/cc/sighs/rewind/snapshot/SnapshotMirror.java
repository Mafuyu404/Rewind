package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 目录镜像：把 sourceRoot 的内容（含相对路径结构）镜像到 targetRoot。
 *
 * <p>两个方向共用同一套逻辑：
 * <ul>
 *   <li>{@link Direction#TO_SNAPSHOT} 建/覆盖存档点：源是活动存档，用上次的清单跳过没动过的文件；
 *       region / entities / poi 的 .mca 走 {@link SnapshotBlockStore} 按 4 KiB 块存，只写真正变了的扇区。</li>
 *   <li>{@link Direction#TO_WORLD} 回溯：源是槽位负载，用建点时记录的清单跳过「活动存档里还是原样」的文件，
 *       也就是只回拷建点之后真正被改写过的文件；块编码的文件从块存储重建。</li>
 * </ul>
 *
 * <p>两个方向都会删除目标里多出来的文件，并清理空目录；{@link SnapshotLayout#isExcluded(String)}
 * 命中的路径两个方向都不碰。
 *
 * <p>回溯方向的「源」不是齐全的目录树——块编码的 .mca 在槽位里没有实体文件，所以文件列表要把
 * 目录、清单、块映射三者并起来（见 {@code sourceFiles}）。少一个都会漏还原或者反过来把世界里的
 * 文件当多余的删掉。
 */
public final class SnapshotMirror {
    /** 镜像方向。 */
    public enum Direction {
        /** 活动存档 → 槽位：建点/覆盖。 */
        TO_SNAPSHOT,
        /** 槽位 → 活动存档：回溯。 */
        TO_WORLD
    }

    /** 进度回调，在拷贝线程上被调用。 */
    public interface Progress {
        void onFile(int index, int total, String relativePath);
    }

    /**
     * 一次镜像用到的块存储上下文；不启用块存储时整个传 null（就是老的全整文件行为）。
     *
     * <p>{@code store} 是调用方开的会话，本类不负责关闭它。
     */
    public static final class Blocks {
        /** 块存储会话。 */
        public final SnapshotBlockStore store;
        /** 已有的块映射：建点方向是本槽位上一次的映射，回溯方向是本槽位的映射。 */
        public final SnapshotBlocks existing;
        /** 建点方向收集新映射的地方；回溯方向传 null。 */
        public final SnapshotBlocks written;

        public Blocks(SnapshotBlockStore store, SnapshotBlocks existing, SnapshotBlocks written) {
            this.store = store;
            this.existing = existing;
            this.written = written;
        }
    }

    public static final class Result {
        public final SnapshotManifest manifest = SnapshotManifest.empty();
        public final List<String> files = new ArrayList<>();
        /** 目标位置被写入的文件数（整文件拷贝 / 编码进块存储 / 从块存储重建）。 */
        public int copied;
        /** 其中按 4 KiB 块编码的（建点方向）。 */
        public int encoded;
        /** 其中从块存储重建的（回溯方向）。 */
        public int decoded;
        /** 块序列沿用上次的、一个字节都没读也没写的文件数。 */
        public int reused;
        /** 完全没动的文件数。 */
        public int skipped;
        public int deleted;
        /** 真正写盘的字节数——块存储里已经存在的块不计入，那正是省钱的地方。 */
        public long copiedBytes;
        /** 快照里所有文件的总字节数（用于展示占用）。 */
        public long totalBytes;

        public String summary() {
            StringBuilder builder = new StringBuilder("copied=").append(copied);
            if (encoded > 0) {
                builder.append("(encoded=").append(encoded).append(')');
            }
            if (decoded > 0) {
                builder.append("(decoded=").append(decoded).append(')');
            }
            if (reused > 0) {
                builder.append(" reused=").append(reused);
            }
            return builder.append(" skipped=").append(skipped)
                    .append(" deleted=").append(deleted)
                    .append(" bytes=").append(copiedBytes)
                    .append(" files=").append(files.size())
                    .toString();
        }
    }

    /** {@link #copyAtomically} 用的临时文件后缀；{@link SnapshotLayout#isExcluded(String)} 会把它排除掉。 */
    public static final String TEMP_SUFFIX = ".rewind-tmp";

    private SnapshotMirror() {
    }

    /**
     * <p>两个方向共用一套镜像逻辑，区别只在「怎么判断一个文件已经就位」：
     * {@link Direction#TO_SNAPSHOT} 用上次的清单比对源文件（活动存档）是否没变，
     * {@link Direction#TO_WORLD} 用建点时的清单比对目标文件（活动存档）是否还是快照内容。
     *
     * @param reference 建点方向：上次该槽位的清单；回溯方向：该槽位建点时记录的清单。null 表示全量拷贝
     */
    public static Result mirror(Path sourceRoot, Path targetRoot, Direction direction, SnapshotManifest reference,
            Progress progress) throws IOException {
        return mirror(sourceRoot, targetRoot, direction, reference, progress, null);
    }

    /**
     * 同上，另可给出一组块存储上下文：region / entities / poi 的 .mca 按 4 KiB 块存取，
     * 只为真正变了的扇区付字节。
     *
     * @param blocks 块存储上下文；null 表示全部按整文件处理
     */
    public static Result mirror(Path sourceRoot, Path targetRoot, Direction direction, SnapshotManifest reference,
            Progress progress, Blocks blocks) throws IOException {
        Result result = new Result();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("source directory does not exist: " + sourceRoot);
        }

        List<String> sourceFiles = sourceFiles(sourceRoot, direction, reference, blocks);
        Collections.sort(sourceFiles);
        Set<String> wanted = new LinkedHashSet<>(sourceFiles);
        int total = sourceFiles.size();
        int index = 0;

        for (String relative : sourceFiles) {
            index++;
            if (progress != null) {
                progress.onFile(index, total, relative);
            }
            Path source = sourceRoot.resolve(relative);
            SnapshotManifest.Entry recorded = reference == null ? null : reference.get(relative);
            SnapshotBlocks.Entry mapped = blocks == null || blocks.existing == null
                    ? null
                    : blocks.existing.get(relative);
            boolean encoded = isEncoded(relative, direction, mapped, blocks);

            long size;
            long modified;
            if (Files.isRegularFile(source)) {
                size = Files.size(source);
                modified = Files.getLastModifiedTime(source).toMillis();
            } else if (encoded && mapped != null) {
                // 块编码、槽位里没有实体文件：大小来自映射，mtime 只可能来自清单
                size = mapped.size;
                modified = recorded == null ? 0L : recorded.modifiedMillis;
            } else if (recorded != null) {
                size = recorded.size;
                modified = recorded.modifiedMillis;
            } else {
                // 既没有源文件也没有任何记录：留着不动
                continue;
            }

            result.manifest.put(relative, size, modified);
            result.files.add(relative);
            result.totalBytes += size;

            Path target = targetRoot.resolve(relative);
            if (isAlreadyInPlace(direction, relative, target, size, modified, reference, recorded)) {
                result.skipped++;
                continue;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (encoded) {
                storeBlockEncoded(relative, source, target, size, modified, mapped, recorded, blocks, direction, result);
                continue;
            }

            copyAtomically(source, target);
            result.copied++;
            result.copiedBytes += size;
            if (direction == Direction.TO_WORLD) {
                restoreModifiedTime(target, recorded);
            }
        }

        for (String relative : listFiles(targetRoot)) {
            if (wanted.contains(relative)) {
                continue;
            }
            if (Files.deleteIfExists(targetRoot.resolve(relative))) {
                result.deleted++;
            }
        }

        pruneEmptyDirectories(targetRoot);
        return result;
    }

    /** 块编码文件的两个方向：建点方向切块入库，回溯方向从块存储重建。 */
    private static void storeBlockEncoded(String relative, Path source, Path target, long size, long modified,
            SnapshotBlocks.Entry mapped, SnapshotManifest.Entry recorded, Blocks blocks, Direction direction,
            Result result) throws IOException {
        if (direction == Direction.TO_WORLD) {
            blocks.store.decode(mapped.hashes, mapped.size, target);
            restoreModifiedTime(target, recorded);
            result.copied++;
            result.copiedBytes += size;
            result.decoded++;
            return;
        }

        // 建点方向
        List<String> hashes;
        if (mapped != null && mapped.size == size
                && recorded != null && recorded.modifiedMillis == modified) {
            // 自上次建点以来这个文件没变：直接沿用上次的块序列，一个字节都不用读
            hashes = mapped.hashes;
            result.reused++;
        } else {
            SnapshotBlockStore.Stored stored = blocks.store.encode(source);
            hashes = stored.hashes;
            result.copied++;
            result.copiedBytes += stored.newBytes;
            result.encoded++;
        }
        if (blocks.written != null) {
            blocks.written.put(relative, size, hashes);
        }
        // 上一次留下的整文件（老格式的槽位）必须清掉，否则它会伪装成权威内容
        Files.deleteIfExists(target);
    }

    /** 这个文件这次要不要按块存取。 */
    private static boolean isEncoded(String relative, Direction direction, SnapshotBlocks.Entry mapped, Blocks blocks) {
        if (blocks == null || blocks.store == null) {
            return false;
        }
        if (direction == Direction.TO_WORLD) {
            // 老槽位在映射里没有条目、槽位里是整文件，照旧整文件回拷
            return mapped != null;
        }
        return SnapshotBlockStore.isEncoded(relative);
    }

    /**
     * 这次镜像要处理哪些文件。
     *
     * <p>建点方向的源就是活动存档目录，直接遍历即可。回溯方向不行：块编码的 .mca 在槽位里没有实体
     * 文件，遍历会漏掉它们——而漏掉它们的后果是「不还原 + 把世界里的 .mca 当多余文件删掉」。所以
     * 回溯方向取「目录 ∪ 清单 ∪ 块映射」的并集。
     */
    private static List<String> sourceFiles(Path sourceRoot, Direction direction, SnapshotManifest reference,
            Blocks blocks) throws IOException {
        if (direction != Direction.TO_WORLD) {
            return listFiles(sourceRoot);
        }
        Set<String> files = new LinkedHashSet<>(listFiles(sourceRoot));
        if (reference != null) {
            files.addAll(reference.paths());
        }
        if (blocks != null && blocks.existing != null) {
            files.addAll(blocks.existing.paths());
        }
        return new ArrayList<>(files);
    }

    /**
     * 临时文件 + 原子替换地拷贝一个文件，绝不原地改写目标。
     *
     * <p>这样中途失败或进程被杀都不会在槽位里留下半个文件（槽位里留半个文件比没有更糟：
     * 它有正确的大小，看起来像是好的）。
     */
    private static void copyAtomically(Path source, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName().toString() + TEMP_SUFFIX);
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 回溯时把活动文件的 mtime 拨回建点时的值：这样「自建点后有没有被改写」下次还能一眼看出来，
     * 连续回溯也不再需要重复拷贝。
     */
    private static void restoreModifiedTime(Path target, SnapshotManifest.Entry recorded) {
        if (recorded == null) {
            return;
        }
        try {
            Files.setLastModifiedTime(target, FileTime.fromMillis(recorded.modifiedMillis));
        } catch (IOException ignored) {
            // 时间戳设不上不影响内容正确性
        }
    }

    private static boolean isAlreadyInPlace(Direction direction, String relative, Path target,
            long sourceSize, long sourceModified, SnapshotManifest reference, SnapshotManifest.Entry recorded)
            throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        if (direction == Direction.TO_SNAPSHOT) {
            // 槽位里已经放了同样大小/同样 mtime 的文件就够了（清单记的就是源文件的 size/mtime）
            return reference != null && reference.matches(relative, sourceSize, sourceModified)
                    && Files.size(target) == sourceSize;
        }
        // 回溯：活动存档里这个文件如果还等于建点时的状态，就不用动它
        return recorded != null
                && Files.size(target) == recorded.size
                && Files.getLastModifiedTime(target).toMillis() == recorded.modifiedMillis;
    }

    /** 列出 root 下所有纳入快照的文件（相对路径，{@code /} 分隔，已应用排除规则）。 */
    public static List<String> listFiles(Path root) throws IOException {
        List<String> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                return SnapshotLayout.isExcluded(SnapshotLayout.relativize(root, dir))
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = SnapshotLayout.relativize(root, file);
                if (!SnapshotLayout.isExcluded(relative)) {
                    files.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static void pruneEmptyDirectories(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (SnapshotLayout.isExcluded(SnapshotLayout.relativize(root, dir))) {
                    return FileVisitResult.CONTINUE;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    if (!stream.iterator().hasNext()) {
                        Files.deleteIfExists(dir);
                    }
                } catch (IOException ignored) {
                    // 目录非空或已被占用：留着即可
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
