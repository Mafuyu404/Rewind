package cc.sighs.rewind.common.compat;

import java.lang.reflect.Method;
import cc.sighs.rewind.common.RewindLog;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;

/**
 * 精妙核心（Sophisticated Core）的**可选**兼容：世界被回溯之后，丢掉它在内存里那份「解码后的」世界状态。
 *
 * <p>为什么要专门为它写一层：精妙系列把容器内容放在**世界级 {@code SavedData}** 里（键是物品上的一个 UUID），
 * 而 {@code StorageWrapperRepository} 又用一个静态 Guava 缓存把「已经解码好的 {@code IStorageWrapper} /
 * InventoryHandler」按 ItemStack 与 UUID 双键缓存十分钟。于是回溯只还原磁盘文件是不够的——
 * 缓存里那些 handler 还拿着回滚前的内容，界面与物品栏照样显示旧数据（详见 {@code docs/AGENT.md}
 * 的「原地回滚」与「已知边界」）。
 *
 * <p>这里是**纯反射、零依赖**：精妙核心不在场时 {@link #isLoaded()} 为 false，什么也不做；
 * 在场且那个缓存类存在时调它的 {@code StorageWrapperRepository.clearCache()}（public static，模组自己在世界重载时也这么清）。
 * 之所以不用 {@code compileOnly} 依赖，是因为四个 target 里只有一个装了它，反射是唯一一份代码能覆盖四个 target 的做法。
 *
 * <p><b>每个版本的实际情形（逐个 jar 核过）</b>：
 * <ul>
 *   <li>{@code neoforge-1.21.1}：Core 1.5.7.2381 里 {@code StorageWrapperRepository.clearCache()} 在 ✓，
 *       实测（探针 + 端到端数据包）能真的清掉。</li>
 *   <li>{@code neoforge-26.1}：Core 1.4.26.1688 里同样有这个方法（javap 核过），实测见 AGENT.md。</li>
 *   <li>{@code forge-1.20.1}：Core 1.5.6.2378 里**没有这个类**——那一版根本没有静态 wrapper 缓存
 *       （整包唯一的 Guava Cache 是 Create 兼容与配方缓存），所以这层适配在 1.20.1 上是**正确的空操作**，
 *       内容回滚由 {@code SavedDataRollback} 那套（世界级 {@code sophisticatedbackpacks.dat}）与 playerdata 负责。</li>
 *   <li>{@code fabric-1.20.1}：精妙背包/核心**没有 1.20.1 的 Fabric 构建**（Modrinth 上 1.20.1 只有
 *       forge/neoforge），所以这条适配在 Fabric 上永远不会命中；代码仍在，将来有移植版就能直接用
 *       （Fabric 只有 {@code modClassLoader} 那一步的差别，见 {@link RewindPlatform#modClassLoader(String)}）。</li>
 * </ul>
 *
 * <p><b>加载器要问平台要</b>（{@link RewindPlatform#modClassLoader(String)}）：NeoForge / Forge 的模组是 JPMS
 * 模块，各自那个加载器只看得到自己读得到的模块，拿 common 自己那个去 {@code Class.forName} 是看不见它的
 * （开发环境实测过）。
 */
public final class SophisticatedCoreCompat {
    /** 精妙核心的缓存仓库；它同时服务精妙背包、精妙存储等模组。 */
    private static final String REPOSITORY_CLASS =
            "net.p3pp3rf1y.sophisticatedcore.inventory.StorageWrapperRepository";
    /** 模组自己提供的清缓存入口。 */
    private static final String CLEAR_METHOD = "clearCache";
    /** 精妙核心的 modid，同时也是它的 JPMS 模块名。 */
    private static final String MOD_ID = "sophisticatedcore";

    /** 三态：null = 还没查过。查过之后不再重复 try/catch。 */
    private static volatile Boolean loaded;

    private SophisticatedCoreCompat() {
    }

    /** 精妙核心在不在场。第一次调用会尝试加载它的类，之后走缓存。 */
    public static boolean isLoaded() {
        Boolean cached = loaded;
        if (cached == null) {
            cached = resolve() != null;
            loaded = cached;
        }
        return cached;
    }

    /**
     * 清掉精妙核心的包装器缓存，让它在下次访问时从（已被快照覆盖的）世界数据里重新解码。
     *
     * <p>精妙核心不在场时静默返回 false；清成功写一条 INFO，失败写一条 WARN。
     *
     * @return 真的清了返回 true
     */
    public static boolean clearCaches() {
        Class<?> repository = resolve();
        if (repository == null) {
            return false;
        }
        try {
            Method clear = repository.getMethod(CLEAR_METHOD);
            clear.invoke(null);
            RewindLog.LOGGER.info("Rewind: cleared the Sophisticated Core wrapper caches after rollback");
            return true;
        } catch (Throwable t) {
            RewindLog.LOGGER.warn("Rewind: failed to clear the Sophisticated Core caches after rollback", t);
            return false;
        }
    }

    /**
     * 加载精妙核心的缓存类；不在场（或所有加载器都看不到它）时返回 null。
     */
    private static Class<?> resolve() {
        for (ClassLoader loader : candidateLoaders()) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(REPOSITORY_CLASS, false, loader);
            } catch (Throwable ignoredFailure) {
                // 换下一个加载器继续试
            }
        }
        return null;
    }

    /**
     * 依次尝试的类加载器。
     *
     * <p>顺序有讲究：**先问平台要「那个模组的加载器」**（{@link RewindPlatform#modClassLoader(String)}）。
     * NeoForge / Forge 的模组是 JPMS 模块，各自的加载器只看得到自己读得到的模块——开发环境实测：
     * 拿 common 自己那个 {@code ModuleClassLoader} 去 {@code Class.forName} 会
     * {@code ClassNotFoundException}，线程上下文加载器也不行，必须问到那个模块的加载器才看得见。
     *
     * <p>后面的几个只是兜底：Fabric 只有一个 KnotClassLoader，第一种就会命中；没有装过平台实现时
     * （例如单元测试）则靠上下文加载器 / 自己的加载器 / 系统加载器。
     */
    private static ClassLoader[] candidateLoaders() {
        ClassLoader modLoader = null;
        RewindPlatform platform = RewindPlatforms.get();
        if (platform != null) {
            try {
                modLoader = platform.modClassLoader(MOD_ID);
            } catch (Throwable ignoredFailure) {
                // 拿不到就走兜底
            }
        }
        return new ClassLoader[] {
                modLoader,
                Thread.currentThread().getContextClassLoader(),
                SophisticatedCoreCompat.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
    }
}
