package cc.sighs.rewind;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import cc.sighs.RewindNeoForge121;
import cc.sighs.rewind.snapshot.SnapshotLayout;

/** 模组共用的常量、日志与全局状态。 */
public final class Rewind {
    public static final String MOD_ID = RewindNeoForge121.MOD_ID;
    public static final Logger LOGGER = LogUtils.getLogger();

    /** F7 / F8 使用的滚动槽位。 */
    public static final String SLOT = SnapshotLayout.DEFAULT_SLOT;

    /**
     * 回滚窗口标记：置位期间世界正准备被快照覆盖，任何「写下去也会被丢掉」的落盘都应该跳过
     * （由 {@code cc.sighs.mixin} 下的 Mixin 检查）。只在服务器线程/客户端线程的同一处设置与清除。
     */
    private static volatile boolean discarding;

    public static void beginDiscard() {
        discarding = true;
    }

    public static void endDiscard() {
        discarding = false;
    }

    public static boolean isDiscarding() {
        return discarding;
    }

    private Rewind() {
    }
}
