package cc.sighs.rewind.common.spi;

/**
 * 当前平台实现的持有者：每个 target 在模组构造阶段装一次。
 *
 * <p>没有装过时 {@link #get()} 返回 null，{@link #require()} 抛异常；调用方按「能不能优雅降级」二选一。
 */
public final class RewindPlatforms {
    private static volatile RewindPlatform platform;

    /** 由 target 的入口调用；重复安装以最后一次为准。 */
    public static void install(RewindPlatform value) {
        platform = value;
    }

    /** 当前平台；还没安装时为 null。 */
    public static RewindPlatform get() {
        return platform;
    }

    /** 当前平台；还没安装时抛 {@link IllegalStateException}。 */
    public static RewindPlatform require() {
        RewindPlatform current = platform;
        if (current == null) {
            throw new IllegalStateException("Rewind platform has not been installed");
        }
        return current;
    }

    private RewindPlatforms() {
    }
}
