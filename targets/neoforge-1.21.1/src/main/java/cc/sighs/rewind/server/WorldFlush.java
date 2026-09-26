package cc.sighs.rewind.server;

import java.nio.file.Path;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.IOUtilities;

/**
 * 把运行中的单人世界强制落盘到「可以安全拷贝」的状态。必须在服务器线程上执行。
 *
 * <p>序列与依据：
 * <ol>
 *   <li>{@code saveEverything(silent=true, flush=true, forced=true)}：玩家数据 / 各维度区块与实体 /
 *       level.dat。第 2 个参数才是同步 flush（展开到 {@code ChunkMap.saveAllChunks(true)} →
 *       {@code flushWorker()} → {@code IOWorker.synchronize(true)} → {@code FileChannel.force(true)}）。</li>
 *   <li>每个维度排空 POI 脏 section 并 flush 一次 region 写入队列。POI 的 IOWorker 没有公开的 join 入口
 *       （{@code SectionStorage.simpleRegionStorage} 是 private），只能这样尽量追平。</li>
 *   <li>{@code IOUtilities.waitUntilIOWorkerComplete()}：NeoForge 把 SavedData 的写盘丢到 ioPool 上，
 *       这一步等它排空。</li>
 * </ol>
 */
public final class WorldFlush {
    public static final class Result {
        public final Path worldRoot;
        public final SnapshotMeta meta;

        Result(Path worldRoot, SnapshotMeta meta) {
            this.worldRoot = worldRoot;
            this.meta = meta;
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
        IOUtilities.waitUntilIOWorkerComplete();

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
        if (player != null) {
            Vec3 position = player.position();
            meta.playerX = position.x;
            meta.playerY = position.y;
            meta.playerZ = position.z;
        }
        return new Result(worldRoot, meta);
    }
}
