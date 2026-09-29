package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个槽位的块映射：哪些文件按 4 KiB 块存取，以及每个文件的块哈希序列。
 *
 * <p>行格式（一行一个文件）：{@code <大小>\t<相对路径>\t<hash> <hash> …}。大小写在行里，
 * 是为了重建文件时不依赖别的记录——映射自己就是完整的。
 *
 * <p>读不到、格式不认识时退化成空映射。这不是错误：它只意味着「这个槽位用的是老的整文件格式」，
 * 老槽位照旧能还原。
 */
public final class SnapshotBlocks {
    /** 一个文件的块序列。 */
    public static final class Entry {
        /** 文件总大小（用来校验重建结果、裁剪最后一块）。 */
        public final long size;
        public final List<String> hashes;

        Entry(long size, List<String> hashes) {
            this.size = size;
            this.hashes = Collections.unmodifiableList(hashes);
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private SnapshotBlocks() {
    }

    public static SnapshotBlocks empty() {
        return new SnapshotBlocks();
    }

    /** 读取映射；文件不存在或读取失败时返回空映射。 */
    public static SnapshotBlocks load(Path file) {
        SnapshotBlocks blocks = new SnapshotBlocks();
        if (file == null || !Files.isRegularFile(file)) {
            return blocks;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return blocks;
        }
        for (String line : lines) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int firstTab = line.indexOf('\t');
            int secondTab = firstTab < 0 ? -1 : line.indexOf('\t', firstTab + 1);
            if (firstTab < 0 || secondTab < 0) {
                continue;
            }
            try {
                long size = Long.parseLong(line.substring(0, firstTab));
                String relative = SnapshotLayout.normalize(line.substring(firstTab + 1, secondTab));
                List<String> hashes = new ArrayList<>();
                for (String hash : line.substring(secondTab + 1).trim().split(" ")) {
                    if (!hash.isEmpty()) {
                        hashes.add(hash);
                    }
                }
                blocks.put(relative, size, hashes);
            } catch (NumberFormatException ignored) {
                // 单行损坏不影响其余记录
            }
        }
        return blocks;
    }

    /** 这个文件的块序列；没有这个文件时返回 null（表示它是普通文件）。 */
    public Entry get(String relativePath) {
        return entries.get(SnapshotLayout.normalize(relativePath));
    }

    public boolean contains(String relativePath) {
        return entries.containsKey(SnapshotLayout.normalize(relativePath));
    }

    public void put(String relativePath, long size, List<String> hashes) {
        entries.put(SnapshotLayout.normalize(relativePath), new Entry(size, new ArrayList<>(hashes)));
    }

    public void remove(String relativePath) {
        entries.remove(SnapshotLayout.normalize(relativePath));
    }

    /** 按块存取的文件列表。 */
    public Set<String> paths() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /** 所有被引用到的块哈希（给垃圾回收当存活集合用）。 */
    public List<String> allHashes() {
        List<String> hashes = new ArrayList<>();
        for (Entry entry : entries.values()) {
            hashes.addAll(entry.hashes);
        }
        return hashes;
    }

    public int size() {
        return entries.size();
    }

    public int blockCount() {
        int count = 0;
        for (Entry entry : entries.values()) {
            count += entry.hashes.size();
        }
        return count;
    }

    /** 先写临时文件再原子替换，避免写一半留下坏映射。 */
    public void save(Path file) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("# rewind block map 1\n");
        for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
            builder.append(mapEntry.getValue().size).append('\t').append(mapEntry.getKey()).append('\t');
            List<String> hashes = mapEntry.getValue().hashes;
            for (int i = 0; i < hashes.size(); i++) {
                if (i > 0) {
                    builder.append(' ');
                }
                builder.append(hashes.get(i));
            }
            builder.append('\n');
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(temp, builder.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
