package cc.sighs.rewind.common.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import cc.sighs.rewind.common.RewindLog;
import cc.sighs.rewind.common.compat.SophisticatedCoreCompat;

/**
 * 回滚收尾：把**内存里**的存档数据拉回存档点。
 *
 * <p>为什么需要它：文件名被快照覆盖只是「磁盘」回到了过去，进程里那些还活着的存档数据实例（
 * {@code DimensionDataStorage} / {@code SavedDataStorage} 的缓存条目、挂在 {@code Level} 上的附件表……）
 * 并不会自己回去；而 {@code save()} 只遍历缓存，所以下一次自动保存就会把内存里那份写回磁盘，
 * 把刚还原的文件盖掉。做法是「作废没被强引用的缓存条目 → 让它们下次访问时从磁盘重读」，
 * 被长生命周期对象用字段钉住的那些再显式重建；模组自己缓存的解码结果则通知模组自己丢。
 *
 * <p>这一整套**骨架与版本无关**，四个 target 的差异只在 {@link Hook} 那四处：
 * 缓存在哪个类的哪个字段里（{@code rewind$cache()}）、怎么重建（各版本 API 不同）、
 * 存档数据文件怎么映射成 id（1.20.1/1.21.1 是 {@code data/<id>.dat}，26.1 是 {@code data/<ns>/<path>.dat}）、
 * 原版 id 白名单各有哪些。判据是「该版本的反编译源码里逐一核对过」。
 */
public final class SavedDataRollback {
    /** 各 target 提供的那几处——凡是与版本相关的都收在这里。 */
    public interface Hook {
        /** 作废掉所有「不该留」的缓存条目（被强引用的留着）；返回摘掉的条数，只用于日志。 */
        int purge();

        /** 重建被长生命周期对象强引用、又能从磁盘重读内容的那些（袭击 / 记分板 / 等级附件 / 天气……）。 */
        void rebuild();

        /** 存档数据文件（相对世界根）→ 存档数据 id；落盘布局按版本不同，见类注释。 */
        String idOf(String mirroredFile);

        /** 这个版本的原版 id 白名单（含 {@code map_} / {@code maps/} 这类前缀规则）。 */
        boolean isVanillaId(String id);
    }

    private SavedDataRollback() {
    }

    /**
     * 收尾顺序固定：**作废 → 重建 → 记日志 → 通知模组侧丢缓存**。顺序不能换——重建依赖缓存已经作废
     * （否则 {@code computeIfAbsent} 直接命中旧实例、反序列化器根本不会跑），日志要在动作之后，
     * 模组侧丢缓存要在世界状态已经回去了之后。
     */
    public static void restore(Hook hook, List<String> mirroredFiles) {
        hook.purge();
        hook.rebuild();
        report(hook, mirroredFiles);
        SophisticatedCoreCompat.clearCaches();
    }

    /** {@code data/*.dat} 或 {@code <维度>/data/...} 才算存档数据文件（两种布局都认）。 */
    public static boolean isSavedDataFile(String relative) {
        return relative.endsWith(".dat") && (relative.startsWith("data/") || relative.contains("/data/"));
    }

    /**
     * 1.20.1 / 1.21.1 的落盘布局：{@code data/<id>.dat} 与 {@code <维度>/data/<id>.dat} 都映射成
     * 「文件名去掉 {@code .dat}」（26.1 是 {@code data/<ns>/<path>.dat} → {@code <ns>:<path>}，
     * 跟它的 {@code SavedDataType} 一样自带一份，不走这里）。
     */
    public static String flatIdOf(String relativeFile) {
        String name = relativeFile.substring(relativeFile.lastIndexOf('/') + 1);
        return name.substring(0, name.length() - ".dat".length());
    }

    /** {@code id} 命中「精确名单 ∪ 前缀」——白名单用。 */
    public static boolean matchesPrefixes(String id, Set<String> exact, Collection<String> prefixes) {
        if (exact.contains(id)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (id.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** {@code id} 命中「精确名单 ∪ 后缀」——保护名单用（老存档的结构索引是 {@code *_index}）。 */
    public static boolean matchesSuffixes(String id, Set<String> exact, Collection<String> suffixes) {
        if (exact.contains(id)) {
            return true;
        }
        for (String suffix : suffixes) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把这次回滚覆盖到的 {@code <维度>/data/*.dat} 打进日志，遇到模组数据再额外提示一句。
     *
     * <p>这是「原地回滚少了什么」唯一能被看见的线索：模组把世界状态写在这些文件里时，磁盘回滚了、
     * 内存不一定回滚，游戏里就可能出现「文件回去了、数据没回去」。真遇到了就关掉
     * {@code rollback.inPlaceRollback} 走完整重开那条路。
     */
    public static void report(Hook hook, List<String> mirroredFiles) {
        List<String> dataFiles = new ArrayList<>();
        Set<String> modIds = new TreeSet<>();
        for (String file : mirroredFiles) {
            if (!isSavedDataFile(file)) {
                continue;
            }
            dataFiles.add(file);
            String id = hook.idOf(file);
            if (!hook.isVanillaId(id)) {
                modIds.add(id);
            }
        }
        if (dataFiles.isEmpty()) {
            return;
        }
        RewindLog.LOGGER.info("Rewind: saved data covered by this rollback: {}", String.join(", ", dataFiles));
        if (!modIds.isEmpty()) {
            RewindLog.LOGGER.warn("Rewind: rollback also covers non-vanilla saved data ({}); if a mod's data looks like it "
                            + "did not roll back, disable rollback.inPlaceRollback and retry",
                    String.join(", ", modIds));
        }
    }
}
