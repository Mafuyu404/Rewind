package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 目录镜像：把 sourceRoot 的内容（含相对路径结构）镜像到 targetRoot。
 *
 * <p>两个方向都用这一个方法：
 * <ul>
 *   <li>F7 建/覆盖存档点：{@code mirror(存档目录, 槽位目录, 上次清单)}</li>
 *   <li>F8 回溯：{@code mirror(槽位目录, 存档目录, 空清单)}——回溯时永远全量拷贝，
 *       因为活动存档里的文件可能比快照新。</li>
 * </ul>
 *
 * <p>语义：拷贝新增/变化的文件（清单命中且目标文件大小一致时跳过），删除目标里多出来的文件，
 * 最后清理空目录。{@link SnapshotLayout#isExcluded(String)} 命中的路径在两个方向上都不碰。
 */
public final class SnapshotMirror {
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
     * @param previous 上次该槽位的清单，用于跳过没变化的文件；null 表示全量拷贝
     */
    public static Result mirror(Path sourceRoot, Path targetRoot, SnapshotManifest previous, Progress progress)
            throws IOException {
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
            if (previous != null && previous.matches(relative, size, modified)
                    && Files.isRegularFile(target) && Files.size(target) == size) {
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
