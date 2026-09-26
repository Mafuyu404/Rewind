package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上一次快照时每个文件的 size / mtime 记录，用于 F7 覆盖时只拷贝真正变化的文件。
 *
 * <p>文件行格式：{@code <size>\t<mtimeMillis>\t<相对路径>}。读不到或损坏时退化为空清单，
 * 也就是退化成全量拷贝，不会破坏正确性。
 */
public final class SnapshotManifest {
    public static final class Entry {
        public final long size;
        public final long modifiedMillis;

        Entry(long size, long modifiedMillis) {
            this.size = size;
            this.modifiedMillis = modifiedMillis;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private SnapshotManifest() {
    }

    public static SnapshotManifest empty() {
        return new SnapshotManifest();
    }

    /** 读取清单；文件不存在或读取失败时返回空清单。 */
    public static SnapshotManifest load(Path file) {
        SnapshotManifest manifest = new SnapshotManifest();
        if (file == null || !Files.isRegularFile(file)) {
            return manifest;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return manifest;
        }
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int firstTab = line.indexOf('\t');
            int secondTab = firstTab < 0 ? -1 : line.indexOf('\t', firstTab + 1);
            if (secondTab < 0) {
                continue;
            }
            try {
                long size = Long.parseLong(line.substring(0, firstTab));
                long modified = Long.parseLong(line.substring(firstTab + 1, secondTab));
                manifest.put(line.substring(secondTab + 1), size, modified);
            } catch (NumberFormatException ignored) {
                // 单行损坏不影响其余记录
            }
        }
        return manifest;
    }

    public void put(String relativePath, long size, long modifiedMillis) {
        entries.put(SnapshotLayout.normalize(relativePath), new Entry(size, modifiedMillis));
    }

    public Entry get(String relativePath) {
        return entries.get(SnapshotLayout.normalize(relativePath));
    }

    /** 相对路径存在且 size 与 mtime 都与记录一致时返回 true。 */
    public boolean matches(String relativePath, long size, long modifiedMillis) {
        Entry entry = get(relativePath);
        return entry != null && entry.size == size && entry.modifiedMillis == modifiedMillis;
    }

    public int size() {
        return entries.size();
    }

    public List<String> paths() {
        return new ArrayList<>(entries.keySet());
    }

    public void save(Path file) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("# rewind snapshot manifest\n");
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            builder.append(entry.getValue().size)
                    .append('\t')
                    .append(entry.getValue().modifiedMillis)
                    .append('\t')
                    .append(entry.getKey())
                    .append('\n');
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, builder.toString().getBytes(StandardCharsets.UTF_8));
    }
}
