package cc.sighs.rewind.common.spi;

import java.nio.file.Path;
import java.util.List;
import cc.sighs.rewind.snapshot.SnapshotInventory;

/**
 * SPI：平台（加载器 + Minecraft 版本）向 common 暴露的能力。
 *
 * <p>每个 target 实现一份，并在模组构造阶段用 {@link RewindPlatforms#install} 装进来。common 侧所有
 * 需要碰 Minecraft 的地方（世界根目录、线程判定、服务器状态、强制落盘、原地回滚）都只经过这个接口，
 * 因此 common 本身不引用任何 {@code net.minecraft.*} / 加载器类型。
 *
 * <p>参数里的 {@code server} 是该平台自己的服务器对象（例如 NeoForge 1.21.1 的
 * {@code MinecraftServer}，Fabric 1.20.1 的 {@code MinecraftServer}）；common 只把它当作不透明句柄
 * 原样回传，实现方负责转型。common 里没有可用的公共父类型，所以这里只能用 {@code Object}。
 */
public interface RewindPlatform {
    /** 模组版本号，只用于元数据展示；取不到时返回 {@code "unknown"}。 */
    String modVersion();

    /** 活动存档根目录（{@code level.dat} 所在目录）。 */
    Path worldRoot(Object server);

    /** 当前线程是不是这个服务器的服务端线程。 */
    boolean isSameThread(Object server);

    /** 是不是专用服务器。 */
    boolean isDedicatedServer(Object server);

    /** 局域网是不是已开放。 */
    boolean isPublished(Object server);

    /** 世界里有没有玩家。 */
    boolean hasPlayers(Object server);

    /** 强制把世界落盘到「可以安全拷贝」的状态，并采集展示用的元数据与背包快照。 */
    FlushOutcome flushForCheckpoint(Object server, String slot, String source);

    /** 这个平台有没有原地回滚引擎；没有时 {@code RewindApi.rollbackInPlace} 直接返回失败。 */
    boolean supportsInPlaceRollback();

    /**
     * 原地回滚：世界不关、客户端不重登，在活着的集成服务器里把世界倒回存档点。
     *
     * <p>调用方已经把回滚窗口打开（{@code RewindState.beginDiscard()}）并在收尾时关掉；
     * 实现只管把世界倒回去。
     */
    RollbackOutcome rollbackInPlace(Object server, Path worldRoot, String slot) throws Exception;
}
