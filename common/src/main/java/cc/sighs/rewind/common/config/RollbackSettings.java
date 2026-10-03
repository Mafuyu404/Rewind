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

    private static volatile boolean syncRecipeBook = DEFAULT_SYNC_RECIPE_BOOK;
    private static volatile boolean syncChunksNearPlayer = DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;

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

    /** 配置加载 / 重载时把值抄进来。 */
    public static void apply(boolean recipeBook, boolean chunksNearPlayer) {
        syncRecipeBook = recipeBook;
        syncChunksNearPlayer = chunksNearPlayer;
    }

    /** 改配方书开关（只改内存；落盘由平台配置层负责）。 */
    public static void setSyncRecipeBook(boolean value) {
        syncRecipeBook = value;
    }

    /** 改「只同步读视野内区块」开关（只改内存；落盘由平台配置层负责）。 */
    public static void setSyncChunksNearPlayer(boolean value) {
        syncChunksNearPlayer = value;
    }
}
