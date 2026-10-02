package cc.sighs.rewind;

import org.slf4j.Logger;
import cc.sighs.rewind.common.RewindCommon;
import cc.sighs.rewind.common.RewindLog;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.snapshot.SnapshotLayout;

/**
 * 本 target 的共用常量与全局状态门面。
 *
 * <p>真正的实现都在 common：日志走 {@link RewindLog}，回滚窗口与区块重建名单在 {@link RewindState}，
 * 槽位常量在 {@link SnapshotLayout}。这里只保留一个 target 侧的短名字，免得几十处调用点都写全限定名；
 * 它没有任何自己的状态，也就不会出现「不同 target 各有一份」的问题。
 */
public final class Rewind {
    public static final String MOD_ID = RewindCommon.MOD_ID;
    public static final Logger LOGGER = RewindLog.LOGGER;

    /** F7 / F8 使用的滚动槽位。 */
    public static final String SLOT = SnapshotLayout.DEFAULT_SLOT;

    /** 回滚窗口标记：见 {@link RewindState#beginDiscard()}。 */
    public static void beginDiscard() {
        RewindState.beginDiscard();
    }

    public static void endDiscard() {
        RewindState.endDiscard();
    }

    public static boolean isDiscarding() {
        return RewindState.isDiscarding();
    }

    /** 登记「本轮自己接管的区块」名单：见 {@link RewindState#beginUnloadGuard}。 */
    public static void beginUnloadGuard(Object chunkMap, long[] positions) {
        RewindState.beginUnloadGuard(chunkMap, positions);
    }

    public static void clearUnloadGuards() {
        RewindState.clearUnloadGuards();
    }

    public static boolean isRecreationBlocked(Object chunkMap, long pos) {
        return RewindState.isRecreationBlocked(chunkMap, pos);
    }

    private Rewind() {
    }
}
