package cc.sighs.rewind.snapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 存档点（快照）在存档目录内的固定布局与排除规则。
 *
 * <p>布局：{@code <存档目录>/rewind_snapshots/} 下，{@code index.properties} 记录槽位元数据，
 * 每个槽位一个子目录，槽位目录是存档目录的镜像（保留相对路径），还原时按相对路径覆盖回去。
 *
 * <p>本类只依赖 JDK，保持 Java 8 兼容、不含任何 Minecraft / loader 类型。
 */
public final class SnapshotLayout {
    /** 存档点根目录名，位于存档目录内。 */
    public static final String ROOT_DIR_NAME = "rewind_snapshots";
    /** 槽位索引文件名，位于存档点根目录内，不属于槽位负载。 */
    public static final String INDEX_FILE_NAME = "index.properties";
    /** 槽位文件清单后缀，用于增量快照。 */
    public static final String MANIFEST_SUFFIX = ".manifest";
    /** 槽位块映射文件后缀：按 4 KiB 块存储的文件每个一行（见 {@code SnapshotBlocks}）。 */
    public static final String BLOCK_MAP_SUFFIX = ".blocks";
    /** 共享块目录名，位于存档点根目录内：内容寻址的 4 KiB 块，所有槽位共用。 */
    public static final String BLOCKS_DIR_NAME = "blocks";
    /** 槽位背包快照后缀，位于存档点根目录内、与槽位目录同级（不参与镜像）。 */
    public static final String INVENTORY_SUFFIX = ".inventory";
    /** 默认槽位：F7 / F8 使用的滚动槽位。 */
    public static final String DEFAULT_SLOT = "quick";
    /** 快速槽位（= {@link #DEFAULT_SLOT}），界面上那张「快速」卡片。 */
    public static final String SLOT_QUICK = DEFAULT_SLOT;
    /**
     * 自动槽位，界面上那张「自动」卡片。
     *
     * <p>它和别的槽位没有区别，只是被 {@code AutoCheckpoint} 拿来承接「跟着原版自动保存建点」：
     * 原版每次自动保存完，这里会被写一个 {@code source=autosave} 的存档点（挂点见
     * {@code cc.sighs.mixin.AutoCheckpointMixins}），所以那张卡不用手动覆盖也会自己长出内容。
     * 玩家也可以手动覆盖它。
     */
    public static final String SLOT_AUTO = "auto";
    /** 手动槽位数量，对应界面上的 8 张编号卡片。 */
    public static final int MANUAL_SLOT_COUNT = 8;
    /** 槽位显示名的长度上限，与界面上的输入框一致。 */
    public static final int NAME_MAX_LENGTH = 20;

    /** 槽位正在写入（未完成）。 */
    public static final String STATUS_INCOMPLETE = "incomplete";
    /** 槽位可安全还原。 */
    public static final String STATUS_COMPLETE = "complete";

    private static final String LOCK_FILE_NAME = "session.lock";
    private static final String PLAYER_DATA_DIR = "playerdata";

    /** 根目录下 {@code level<随机数字>.dat}，level.dat 写入时的临时文件。 */
    private static final Pattern ROOT_LEVEL_TEMP = Pattern.compile("level\\d+\\.dat");
    /** {@code playerdata/<uuid>-<随机数字>.dat}，玩家数据写入时的临时文件。 */
    private static final Pattern PLAYER_DATA_TEMP = Pattern.compile(".+-\\d+\\.dat");
    /** region / entities / poi 目录下超大区块的临时文件 {@code tmp<数字>}。 */
    private static final Pattern REGION_TEMP = Pattern.compile("tmp\\d+");
    /** NeoForge 异步 SavedData 写入的临时文件 {@code *.neoforge-tmp}。 */
    private static final Pattern NEOFORGE_TEMP = Pattern.compile(".+\\.neoforge-tmp");
    /** Rewind 原子替换槽位文件时用的临时文件 {@code *.rewind-tmp}（见 {@code SnapshotMirror}）。 */
    private static final Pattern REWIND_TEMP = Pattern.compile(".+\\.rewind-tmp");

    private SnapshotLayout() {
    }

    public static Path snapshotRoot(Path worldRoot) {
        return worldRoot.resolve(ROOT_DIR_NAME);
    }

    public static Path slotDir(Path worldRoot, String slot) {
        return snapshotRoot(worldRoot).resolve(slot);
    }

    public static Path indexFile(Path worldRoot) {
        return snapshotRoot(worldRoot).resolve(INDEX_FILE_NAME);
    }

    public static Path manifestFile(Path worldRoot, String slot) {
        return snapshotRoot(worldRoot).resolve(slot + MANIFEST_SUFFIX);
    }

    /** 槽位的块映射文件（与槽位目录同级，不参与镜像，删槽位时要一起删）。 */
    public static Path blockMapFile(Path worldRoot, String slot) {
        return snapshotRoot(worldRoot).resolve(slot + BLOCK_MAP_SUFFIX);
    }

    /** 所有槽位共用的内容寻址块目录。 */
    public static Path blocksRoot(Path worldRoot) {
        return snapshotRoot(worldRoot).resolve(BLOCKS_DIR_NAME);
    }

    /** 槽位的背包快照文件（与槽位目录同级，不参与镜像，删槽位时要一起删）。 */
    public static Path inventoryFile(Path worldRoot, String slot) {
        return snapshotRoot(worldRoot).resolve(slot + INVENTORY_SUFFIX);
    }

    /** 第 {@code index} 个手动槽位的 id（{@code index} 从 1 开始），形如 {@code s1}。 */
    public static String manualSlot(int index) {
        return "s" + index;
    }

    /** {@link #manualSlot(int)} 的逆运算；不是手动槽位时返回 0。 */
    public static int manualIndex(String slot) {
        if (slot == null || slot.length() < 2 || slot.charAt(0) != 's') {
            return 0;
        }
        try {
            return Integer.parseInt(slot.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 界面固定展示的槽位顺序：自动、快速，然后是 8 个手动槽位。 */
    public static List<String> uiSlots() {
        List<String> slots = new ArrayList<>();
        slots.add(SLOT_AUTO);
        slots.add(SLOT_QUICK);
        for (int i = 1; i <= MANUAL_SLOT_COUNT; i++) {
            slots.add(manualSlot(i));
        }
        return Collections.unmodifiableList(slots);
    }

    /** 是不是「自动 / 快速」这两张特殊卡片。 */
    public static boolean isSpecialSlot(String slot) {
        return SLOT_AUTO.equals(slot) || SLOT_QUICK.equals(slot);
    }

    /** 槽位名能不能安全地当目录名用。 */
    public static boolean isValidSlotName(String slot) {
        if (slot == null || slot.isEmpty() || slot.length() > 32) {
            return false;
        }
        for (int i = 0; i < slot.length(); i++) {
            char c = slot.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 玩家起的槽位名是否合法：去空白后 1 - {@value #NAME_MAX_LENGTH} 个字符。 */
    public static boolean isValidDisplayName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        return !trimmed.isEmpty() && trimmed.length() <= NAME_MAX_LENGTH;
    }

    /** 把绝对路径转成相对根的、以 {@code /} 分隔的字符串。 */
    public static String relativize(Path root, Path file) {
        return normalize(root.relativize(file).toString());
    }

    /** 统一分隔符并去掉 {@code ./}、开头结尾的斜杠。 */
    public static String normalize(String relativePath) {
        String path = relativePath.replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 判断一个相对路径是否应该被排除。
     *
     * <p>排除 {@code session.lock}、存档点目录自身，以及原版与 Rewind 自己写盘留下的临时文件；
     * 其余（含 .mcc 超大区块文件、datapacks/、advancements/、stats/ 等）都算存档状态。
     */
    public static boolean isExcluded(String relativePath) {
        String path = normalize(relativePath);
        if (path.isEmpty() || ".".equals(path)) {
            return false;
        }
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        String last = path.substring(path.lastIndexOf('/') + 1);

        if (first.equals(ROOT_DIR_NAME)) {
            return true;
        }
        if (path.equals(LOCK_FILE_NAME)) {
            return true;
        }
        if (NEOFORGE_TEMP.matcher(last).matches() || REWIND_TEMP.matcher(last).matches()) {
            return true;
        }
        if (REGION_TEMP.matcher(last).matches()) {
            return true;
        }
        if (last.contains("_corrupted_") || last.contains("_raw_")) {
            return true;
        }
        if (slash < 0 && ROOT_LEVEL_TEMP.matcher(last).matches()) {
            return true;
        }
        if (first.equals(PLAYER_DATA_DIR) && PLAYER_DATA_TEMP.matcher(last).matches()) {
            return true;
        }
        return false;
    }
}
