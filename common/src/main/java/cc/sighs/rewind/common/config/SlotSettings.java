package cc.sighs.rewind.common.config;

/**
 * 槽位布局设置（COMMON 配置，两边都要有）。
 *
 * <p>「自动 / 快速」两个特殊槽位是固定的，可调的是**手动槽位数量**——界面上那排编号卡片
 * （{@code s1}…{@code sN}）和 {@link cc.sighs.rewind.snapshot.SnapshotLayout#uiSlots()} 都跟着它走。
 *
 * <p>改这个值**不动磁盘上的任何东西**：调小只是不再展示/不再列出的那几张卡，已有的槽位目录与索引
 * 条目原样保留（用 {@code RewindApi} 按名字照样能读回来）；调大就是多出几个空槽位。
 */
public final class SlotSettings {
    /** 手动槽位数量默认值。 */
    public static final int DEFAULT_MANUAL_SLOT_COUNT = 8;
    /** 可调范围：至少 1 个，最多 16 个（再多界面上那排卡片就没法看了）。 */
    public static final int MIN_MANUAL_SLOT_COUNT = 1;
    public static final int MAX_MANUAL_SLOT_COUNT = 16;

    private static volatile int manualSlotCount = DEFAULT_MANUAL_SLOT_COUNT;

    private SlotSettings() {
    }

    /** 手动槽位数量（已夹到范围内）。 */
    public static int manualSlotCount() {
        return manualSlotCount;
    }

    /** 配置加载 / 重载时把值抄进来（会夹到范围内）。 */
    public static void apply(int value) {
        manualSlotCount = clamp(value);
    }

    /** 改值（只改内存；落盘由平台配置层负责）。 */
    public static void setManualSlotCount(int value) {
        manualSlotCount = clamp(value);
    }

    public static int clamp(int value) {
        return Math.max(MIN_MANUAL_SLOT_COUNT, Math.min(MAX_MANUAL_SLOT_COUNT, value));
    }
}
