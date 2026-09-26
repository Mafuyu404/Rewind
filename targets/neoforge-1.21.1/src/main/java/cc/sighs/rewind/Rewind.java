package cc.sighs.rewind;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import cc.sighs.RewindNeoForge121;
import cc.sighs.rewind.snapshot.SnapshotLayout;

/** 模组共用的常量与日志。 */
public final class Rewind {
    public static final String MOD_ID = RewindNeoForge121.MOD_ID;
    public static final Logger LOGGER = LogUtils.getLogger();

    /** F7 / F8 使用的滚动槽位。 */
    public static final String SLOT = SnapshotLayout.DEFAULT_SLOT;

    private Rewind() {
    }
}
