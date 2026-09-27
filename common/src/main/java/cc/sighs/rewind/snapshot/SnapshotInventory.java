package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个槽位的背包快照：非空栏位 + 该栏位的物品表达式。
 *
 * <p>物品表达式是原版 SNBT（{@code {id:"minecraft:diamond",count:12}}），由各 target 自己生成；
 * 本类只管按行存取，所以没有任何 Minecraft 类型，保持 Java 8 兼容。
 *
 * <p>文件格式（一行一条，TAB 分隔——表达式里可能出现空格，不能用空格当分隔符）：
 * <pre>
 * #rewind-inventory 1
 * 0&#9;minecraft:redstone
 * 3&#9;{id:"minecraft:iron_ingot",count:55}
 * </pre>
 * 第一列是栏位序号（与原版 {@code Inventory.getContainerSize()} 的序号一致），第二列是物品表达式。
 * 空栏位不写行，所以文件大小与「真正带了多少东西」成正比。
 */
public final class SnapshotInventory {
    private static final String HEADER = "#rewind-inventory 1";
    /** 原版玩家物品栏的栏位总数（36 主背包 + 4 护甲 + 1 副手）。 */
    public static final int SLOT_COUNT = 41;

    /** 一个非空栏位。 */
    public static final class Entry {
        public final int index;
        public final String item;

        public Entry(int index, String item) {
            this.index = index;
            this.item = item;
        }

        @Override
        public String toString() {
            return index + "\t" + item;
        }
    }

    private final List<Entry> entries;

    private SnapshotInventory(List<Entry> entries) {
        this.entries = entries;
    }

    public static SnapshotInventory empty() {
        return new SnapshotInventory(new ArrayList<>());
    }

    /** 把物品栏内容（栏位序号 → 物品表达式）装成一个快照。 */
    public static SnapshotInventory of(List<Entry> entries) {
        return new SnapshotInventory(new ArrayList<>(entries));
    }

    /**
     * 读取背包快照。
     *
     * <p>文件不存在、读不动、或者格式不认识时返回空快照——背包快照只是界面上的展示信息，
     * 坏了不该影响「这个槽位能不能还原」的判断（那个由索引里的 status 决定）。
     */
    public static SnapshotInventory load(Path file) {
        List<Entry> entries = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return new SnapshotInventory(entries);
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    continue;
                }
                int index;
                try {
                    index = Integer.parseInt(line.substring(0, tab).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (index < 0 || index >= SLOT_COUNT) {
                    continue;
                }
                entries.add(new Entry(index, line.substring(tab + 1)));
            }
        } catch (IOException e) {
            entries.clear();
        }
        return new SnapshotInventory(entries);
    }

    /** 先写临时文件再替换，避免写一半留下坏文件（与索引同样的处理）。 */
    public void save(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>(entries.size() + 1);
        lines.add(HEADER);
        for (Entry entry : entries) {
            lines.add(entry.toString());
        }
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }
}
