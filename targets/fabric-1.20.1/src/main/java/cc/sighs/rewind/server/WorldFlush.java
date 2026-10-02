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
 *       level.dat。第 2 个参数才是同步 flush（{@code saveAllChunks} 把它转给
 *       {@code ServerLevel.save} → {@code ServerChunkCache.save(true)} → {@code IOWorker.synchronize(true)}
 *       → 文件通道 force）。</li>
 *   <li>每个维度排空 POI 脏 section（{@code PoiManager.tick}）并 flush 一次区块写入队列
 *       （{@code ChunkStorage.flushWorker()}）。POI 的 IOWorker 没有公开的 join 入口
 *       （{@code SectionStorage.worker} 是 private），只能这样尽量追平。</li>
 * </ol>
 *
 * <p>与 NeoForge 1.21.1 的差异：那边最后还要等 {@code IOUtilities.waitUntilIOWorkerComplete()}
 * （NeoForge 把 SavedData 的写盘丢到自己的 ioPool 上，原版没有这一步）。原版的 SavedData 保存是
 * {@code saveEverything} 里同步做完的，所以这个 target 不需要等价调用。
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
        // 1.20.1 的 ServerPlayer 没有把 level() 收窄成 ServerLevel，取维度要走 serverLevel()
        return player.serverLevel()
                .getBiome(player.blockPosition())
                .unwrapKey()
                .map(key -> key.location().toString())
                .orElse("");
    }

    /**
     * 把玩家物品栏里非空的栏位抓成「栏位序号 → 物品表达式」。
     *
     * <p>用原版 SNBT 而不是自己拼 id + 数量，是因为界面侧（ApricityUI 的 {@code <item>} 元素）
     * 也是拿原版编解码解析它的——两边同一套格式，附魔、自定义名、耐久这些才不会在展示时丢掉。
     *
     * <p>与 NeoForge 1.21.1 的差异：1.20.1 没有 {@code HolderLookup.Provider}，
     * 所以用 {@code ItemStack.save(CompoundTag)}（1.20.5 之后才有的 {@code saveOptional} 在这里不存在）。
     * 它写出来的是 1.20.1 那一套字段名：{@code {id:"minecraft:x",Count:1b,tag:{...}}}。
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
