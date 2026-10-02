package cc.sighs.rewind.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
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
 * <p>序列与依据：
 * <ol>
 *   <li>{@code saveEverything(silent=true, flush=true, forced=true)}：玩家数据 / 各维度区块与实体 /
 *       level.dat。第 2 个参数才是同步 flush——1.20.1 里它一路展开到 {@code ChunkMap.saveAllChunks(true)}
 *       → {@code ChunkStorage.flushWorker()} → {@code IOWorker.synchronize(false)}，也就是区块写入队列
 *       的排空入口。</li>
 *   <li>每个维度排空 POI 脏 section，并再 flush 一次 region 写入队列。POI 的 IOWorker 没有公开的 join
 *       入口（{@code SectionStorage.worker} 是 private），只能这样尽量追平。</li>
 * </ol>
 *
 * <p>与 NeoForge 1.21.1 那份的差别：
 * <ul>
 *   <li>**没有** {@code IOUtilities.waitUntilIOWorkerComplete()}：那是 NeoForge 专有类（它把 SavedData
 *       的写盘丢到自己的 ioPool 上），Forge 1.20.1 不存在，也不需要——1.20.1 的
 *       {@code DimensionDataStorage.save()} 就在 {@code saveEverything} 里同步写完，
 *       没有额外要在外面等的线程池。</li>
 *   <li>没有 {@code ChunkMap.flushWorker()}（1.20.1 的入口在父类 {@code ChunkStorage} 上，名字一样、
 *       是 public），所以调用点写成 {@code chunkMap.flushWorker()} 依然成立。</li>
 * </ul>
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
            level.getChunkSource().chunkMap.flushWorker();
        }

        Path worldRoot = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();

        SnapshotMeta meta = new SnapshotMeta();
        meta.slot = slot;
        meta.status = SnapshotLayout.STATUS_INCOMPLETE;
        meta.savedAtMillis = System.currentTimeMillis();
        meta.worldName = server.getWorldData().getLevelName();
        meta.minecraftVersion = SharedConstants.getCurrentVersion().getName();
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
                .map(key -> key.location().toString())
                .orElse("");
    }

    /**
     * 把玩家物品栏里非空的栏位抓成「栏位序号 → 物品表达式」。
     *
     * <p>1.20.1 的 {@code ItemStack} 还没有 1.20.5 那套数据组件，序列化就是
     * {@code ItemStack.save(new CompoundTag())}，读回来是 {@code ItemStack.of(CompoundTag)}——界面侧
     * （ApricityUI 的 {@code <item>} 元素）用的就是后者，两边同一套编解码，附魔、自定义名、耐久
     * 这些 NBT 才不会在展示时丢掉。也因为没有数据组件，这里**不需要** NeoForge 1.21.1 那份要传的
     * {@code HolderLookup.Provider}。
     */
    private static List<SnapshotInventory.Entry> captureInventory(ServerPlayer player) {
        List<SnapshotInventory.Entry> entries = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            entries.add(new SnapshotInventory.Entry(i, stack.save(new CompoundTag()).toString()));
        }
        return entries;
    }
}
