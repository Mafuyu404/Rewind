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
 *
 * <p>**{@code size} 为 {@link #UNTOUCHED_SIZE} 的行是「没动它」的记录**：建点时这个文件动不了
 * （典型：被别的进程锁着，Windows 上连读都读不动），快照里没有它的内容，那一条只是账。回溯方向
 * 对它既不能回拷（源里根本没有内容）、也不能删（那属于「快照里没有的多余文件」，但世界里的那一份
 * 是好的）。见 {@link #putUntouched(String)}。
 */
public final class SnapshotManifest {
    /** 「建点时没动它、快照里没有它的内容」的哨兵值；正常的 size 不可能是负数。 */
    public static final long UNTOUCHED_SIZE = -1L;

    public static final class Entry {
        public final long size;
        public final long modifiedMillis;

        Entry(long size, long modifiedMillis) {
            this.size = size;
            this.modifiedMillis = modifiedMillis;
        }

        /** 建点时没能把这个文件收进快照（读不动 / 写失败），两个方向都别动它。 */
        public boolean untouched() {
            return size == UNTOUCHED_SIZE;
        }

        /** 快照里有它的内容、能按 {@link #size} / {@link #modifiedMillis} 比对。 */
        public boolean hasContent() {
            return size != UNTOUCHED_SIZE;
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

    /**
     * 记下「建点时这个文件动不了、快照里没有它的内容」。
     *
     * <p>它同时是回溯方向的指令：别回拷（没内容可拷），也别把它当多余文件删掉。
     */
    public void putUntouched(String relativePath) {
        put(relativePath, UNTOUCHED_SIZE, UNTOUCHED_SIZE);
    }

    public Entry get(String relativePath) {
        return entries.get(SnapshotLayout.normalize(relativePath));
    }

    /** 相对路径存在、快照里有它的内容，且 size 与 mtime 都与记录一致时返回 true。 */
    public boolean matches(String relativePath, long size, long modifiedMillis) {
        Entry entry = get(relativePath);
        return entry != null && entry.hasContent() && entry.size == size && entry.modifiedMillis == modifiedMillis;
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
