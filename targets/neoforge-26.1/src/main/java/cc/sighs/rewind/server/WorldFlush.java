package cc.sighs.rewind.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * 把运行中的单人世界强制落盘到「可以安全拷贝」的状态。必须在服务器线程上执行。
 *
 * <p>序列与依据（26.1 版）：
 * <ol>
 *   <li>{@code saveEverything(silent=true, flush=true, forced=true)}：玩家数据 / 各维度区块与实体 /
 *       level.dat / SavedData。26.1 的 flush 路径自己就把该等的都等完了——
 *       {@code saveAllChunks} 在 flush 时对 {@code ServerSavedDataStorage} 调 {@code saveAndJoin()}，
 *       每个 {@code ServerLevel.save(null, true, ...)} 又对维度自己的 {@code SavedDataStorage}
 *       调一次 {@code saveAndJoin()}，最后 {@code ChunkMap.saveAllChunks(true)} 里还有
 *       {@code poiManager.flushAll()} + {@code synchronize(true).join()}。</li>
 *   <li>每个维度再排空一次 POI 与 region 写入队列。上面的 flush 路径已经做过一遍，这里保留
 *       「按维度各自追平」的语义，作为防止版本内部行为变化时的兜底。</li>
 * </ol>
 *
 * <p>1.21.1 版本里还有一步 {@code IOUtilities.waitUntilIOWorkerComplete()}（等 NeoForge 丢到 ioPool 上的
 * SavedData 写盘排空）；26.1 上这个方法已经删掉，写盘改由 {@code SavedDataStorage.saveAndJoin()} 同步收口，
 * 也就是第 1 步已经覆盖，所以这里不再有对应调用。
 *
 * <p>落盘完成后顺手采一次「界面要展示的玩家状态」：坐标、生物群系、累计游玩时长、背包快照。
 * 这些只是展示信息，采不到（世界里没玩家）就留空，不影响存档点能不能还原。
 */
public final class WorldFlush {
    public static final class Result {
        public final Path worldRoot;
        public final SnapshotMeta meta;
        /** 建点时玩家的背包快照（栏位序号 → 物品表达式）；世界里没有玩家时为空。 */
        public final List<SnapshotInventory.Entry> inventory;

        Result(Path worldRoot, SnapshotMeta meta, List<SnapshotInventory.Entry> inventory) {
            this.worldRoot = worldRoot;
            this.meta = meta;
            this.inventory = inventory;
        }
    }

    private WorldFlush() {
    }

    public static Result flush(MinecraftServer server, String slot, String source) {
        server.saveEverything(true, true, true);

        for (ServerLevel level : server.getAllLevels()) {
            level.getPoiManager().tick(() -> true);
            // 1.21.1 这里是 ChunkMap.flushWorker()；26.1 的 ChunkMap 继承 SimpleRegionStorage，
            // 写队列的追平入口变成了 synchronize(boolean)（返回 CompletableFuture）。
            level.getChunkSource().chunkMap.synchronize(true).join();
        }

        Path worldRoot = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();

        SnapshotMeta meta = new SnapshotMeta();
        meta.slot = slot;
        meta.status = SnapshotLayout.STATUS_INCOMPLETE;
        meta.savedAtMillis = System.currentTimeMillis();
        meta.worldName = server.getWorldData().getLevelName();
        // 26.1：WorldVersion 上原来叫 getName()，现在是 record 风格的 name()
        meta.minecraftVersion = SharedConstants.getCurrentVersion().name();
        meta.gameTime = server.overworld().getGameTime();
        meta.source = source == null ? "" : source;
        meta.modVersion = RewindVersion.of();

        ServerPlayer player = server.getPlayerList().getPlayers().isEmpty()
                ? null
                : server.getPlayerList().getPlayers().get(0);
        List<SnapshotInventory.Entry> inventory = new ArrayList<>();
        if (player != null) {
            Vec3 position = player.position();
            meta.playerX = position.x;
            meta.playerY = position.y;
            meta.playerZ = position.z;
            meta.playtimeTicks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
            meta.biomeId = biomeId(player);
            inventory = captureInventory(player);
        }
        return new Result(worldRoot, meta, inventory);
    }

    /** 玩家所在生物群系的 id；取不到注册表键时退化成空串（界面那边会自己兜底）。 */
    private static String biomeId(ServerPlayer player) {
        return player.level()
                .getBiome(player.blockPosition())
                .unwrapKey()
                // 26.1：ResourceLocation 改名为 Identifier，ResourceKey 上的 location() 也随之变成 identifier()
                .map(key -> key.identifier().toString())
                .orElse("");
    }

    /**
     * 把玩家物品栏里非空的栏位抓成「栏位序号 → 物品表达式」。
     *
     * <p>用原版 SNBT 而不是自己拼 id + 数量，是因为界面侧（ApricityUI 的 {@code <item>} 元素）
     * 就是用同一套编解码解析它的——附魔、自定义名、耐久这些组件才不会在展示时丢掉。
     *
     * <p>1.21.1 走的是 {@code ItemStack.saveOptional(registries)}；26.1 上这个方法已经删除
     * （见 14-1.21.11-to-26.1/08.md 的 ItemStackTemplate/ItemInstance 改动），改用原版的
     * {@code ItemStack.CODEC} + 注册表序列化上下文手写一遍，拿到的仍然是同一个 SNBT 形状。
     */
    private static List<SnapshotInventory.Entry> captureInventory(ServerPlayer player) {
        List<SnapshotInventory.Entry> entries = new ArrayList<>();
        Inventory inventory = player.getInventory();
        HolderLookup.Provider registries = player.level().registryAccess();
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            entries.add(new SnapshotInventory.Entry(i, encodeStack(ops, stack)));
        }
        return entries;
    }

    /** 单个物品的 SNBT 表达式；编不出来时退化成空串（展示信息，不值得让建点失败）。 */
    private static String encodeStack(RegistryOps<Tag> ops, ItemStack stack) {
        try {
            return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
