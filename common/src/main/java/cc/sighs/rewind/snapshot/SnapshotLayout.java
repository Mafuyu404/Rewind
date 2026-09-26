package cc.sighs.rewind.snapshot;

import java.nio.file.Path;
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
    /** 默认槽位：F7 / F8 使用的滚动槽位。 */
    public static final String DEFAULT_SLOT = "quick";

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
     * <p>排除 {@code session.lock}、存档点目录自身，以及原版写盘留下的临时文件；
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
        if (NEOFORGE_TEMP.matcher(last).matches()) {
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
