package cc.sighs.rewind.common.config;

/**
 * 原地回滚的行为开关（服务端侧）。默认值、语义在这里，值也存在这里；各 target 的配置类只负责
 * 把它读写进自己的配置文件（NeoForge 的 {@code ModConfigSpec} / Fabric 的 properties）。
 */
public final class RollbackSettings {
    /**
     * 回溯时要不要处理玩家的「配方书」。
     *
     * <p>默认 {@code false}：**完全不碰配方书**——{@code player.load} 之前会把 NBT 里的
     * {@code recipeBook} 摘掉（{@code ServerRecipeBook.fromNbt} 要按名字逐条查配方表，大整合包里
     * 这一下可能几百毫秒），也不给客户端重发那份包；服务端与客户端两边都停在回溯前的状态。
     *
     * <p>开着才走原来那套：{@code player.load} 把配方书读回来，再用
     * {@code sendInitialRecipeBook} 把整份重发给客户端。
     */
    public static final boolean DEFAULT_SYNC_RECIPE_BOOK = false;

    /**
     * 回溯时要不要「只同步读回玩家视野内的受影响区块」。
     *
     * <p>默认 {@code true}：不在任何玩家视野（服务端视距 + 1 个区块）里的受影响区块**不在这里同步读**，
     * 交给原版流水线按还原后的 ticket 异步读回来。受影响区块的重载实测约 1ms/区块、与「建点之后改了
     * 多少」成正比，视野外那部分同步等它纯属浪费——玩家看不到，晚几 tick 回来也无所谓。
     *
     * <p>关掉则回到老行为：所有受影响区块都在回滚这一帧里同步读回来（回溯本身更慢，但结束后世界立即完整）。
     */
    public static final boolean DEFAULT_SYNC_CHUNKS_NEAR_PLAYER = true;

    /**
     * 读档冷却时长（秒）：一次成功回溯之后，要等这么久才能再读一次档。默认 {@code 0} = 不冷却。
     *
     * <p>冷却只挡「新的回溯」——建点（F7 / 界面上的覆盖）不受影响。起点是**回溯成功那一刻**，
     * 记在存档点索引里（{@code SnapshotIndex.lastRollbackAt}），所以退出游戏重进也绕不过去；
     * 换个存档目录则各算各的。
     */
    public static final int DEFAULT_COOLDOWN_SECONDS = 0;

    /** 读档冷却的可调范围（秒）；{@link #MIN_COOLDOWN_SECONDS} = 0 就是关闭冷却。 */
    public static final int MIN_COOLDOWN_SECONDS = 0;
    public static final int MAX_COOLDOWN_SECONDS = 3600;

    /**
     * 死亡后要不要自动回溯到「时间线上最近的那个节点」——时间线的头，也就是世界当前站着的那个存档点
     * （{@code RewindApi.currentSlot}）。默认 {@code false}：死亡保持原版行为。
     *
     * <p>开着时由客户端在自己死亡的那一下触发一次回溯；时间树上那个槽位会标红提示。
     */
    public static final boolean DEFAULT_ROLLBACK_ON_DEATH = false;

    /**
     * 回溯要不要走「原地回滚」（世界不关、客户端不重登，直接在活着的集成服务器里倒回去）。默认 {@code true}。
     *
     * <p>原地回滚快，但它**只回滚磁盘上的文件与内存里那部分被显式重建的状态**：进程里其它对象图
     * （第三方模组自己缓存的解码数据、静态单例……）不会跟着回到过去。所以只要装了这类模组，
     * 就可能出现「文件回滚了、游戏里看起来没有」。关掉它就退回「关世界 → 覆盖 → 重开」那条路：
     * 服务端对象全部重建、模组跟着世界重载走一遍，语义最干净，代价是慢（几秒 vs 几百毫秒）。
     *
     * <p>判定一个模组会不会中招见 {@code docs/AGENT.md} 的「原地回滚」一节。
     */
    public static final boolean DEFAULT_IN_PLACE_ROLLBACK = true;

    private static volatile boolean syncRecipeBook = DEFAULT_SYNC_RECIPE_BOOK;
    private static volatile boolean syncChunksNearPlayer = DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;
    private static volatile int cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
    private static volatile boolean rollbackOnDeath = DEFAULT_ROLLBACK_ON_DEATH;
    private static volatile boolean inPlaceRollback = DEFAULT_IN_PLACE_ROLLBACK;

    private RollbackSettings() {
    }

    /** 回溯时要不要处理配方书。 */
    public static boolean syncRecipeBook() {
        return syncRecipeBook;
    }

    /** 回溯时是不是只同步读回玩家视野内的受影响区块。 */
    public static boolean syncChunksNearPlayer() {
        return syncChunksNearPlayer;
    }

    /** 读档冷却时长（秒）；0 表示不冷却。 */
    public static int cooldownSeconds() {
        return cooldownSeconds;
    }

    /** 读档冷却时长（毫秒）。 */
    public static long cooldownMillis() {
        return cooldownSeconds() * 1000L;
    }

    /** 死亡后要不要自动回溯到时间线上最近的那个节点。 */
    public static boolean rollbackOnDeath() {
        return rollbackOnDeath;
    }

    /** 回溯是不是走原地回滚（关掉就退回「关世界 → 覆盖 → 重开」）。 */
    public static boolean inPlaceRollback() {
        return inPlaceRollback;
    }

    /** 配置加载 / 重载时把值抄进来。 */
    public static void apply(boolean recipeBook, boolean chunksNearPlayer, int cooldownSeconds, boolean rollbackOnDeath,
            boolean inPlaceRollback) {
        syncRecipeBook = recipeBook;
        syncChunksNearPlayer = chunksNearPlayer;
        setCooldownSeconds(cooldownSeconds);
        RollbackSettings.rollbackOnDeath = rollbackOnDeath;
        RollbackSettings.inPlaceRollback = inPlaceRollback;
    }

    /** 改配方书开关（只改内存；落盘由平台配置层负责）。 */
    public static void setSyncRecipeBook(boolean value) {
        syncRecipeBook = value;
    }

    /** 改「只同步读视野内区块」开关（只改内存；落盘由平台配置层负责）。 */
    public static void setSyncChunksNearPlayer(boolean value) {
        syncChunksNearPlayer = value;
    }

    /** 改「死亡后自动回溯」开关（只改内存；落盘由平台配置层负责）。 */
    public static void setRollbackOnDeath(boolean value) {
        rollbackOnDeath = value;
    }

    /** 改「原地回滚」开关（只改内存；落盘由平台配置层负责）。 */
    public static void setInPlaceRollback(boolean value) {
        inPlaceRollback = value;
    }

    /**
     * 改读档冷却时长（只改内存；落盘由平台配置层负责）。
     * 值会被夹到 {@link #MIN_COOLDOWN_SECONDS} - {@link #MAX_COOLDOWN_SECONDS}。
     */
    public static void setCooldownSeconds(int value) {
        cooldownSeconds = clampCooldownSeconds(value);
    }

    /** 把冷却秒数夹到 {@link #MIN_COOLDOWN_SECONDS} - {@link #MAX_COOLDOWN_SECONDS}。 */
    public static int clampCooldownSeconds(int value) {
        if (value < MIN_COOLDOWN_SECONDS) {
            return MIN_COOLDOWN_SECONDS;
        }
        return Math.min(value, MAX_COOLDOWN_SECONDS);
    }
}
