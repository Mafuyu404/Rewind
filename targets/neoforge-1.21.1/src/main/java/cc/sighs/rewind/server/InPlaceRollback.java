package cc.sighs.rewind.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import cc.sighs.mixin.RollbackAccessMixins.ChunkMapAccess;
import cc.sighs.mixin.RollbackAccessMixins.ChunkStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.DimensionDataStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.DistanceManagerAccess;
import cc.sighs.mixin.RollbackAccessMixins.EntitySectionManagerAccess;
import cc.sighs.mixin.RollbackAccessMixins.EntityStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.IOWorkerAccess;
import cc.sighs.mixin.RollbackAccessMixins.RegionFileStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.SectionStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.ServerChunkCacheAccess;
import cc.sighs.mixin.RollbackAccessMixins.ServerLevelAccess;
import cc.sighs.mixin.RollbackAccessMixins.SimpleRegionStorageAccess;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotManifest;
import cc.sighs.rewind.snapshot.SnapshotMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;

/**
 * 原地回滚：不关世界、不重开世界，在活着的集成服务器里把世界倒回存档点。
 *
 * <p>整段逻辑必须在服务端线程上跑完（它是唯一能安全碰 ChunkMap 与存储层的线程）。
 *
 * <p>顺序与依据：
 * <ol>
 *   <li><b>找受影响区块</b>：遍历当前加载的区块，判据是「内存里改过还没落盘」或者「它所在 region
 *       文件的 per-chunk 偏移/时间戳与快照里不一致」。用 .mca 头部的 8 KiB 表做逐区块比对，
 *       所以代价与「真正改了多少区块」成正比，而不是与视距成正比。</li>
 *   <li><b>强制卸载</b>：把这些区块的 ticket level 顶到 {@code ChunkLevel.MAX_LEVEL + 1}，再推进
 *       原版的卸载流水线。此时回滚窗口是开着的，卸载触发的落盘会被跳过——内存里这些状态本来就要被覆盖。</li>
 *   <li><b>释放 region 句柄</b>：Windows 上打开着的 .mca 会锁住文件，覆盖之前必须关掉。原版
 *       {@code RegionFileStorage.close()} 只关不清缓存，所以这里关完直接清空缓存，让存储层下次访问时
 *       重新打开（顺便重新读被还原过的头部）。</li>
 *   <li><b>回拷文件</b>：复用 {@link SnapshotMirror} 的反向增量：只有建点之后真正被改写过的文件才会
 *       被拷回来，其余文件本来就还是存档点内容。</li>
 *   <li><b>重载</b>：给卸载掉的区块重建 holder，让它们从（已还原的）磁盘读回来；原版的发送流水线
 *       会自动把新数据推给客户端。</li>
 *   <li><b>内存状态</b>：玩家 / 时间天气 / 被强引用的存档数据（记分板、袭击）单独还原。</li>
 * </ol>
 *
 * <p>已知边界（比「关世界再重开」那条路少了什么）：只还原上面列出的那几类 SavedData，其它
 * {@code <维度>/data/*.dat}（地图、自定义 boss 条等）只还原了磁盘文件，内存里的实例保持原样，
 * 会在下一次自动保存时把旧内容写回去。
 */
public final class InPlaceRollback {
    /** 卸载流水线的推进轮数上限：正常 1-2 轮就收敛，余量留给「还有生成任务在飞」的少数情况。 */
    private static final int MAX_DRAIN_ROUNDS = 400;
    /** 实体重新读盘是异步的，重载之后给它几轮时间落地。 */
    private static final int ENTITY_SETTLE_ROUNDS = 40;
    /** 每一轮之间让出一点时间给世界生成 / 后台线程，否则空转推进不了。 */
    private static final long PARK_NANOS = 200_000L;

    /** 单次原地回滚的统计，用于日志与自测。 */
    public static final class Result {
        public int scannedChunks;
        public int affectedChunks;
        public int unloadedChunks;
        public int reloadedChunks;
        public int stuckChunks;
        public int closedRegionFiles;
        public String mirror = "";
        public int mirrorCopied;
        public int mirrorSkipped;
        public int mirrorFiles;
        public int restoredPlayers;
        public boolean restoredWorldData;
        public long unloadMs;
        public long storageMs;
        public long copyMs;
        public long reloadMs;
        public long stateMs;
        public long totalMs;

        public String summary() {
            return "chunks=" + affectedChunks + "/" + scannedChunks
                    + " unloaded=" + unloadedChunks
                    + " reloaded=" + reloadedChunks
                    + (stuckChunks == 0 ? "" : " stuck=" + stuckChunks)
                    + " closedRegions=" + closedRegionFiles
                    + " players=" + restoredPlayers
                    + " worldData=" + (restoredWorldData ? "yes" : "no")
                    + " unloadMs=" + unloadMs
                    + " storageMs=" + storageMs
                    + " copyMs=" + copyMs
                    + " reloadMs=" + reloadMs
                    + " stateMs=" + stateMs
                    + " totalMs=" + totalMs
                    + " mirror[" + mirror + "]";
        }
    }

    /** 一个待回滚的区块：卸载前的 ticket level 要留着，重载时还原成同一个值。 */
    private static final class Target {
        private final ServerLevel level;
        private final ChunkPos pos;
        private int ticketLevel;
        private boolean unloaded;

        Target(ServerLevel level, ChunkPos pos) {
            this.level = level;
            this.pos = pos;
        }
    }

    private InPlaceRollback() {
    }

    /**
     * 执行原地回滚。调用方必须已经在服务端线程上，并且已经打开回滚窗口（{@link Rewind#beginDiscard()}）。
     *
     * @param worldRoot 活动存档目录
     * @param slot      槽位名
     */
    public static Result run(MinecraftServer server, Path worldRoot, String slot) throws Exception {
        Result result = new Result();
        long startedNanos = System.nanoTime();
        Path slotDir = SnapshotLayout.slotDir(worldRoot, slot);
        SnapshotManifest manifest = SnapshotManifest.load(SnapshotLayout.manifestFile(worldRoot, slot));

        try {
            // 先把「已经排队但还没落盘」的写入落下去：这类区块在内存里已经不是 unsaved、磁盘上又还没变，
            // 两个判据都会漏掉它。这段时间世界不会 tick（我们就在服务端线程上），所以不会再有新的写入插进来。
            Rewind.endDiscard();
            flushWorkers(server);
            Rewind.beginDiscard();

            long stepNanos = System.nanoTime();
            List<Target> targets = collectTargets(server, worldRoot, slotDir, result);
            unload(server, targets, result);
            drainUnloads(server);
            // 实体区块的卸载由区块状态驱动，落在下一次 entityManager.tick()；必须在回拷文件之前跑完，
            // 否则实体数据会以「改世界之后」的内容写到刚还原的文件上。
            tickEntityManagers(server);
            invalidateCaches(server, targets);
            result.unloadMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            result.closedRegionFiles = closeStorageHandles(server);
            result.storageMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            SnapshotMirror.Result mirror = SnapshotMirror.mirror(
                    slotDir, worldRoot, SnapshotMirror.Direction.TO_WORLD, manifest, null);
            result.mirror = mirror.summary();
            result.mirrorCopied = mirror.copied;
            result.mirrorSkipped = mirror.skipped;
            result.mirrorFiles = mirror.files.size();
            result.copyMs = millisSince(stepNanos);

            // 回滚窗口到此结束：后面（重载 + 正常游玩）的落盘都是必须发生的
            Rewind.endDiscard();

            stepNanos = System.nanoTime();
            reload(server, targets, result);
            result.reloadMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            restoreMemoryState(server, slotDir, result);
            result.stateMs = millisSince(stepNanos);
        } finally {
            Rewind.endDiscard();
        }

        result.totalMs = millisSince(startedNanos);
        return result;
    }

    // ------------------------------------------------------------------ 1. 找受影响区块

    /**
     * 判据一：内存里 {@code isUnsaved()}（改过、还没落盘，磁盘上还是存档点内容）。
     * 判据二：所在 region 文件的 per-chunk 偏移/时间戳与快照里不一致（改过且已经落盘）。
     * 两者合起来就是「自建点以来真正变过的区块」，与视距无关。
     */
    private static List<Target> collectTargets(MinecraftServer server, Path worldRoot, Path slotDir, Result result) {
        List<Target> targets = new ArrayList<>();
        Map<Path, int[]> headerCache = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Path liveRegionDir = regionFolder(chunkMap);
            Path snapshotRegionDir = liveRegionDir == null
                    ? null
                    : slotDir.resolve(SnapshotLayout.relativize(worldRoot, liveRegionDir));
            for (var holder : mapAccess(chunkMap).rewind$getChunks()) {
                if (holder.getTicketLevel() > ChunkLevel.MAX_LEVEL) {
                    // 已经在卸载路上：内存里的内容马上就会自己丢掉，磁盘上会被快照覆盖，不用管
                    continue;
                }
                if (!(holder.getLatestChunk() instanceof LevelChunk chunk)) {
                    continue;
                }
                result.scannedChunks++;
                ChunkPos pos = chunk.getPos();
                if (chunk.isUnsaved() || regionChunkChanged(liveRegionDir, snapshotRegionDir, pos, headerCache)) {
                    targets.add(new Target(level, pos));
                }
            }
        }
        result.affectedChunks = targets.size();
        return targets;
    }

    /** 从 .mca 头部读这个区块的偏移/时间戳，和快照里的比：任何一个不一致就算变过。 */
    private static boolean regionChunkChanged(Path liveDir, Path snapshotDir, ChunkPos pos, Map<Path, int[]> cache) {
        if (liveDir == null || snapshotDir == null) {
            return true;
        }
        String name = "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
        int[] live = readRegionHeader(cache, liveDir.resolve(name));
        int[] snapshot = readRegionHeader(cache, snapshotDir.resolve(name));
        if (live == null || snapshot == null) {
            // 有一边不存在：新建的 region，或者快照里没有这个 region，都当成变了
            return true;
        }
        int index = (pos.x & 31) + (pos.z & 31) * 32;
        return live[index] != snapshot[index] || live[1024 + index] != snapshot[1024 + index];
    }

    /** 头部前 4 KiB 是 1024 个偏移，接着 4 KiB 是 1024 个时间戳；读成 int[2048]。 */
    private static int[] readRegionHeader(Map<Path, int[]> cache, Path file) {
        if (cache.containsKey(file)) {
            return cache.get(file);
        }
        int[] header = null;
        if (Files.isRegularFile(file)) {
            byte[] bytes = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                if (in.readNBytes(bytes, 0, 8192) == 8192) {
                    header = new int[2048];
                    for (int i = 0; i < 2048; i++) {
                        int base = i * 4;
                        header[i] = ((bytes[base] & 0xFF) << 24)
                                | ((bytes[base + 1] & 0xFF) << 16)
                                | ((bytes[base + 2] & 0xFF) << 8)
                                | (bytes[base + 3] & 0xFF);
                    }
                }
            } catch (IOException e) {
                Rewind.LOGGER.warn("Rewind: cannot read region header {}", file, e);
            }
        }
        cache.put(file, header);
        return header;
    }

    // ------------------------------------------------------------------ 2. 强制卸载

    private static void unload(MinecraftServer server, List<Target> targets, Result result) {
        int unloadedLevel = ChunkLevel.MAX_LEVEL + 1;
        for (Target target : targets) {
            ChunkMap chunkMap = target.level.getChunkSource().chunkMap;
            ChunkMapAccess access = mapAccess(chunkMap);
            long key = target.pos.toLong();
            var holder = access.rewind$getUpdatingChunkIfPresent(key);
            if (holder == null) {
                continue;
            }
            int old = holder.getTicketLevel();
            target.ticketLevel = old;
            if (old > ChunkLevel.MAX_LEVEL) {
                continue;
            }
            var updated = access.rewind$updateChunkScheduling(key, unloadedLevel, holder, old);
            if (updated != null) {
                distanceAccess(chunkMap.getDistanceManager()).rewind$chunksToUpdateFutures().add(updated);
                target.unloaded = true;
                result.unloadedChunks++;
            }
        }
    }

    /** 推进原版的卸载流水线，直到 toDrop / pendingUnloads / unloadQueue 都空。 */
    private static void drainUnloads(MinecraftServer server) {
        for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
            boolean quiet = true;
            for (ServerLevel level : server.getAllLevels()) {
                ServerChunkCache source = level.getChunkSource();
                ChunkMapAccess access = mapAccess(source.chunkMap);
                access.rewind$processUnloads(() -> true);
                sourceAccess(source).rewind$runDistanceManagerUpdates();
                access.rewind$processUnloads(() -> true);
                if (!access.rewind$toDrop().isEmpty()
                        || !access.rewind$pendingUnloads().isEmpty()
                        || !access.rewind$unloadQueue().isEmpty()) {
                    quiet = false;
                }
            }
            if (quiet) {
                return;
            }
            LockSupport.parkNanos(PARK_NANOS);
        }
        Rewind.LOGGER.warn("Rewind: in-place rollback could not fully drain chunk unloads");
    }

    private static void tickEntityManagers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            levelAccess(level).rewind$entityManager().tick();
        }
    }

    /**
     * 作废按区块缓存的派生数据，否则重载回来的区块会带着「改世界之后」的缓存。
     *
     * <ul>
     *   <li>实体存储的 {@code emptyChunks}：区块被卸载时如果当时没有可保存的实体，这个位置会被记成
     *       「空区块」，之后 {@code loadEntities} 直接返回空——快照里本来有实体的区块就再也读不出来。</li>
     *   <li>POI 的分段缓存：床位 / 工作站这类兴趣点按 16³ 分段缓存在内存里，不改就会留下幻影 POI。</li>
     * </ul>
     */
    private static void invalidateCaches(MinecraftServer server, List<Target> targets) {
        for (ServerLevel level : server.getAllLevels()) {
            Object permanent = entityManagerAccess(level).rewind$permanentStorage();
            var emptyChunks = permanent instanceof EntityStorage storage
                    ? entityStorageAccess(storage).rewind$emptyChunks()
                    : null;
            PoiManager poi = level.getChunkSource().getPoiManager();
            for (Target target : targets) {
                if (target.level != level) {
                    continue;
                }
                if (emptyChunks != null) {
                    emptyChunks.remove(target.pos.toLong());
                }
                for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
                    poi.remove(SectionPos.asLong(target.pos.x, sectionY, target.pos.z));
                }
            }
        }
    }

    // ------------------------------------------------------------------ 3. 释放存储句柄

    /**
     * 关掉所有 region 文件句柄（区块 / POI / 实体三条链），并把缓存清空，让存储层下次访问时重开。
     * 关之前先把排队中的写入排空：那些写入本来就是要被覆盖掉的旧内容。
     */
    private static int closeStorageHandles(MinecraftServer server) {
        int closed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            ServerChunkCache source = level.getChunkSource();
            closed += closeWorker(chunkStorageAccess(source.chunkMap).rewind$worker());
            closed += closeWorker(poiWorker(source.getPoiManager()));
            Object permanent = entityManagerAccess(level).rewind$permanentStorage();
            if (permanent instanceof EntityStorage storage) {
                closed += closeWorker(
                        simpleRegionStorageAccess(entityStorageAccess(storage).rewind$simpleRegionStorage()).rewind$worker());
            }
        }
        return closed;
    }

    private static IOWorker poiWorker(PoiManager poi) {
        SimpleRegionStorage storage = sectionStorageAccess(poi).rewind$simpleRegionStorage();
        return storage == null ? null : simpleRegionStorageAccess(storage).rewind$worker();
    }

    /** 排空三条存储链（区块 / POI / 实体）排队中的写入，不 fsync。 */
    private static void flushWorkers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerChunkCache source = level.getChunkSource();
            drainWorker(chunkStorageAccess(source.chunkMap).rewind$worker());
            drainWorker(poiWorker(source.getPoiManager()));
            Object permanent = entityManagerAccess(level).rewind$permanentStorage();
            if (permanent instanceof EntityStorage storage) {
                drainWorker(simpleRegionStorageAccess(entityStorageAccess(storage).rewind$simpleRegionStorage()).rewind$worker());
            }
        }
    }

    private static void drainWorker(IOWorker worker) {
        if (worker == null) {
            return;
        }
        try {
            worker.synchronize(false).join();
        } catch (Throwable t) {
            Rewind.LOGGER.warn("Rewind: failed to drain pending chunk writes", t);
        }
    }

    private static int closeWorker(IOWorker worker) {
        if (worker == null) {
            return 0;
        }
        drainWorker(worker);
        RegionFileStorage storage = ioWorkerAccess(worker).rewind$storage();
        var cache = regionFileStorageAccess(storage).rewind$regionCache();
        int size = cache.size();
        for (RegionFile file : cache.values()) {
            try {
                file.close();
            } catch (Throwable t) {
                Rewind.LOGGER.warn("Rewind: failed to close a region file", t);
            }
        }
        cache.clear();
        return size;
    }

    // ------------------------------------------------------------------ 4. 重载

    private static void reload(MinecraftServer server, List<Target> targets, Result result) {
        for (Target target : targets) {
            ChunkMap chunkMap = target.level.getChunkSource().chunkMap;
            ChunkMapAccess access = mapAccess(chunkMap);
            long key = target.pos.toLong();
            if (!target.unloaded) {
                // 卸载时被跳过了（还有生成任务在飞）：保持原样，这个区块不会回滚，记一笔
                result.stuckChunks++;
                continue;
            }
            var holder = access.rewind$getUpdatingChunkIfPresent(key);
            if (holder != null) {
                // 没卸干净：直接顶回原来的 ticket level，区块保持内存里的旧内容
                var updated = access.rewind$updateChunkScheduling(key, target.ticketLevel, holder, holder.getTicketLevel());
                if (updated != null) {
                    distanceAccess(chunkMap.getDistanceManager()).rewind$chunksToUpdateFutures().add(updated);
                }
                result.stuckChunks++;
                continue;
            }
            var created = access.rewind$updateChunkScheduling(key, target.ticketLevel, null, ChunkLevel.MAX_LEVEL + 1);
            if (created != null) {
                distanceAccess(chunkMap.getDistanceManager()).rewind$chunksToUpdateFutures().add(created);
            }
            access.rewind$chunkTypeCache().remove(key);
        }

        for (ServerLevel level : server.getAllLevels()) {
            sourceAccess(level.getChunkSource()).rewind$runDistanceManagerUpdates();
        }
        for (Target target : targets) {
            if (!target.unloaded) {
                continue;
            }
            ServerChunkCache source = target.level.getChunkSource();
            ChunkMapAccess access = mapAccess(source.chunkMap);
            sourceAccess(source).rewind$clearCache();
            try {
                // 同步等它从磁盘读回来：这也是把区块重新推给客户端的触发点（原版的发送流水线）
                source.getChunk(target.pos.x, target.pos.z, ChunkStatus.FULL, true);
                access.rewind$chunkTypeCache().remove(target.pos.toLong());
                result.reloadedChunks++;
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload chunk {} in {}",
                        target.pos, target.level.dimension().location(), t);
            }
        }

        // 实体重新读盘走的是后台队列，给它几轮时间落地（晚一两 tick 也无所谓，过渡还在盖着）
        for (int round = 0; round < ENTITY_SETTLE_ROUNDS; round++) {
            tickEntityManagers(server);
            if (round + 1 < ENTITY_SETTLE_ROUNDS) {
                LockSupport.parkNanos(PARK_NANOS);
            }
        }
    }

    // ------------------------------------------------------------------ 5. 内存状态

    private static void restoreMemoryState(MinecraftServer server, Path slotDir, Result result) {
        try {
            result.restoredWorldData = restoreWorldData(server, slotDir);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to restore world data in place", t);
        }
        try {
            result.restoredPlayers = restorePlayers(server, slotDir);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to restore players in place", t);
        }
        try {
            restoreSavedData(server, slotDir);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to reload saved data in place", t);
        }
    }

    /** 时间 / 天气 / 出生点来自 level.dat；原地回滚不改磁盘上的 level.dat，直接把值写进活着的 WorldData。 */
    private static boolean restoreWorldData(MinecraftServer server, Path slotDir) throws IOException {
        Path file = slotDir.resolve("level.dat");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("Data");
        ServerLevel overworld = server.overworld();
        if (overworld.getLevelData() instanceof ServerLevelData levelData) {
            if (data.contains("GameTime", 99)) {
                levelData.setGameTime(data.getLong("GameTime"));
            }
            if (data.contains("DayTime", 99)) {
                levelData.setDayTime(data.getLong("DayTime"));
            }
        }
        overworld.setWeatherParameters(
                data.getInt("clearWeatherTime"),
                data.getInt("rainTime"),
                data.getBoolean("raining"),
                data.getBoolean("thundering"));
        if (data.contains("SpawnX", 99) && data.contains("SpawnY", 99) && data.contains("SpawnZ", 99)) {
            overworld.setDefaultSpawnPos(
                    new BlockPos(data.getInt("SpawnX"), data.getInt("SpawnY"), data.getInt("SpawnZ")),
                    data.getFloat("SpawnAngle"));
        }
        server.forceTimeSynchronization();
        return true;
    }

    /** 玩家状态从快照的 playerdata 读回来；原版没有「重读玩家」的入口，只能 load 之后手动补齐客户端同步。 */
    private static int restorePlayers(MinecraftServer server, Path slotDir) throws IOException {
        int restored = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Path file = slotDir.resolve("playerdata").resolve(player.getUUID() + ".dat");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            restorePlayer(server, player, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            restored++;
        }
        return restored;
    }

    private static void restorePlayer(MinecraftServer server, ServerPlayer player, CompoundTag tag) {
        // 先把「改世界之后加的 buff」清掉：客户端会收到对应的移除包
        player.removeAllEffects();
        player.load(tag);
        if (tag.contains("playerGameType", 99)) {
            player.setGameMode(GameType.byId(tag.getInt("playerGameType")));
        }

        // 维度可能与建点时不在一起：那种情况要走跨维度传送，否则客户端的世界不会跟着换
        ServerLevel savedLevel = dimensionOf(server, tag);
        Vec3 position = player.position();
        if (savedLevel != null && savedLevel != player.serverLevel()) {
            player.teleportTo(savedLevel, position.x, position.y, position.z, player.getYRot(), player.getXRot());
        } else {
            player.connection.teleport(position.x, position.y, position.z, player.getYRot(), player.getXRot(), Set.of());
        }

        // load() 只把 NBT 读进内存，效果列表不会自己同步；清一遍再逐个 addEffect 才会发给客户端
        List<MobEffectInstance> effects = new ArrayList<>(player.getActiveEffects());
        player.removeAllEffects();
        for (MobEffectInstance effect : effects) {
            player.addEffect(effect);
        }

        player.inventoryMenu.broadcastFullState();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastFullState();
        }
        player.onUpdateAbilities();
        player.setExperienceLevels(player.experienceLevel);
        player.setExperiencePoints(player.totalExperience);
        // 血量 / 饥饿 / 经验值靠 ServerPlayer.tick() 里「和上次发的比」的逻辑重发，这里把基线清掉
        player.resetSentInfo();
        player.getRecipeBook().sendInitialRecipeBook(player);
    }

    private static ServerLevel dimensionOf(MinecraftServer server, CompoundTag tag) {
        Tag dimension = tag.get("Dimension");
        if (dimension == null) {
            return null;
        }
        return Level.RESOURCE_KEY_CODEC.parse(NbtOps.INSTANCE, dimension)
                .result()
                .map(server::getLevel)
                .orElse(null);
    }

    /**
     * 被强引用的 SavedData 要换掉实例（或者把内容重新灌进去）才算真回滚。
     *
     * <p>记分板：不能走 {@code DimensionDataStorage.computeIfAbsent}——那个存储实例是服务端建服时
     * 单独创建的，{@code ServerLevel} 手里是另一个（同一个目录、不同对象），拿不到；而且
     * {@code Scoreboard.addObjective} 对重名会抛异常，直接 load 到活着的记分板上必然失败
     * （原版日志里那句 {@code Error loading saved data: scoreboard}）。所以先把现有 objective / team
     * 清掉，再用 {@code ServerScoreboard.dataFactory().deserializer()} 把快照内容灌回同一个记分板对象；
     * 老的那个 save data 实例仍然持有脏标记回调，读的是同一个记分板，下一次自动保存会写回正确内容。
     *
     * <p>袭击：{@code ServerLevel.raids} 是 final 字段，直接换实例。
     */
    private static void restoreSavedData(MinecraftServer server, Path slotDir) throws IOException {
        Path file = slotDir.resolve("data").resolve("scoreboard.dat");
        if (Files.isRegularFile(file)) {
            CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data");
            ServerScoreboard scoreboard = server.getScoreboard();
            for (Objective objective : new ArrayList<>(scoreboard.getObjectives())) {
                scoreboard.removeObjective(objective);
            }
            for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
                scoreboard.removePlayerTeam(team);
            }
            scoreboard.dataFactory().deserializer().apply(data, server.registryAccess());
        }

        for (ServerLevel level : server.getAllLevels()) {
            String fileId = Raids.getFileId(level.dimensionTypeRegistration());
            DimensionDataStorage levelStorage = level.getDataStorage();
            cacheOf(levelStorage).remove(fileId);
            levelAccess(level).rewind$setRaids(levelStorage.computeIfAbsent(Raids.factory(level), fileId));
        }
    }

    private static Map<String, SavedData> cacheOf(DimensionDataStorage storage) {
        return dimensionStorageAccess(storage).rewind$cache();
    }

    // ------------------------------------------------------------------ 访问器包装

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static ChunkMapAccess mapAccess(ChunkMap chunkMap) {
        return (ChunkMapAccess) (Object) chunkMap;
    }

    private static ChunkStorageAccess chunkStorageAccess(ChunkMap chunkMap) {
        return (ChunkStorageAccess) (Object) chunkMap;
    }

    private static DistanceManagerAccess distanceAccess(net.minecraft.server.level.DistanceManager manager) {
        return (DistanceManagerAccess) (Object) manager;
    }

    private static ServerChunkCacheAccess sourceAccess(ServerChunkCache source) {
        return (ServerChunkCacheAccess) (Object) source;
    }

    private static ServerLevelAccess levelAccess(ServerLevel level) {
        return (ServerLevelAccess) (Object) level;
    }

    private static EntitySectionManagerAccess entityManagerAccess(ServerLevel level) {
        return (EntitySectionManagerAccess) (Object) levelAccess(level).rewind$entityManager();
    }

    private static EntityStorageAccess entityStorageAccess(EntityStorage storage) {
        return (EntityStorageAccess) (Object) storage;
    }

    private static SectionStorageAccess sectionStorageAccess(PoiManager poi) {
        return (SectionStorageAccess) (Object) poi;
    }

    private static SimpleRegionStorageAccess simpleRegionStorageAccess(SimpleRegionStorage storage) {
        return (SimpleRegionStorageAccess) (Object) storage;
    }

    private static IOWorkerAccess ioWorkerAccess(IOWorker worker) {
        return (IOWorkerAccess) (Object) worker;
    }

    private static RegionFileStorageAccess regionFileStorageAccess(RegionFileStorage storage) {
        return (RegionFileStorageAccess) (Object) storage;
    }

    private static DimensionDataStorageAccess dimensionStorageAccess(DimensionDataStorage storage) {
        return (DimensionDataStorageAccess) (Object) storage;
    }

    private static Path regionFolder(ChunkMap chunkMap) {
        RegionFileStorage storage = ioWorkerAccess(chunkStorageAccess(chunkMap).rewind$worker()).rewind$storage();
        return regionFileStorageAccess(storage).rewind$folder();
    }
}
