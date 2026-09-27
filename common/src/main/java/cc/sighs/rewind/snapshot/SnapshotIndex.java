package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/** 存档点索引：记录每个槽位的元数据。键格式为 {@code slot.<槽位>.<字段>}。 */
public final class SnapshotIndex {
    private static final String FORMAT_KEY = "format";
    private static final String FORMAT_VALUE = "1";

    private final Properties properties = new Properties();

    private SnapshotIndex() {
    }

    public static SnapshotIndex empty() {
        return new SnapshotIndex();
    }

    /** 读取索引；文件不存在时返回空索引。 */
    public static SnapshotIndex load(Path file) throws IOException {
        SnapshotIndex index = new SnapshotIndex();
        if (file == null || !Files.isRegularFile(file)) {
            return index;
        }
        try (InputStream in = Files.newInputStream(file)) {
            index.properties.load(in);
        }
        return index;
    }

    public List<String> slots() {
        List<String> slots = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("slot.")) {
                continue;
            }
            int separator = key.indexOf('.', "slot.".length());
            if (separator < 0) {
                continue;
            }
            String slot = key.substring("slot.".length(), separator);
            if (!slots.contains(slot)) {
                slots.add(slot);
            }
        }
        Collections.sort(slots);
        return slots;
    }

    /** 槽位不存在时返回 null。 */
    public SnapshotMeta get(String slot) {
        if (slot == null || !properties.containsKey(key(slot, "status"))) {
            return null;
        }
        SnapshotMeta meta = new SnapshotMeta();
        meta.slot = slot;
        meta.status = properties.getProperty(key(slot, "status"), SnapshotLayout.STATUS_INCOMPLETE);
        meta.savedAtMillis = readLong(slot, "savedAt", 0L);
        meta.worldName = properties.getProperty(key(slot, "worldName"), "");
        meta.minecraftVersion = properties.getProperty(key(slot, "minecraftVersion"), "");
        meta.modVersion = properties.getProperty(key(slot, "modVersion"), "");
        meta.source = properties.getProperty(key(slot, "source"), "");
        meta.displayName = properties.getProperty(key(slot, "displayName"), "");
        meta.biomeId = properties.getProperty(key(slot, "biomeId"), "");
        meta.playtimeTicks = readLong(slot, "playtimeTicks", 0L);
        meta.gameTime = readLong(slot, "gameTime", 0L);
        meta.playerX = readDouble(slot, "playerX", 0.0D);
        meta.playerY = readDouble(slot, "playerY", 0.0D);
        meta.playerZ = readDouble(slot, "playerZ", 0.0D);
        meta.fileCount = (int) readLong(slot, "fileCount", 0L);
        meta.totalBytes = readLong(slot, "totalBytes", 0L);
        return meta;
    }

    public void put(SnapshotMeta meta) {
        put(meta, meta.slot);
    }

    /** 以指定槽位名写入（用于「先标记 incomplete、完成后改写为 complete」）。 */
    public void put(SnapshotMeta meta, String slot) {
        properties.setProperty(FORMAT_KEY, FORMAT_VALUE);
        properties.setProperty(key(slot, "status"), meta.status);
        properties.setProperty(key(slot, "savedAt"), Long.toString(meta.savedAtMillis));
        properties.setProperty(key(slot, "worldName"), meta.worldName);
        properties.setProperty(key(slot, "minecraftVersion"), meta.minecraftVersion);
        properties.setProperty(key(slot, "modVersion"), meta.modVersion);
        properties.setProperty(key(slot, "source"), meta.source);
        properties.setProperty(key(slot, "displayName"), meta.displayName);
        properties.setProperty(key(slot, "biomeId"), meta.biomeId);
        properties.setProperty(key(slot, "playtimeTicks"), Long.toString(meta.playtimeTicks));
        properties.setProperty(key(slot, "gameTime"), Long.toString(meta.gameTime));
        properties.setProperty(key(slot, "playerX"), Double.toString(meta.playerX));
        properties.setProperty(key(slot, "playerY"), Double.toString(meta.playerY));
        properties.setProperty(key(slot, "playerZ"), Double.toString(meta.playerZ));
        properties.setProperty(key(slot, "fileCount"), Integer.toString(meta.fileCount));
        properties.setProperty(key(slot, "totalBytes"), Long.toString(meta.totalBytes));
    }

    /**
     * 删掉一个槽位的全部字段（只改内存，落盘由 {@link #save(Path)} 负责）。
     *
     * @return 槽位本来是否存在
     */
    public boolean remove(String slot) {
        String prefix = "slot." + slot + ".";
        boolean removed = false;
        for (String key : new ArrayList<>(properties.stringPropertyNames())) {
            if (key.startsWith(prefix)) {
                properties.remove(key);
                removed = true;
            }
        }
        return removed;
    }

    /** 先写临时文件再替换，避免写一半留下坏索引。 */
    public void save(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            properties.store(out, "Rewind snapshot index");
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String key(String slot, String field) {
        return "slot." + slot + "." + field;
    }

    private long readLong(String slot, String field, long fallback) {
        try {
            return Long.parseLong(properties.getProperty(key(slot, field), Long.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double readDouble(String slot, String field, double fallback) {
        try {
            return Double.parseDouble(properties.getProperty(key(slot, field), Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
