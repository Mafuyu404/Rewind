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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 目录镜像：把 sourceRoot 的内容（含相对路径结构）镜像到 targetRoot。
 *
 * <p>两个方向共用同一套逻辑：
 * <ul>
 *   <li>{@link Direction#TO_SNAPSHOT} 建/覆盖存档点：源是活动存档，用上次的清单跳过没动过的文件。</li>
 *   <li>{@link Direction#TO_WORLD} 回溯：源是槽位负载，用建点时记录的清单跳过「活动存档里还是原样」的文件，
 *       也就是只回拷建点之后真正被改写过的文件。</li>
 * </ul>
 *
 * <p>两个方向都会删除目标里多出来的文件，并清理空目录；{@link SnapshotLayout#isExcluded(String)}
 * 命中的路径两个方向都不碰。
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

    public static final class Result {
        public final SnapshotManifest manifest = SnapshotManifest.empty();
        public final List<String> files = new ArrayList<>();
        public int copied;
        public int skipped;
        public int deleted;
        /** 本次真正拷贝的字节数。 */
        public long copiedBytes;
        /** 快照里所有文件的总字节数（用于展示占用）。 */
        public long totalBytes;

        public String summary() {
            return "copied=" + copied
                    + " skipped=" + skipped
                    + " deleted=" + deleted
                    + " bytes=" + copiedBytes
                    + " files=" + files.size();
        }
    }

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
        Result result = new Result();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("source directory does not exist: " + sourceRoot);
        }

        List<String> sourceFiles = listFiles(sourceRoot);
        Collections.sort(sourceFiles);
        Set<String> wanted = new HashSet<>(sourceFiles);
        int total = sourceFiles.size();
        int index = 0;

        for (String relative : sourceFiles) {
            index++;
            if (progress != null) {
                progress.onFile(index, total, relative);
            }
            Path source = sourceRoot.resolve(relative);
            long size = Files.size(source);
            long modified = Files.getLastModifiedTime(source).toMillis();
            result.manifest.put(relative, size, modified);
            result.files.add(relative);
            result.totalBytes += size;

            Path target = targetRoot.resolve(relative);
            SnapshotManifest.Entry recorded = reference == null ? null : reference.get(relative);
            if (isAlreadyInPlace(direction, relative, target, size, modified, reference, recorded)) {
                result.skipped++;
                continue;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            result.copied++;
            result.copiedBytes += size;

            // 回溯时把活动文件的 mtime 拨回建点时的值：这样「自建点后有没有被改写」下次还能一眼看出来，
            // 连续回溯也不再需要重复拷贝。
            if (direction == Direction.TO_WORLD && recorded != null) {
                try {
                    Files.setLastModifiedTime(target, FileTime.fromMillis(recorded.modifiedMillis));
                } catch (IOException ignored) {
                    // 时间戳设不上不影响内容正确性
                }
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
