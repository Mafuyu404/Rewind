package cc.sighs.rewind.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.common.config.RollbackSettings;
import cc.sighs.rewind.common.store.SnapshotBlockIo;
import cc.sighs.rewind.snapshot.SnapshotBlockStore;
import cc.sighs.rewind.snapshot.SnapshotBlocks;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotManifest;
import cc.sighs.rewind.snapshot.SnapshotMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreboardSaveData;

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
 *   <li><b>实体卸载排空</b>：实体区块的卸载由区块状态驱动、靠 {@code PersistentEntitySectionManager.tick()}
 *       推进，而且「实体还没读回来」时那一次会被推迟。必须在这一段里泵干净。</li>
 *   <li><b>作废按区块缓存</b>：实体存储的 {@code emptyChunks} 与 POI 的分段缓存。</li>
 *   <li><b>释放 region 句柄</b>：Windows 上打开着的 .mca 会锁住文件，覆盖之前必须关掉。原版
 *       {@code RegionFileStorage.close()} 只关不清缓存，所以这里关完直接清空缓存，让存储层下次访问时
 *       重新打开（顺便重新读被还原过的头部）。</li>
 *   <li><b>回拷文件</b>：复用 {@link SnapshotBlockIo#restoreInto} 的反向增量：只有建点之后真正被改写过的
 *       文件才会被拷回来，其余文件本来就还是存档点内容。</li>
 *   <li><b>重载</b>：给卸载掉的区块重建 holder，让它们从（已还原的）磁盘读回来；原版的发送流水线
 *       会自动把新数据推给客户端。</li>
 *   <li><b>内存状态</b>：玩家 / 时间天气 / 被强引用的存档数据（记分板、袭击）单独还原。</li>
 * </ol>
 *
 * <p>已知边界（比「关世界再重开」那条路少了什么）：只还原上面列出的那几类 SavedData，其它
 * {@code <维度>/data/*.dat}（地图、自定义 boss 条等）只还原了磁盘文件，内存里的实例保持原样，
 * 会在下一次自动保存时把旧内容写回去。
 *
 * <h2>1.20.1 与 NeoForge 1.21.1 那份实现的差异（逐条核对过 1.20.1 的原版内部结构）</h2>
 * <ul>
 *   <li><b>取「这个区块在内存里长什么样」</b>：1.21.1 用 {@code ChunkHolder.getLatestChunk()}；
 *       1.20.1 上这个名字不存在，等价物是 {@code ChunkHolder.getLastAvailable()}（同样是
 *       「最后一个已就绪的 ChunkAccess」，可能是 ProtoChunk），所以后面照样要过
 *       {@code instanceof LevelChunk}。</li>
 *   <li><b>没有 {@code SimpleRegionStorage}</b>（1.20.5 才引入）：区块 / 实体 / POI 三条链各自直接
 *       持有 {@code IOWorker}（{@code ChunkStorage.worker}、{@code EntityStorage.worker}、
 *       {@code SectionStorage.worker}），所以「取 worker」不再需要中间一跳。</li>
 *   <li><b>POI 缓存的作废方式</b>：1.21.1 有 {@code PoiManager.remove(long)}（按 section 键作废）；
 *       1.20.1 的 {@code PoiManager.remove(BlockPos)} 是另一回事（删单个兴趣点记录），所以这里直接删
 *       {@code SectionStorage} 的 {@code storage} 与 {@code dirty} 两个字段里的 section 键。两个都要删：
 *       只删 {@code storage} 的话那个键还留在 {@code dirty} 里，下一 tick {@code tick()} 会照旧把它写一遍
 *       （内容已经没了，等于把刚还原好的 poi 文件抹成空的）。</li>
 *   <li><b>{@code ChunkHolder.getGenerationRefCount()} 不存在</b>（1.20.5 起的区块系统才有）。它只被
 *       「卸载卡住」那条诊断日志用到，这里换成「还挂在 updatingChunkMap 上、没被卸掉的 holder 数」。</li>
 *   <li><b>记分板</b>：1.20.1 没有 {@code Scoreboard.dataFactory()} / {@code SavedData.Factory}（也是
 *       1.20.5 之后才有的），原版建服时走的是
 *       {@code DimensionDataStorage.computeIfAbsent(scoreboard::createData, scoreboard::createData, "scoreboard")}。
 *       这里用等价的 {@code new ScoreboardSaveData(scoreboard).load(data)}：{@code ScoreboardSaveData}
 *       持有活着的记分板实例，{@code load} 直接把快照内容灌进去——和 {@code createData(tag)} 同一件事，
 *       只是不会顺手往 {@code ServerScoreboard.dirtyListeners} 里再挂一个监听器。</li>
 *   <li><b>袭击</b>：1.20.1 没有 {@code Raids.factory(level)}，走原版建服时那条
 *       {@code computeIfAbsent(tag -&gt; Raids.load(level, tag), () -&gt; new Raids(level), fileId)}。</li>
 *   <li><b>NBT 读取</b>：1.20.1 的 {@code NbtIo} 只有 {@code readCompressed(File)} 这一种重载，
 *       没有 {@code Path} 版、也没有 {@code NbtAccounter} 参数。</li>
 * </ul>
 */
public final class InPlaceRollback {
    /** 卸载流水线的推进轮数上限：正常 1-2 轮就收敛，余量留给「还有生成任务在飞」的少数情况。 */
    private static final int MAX_DRAIN_ROUNDS = 400;
    /** 实体重新读盘是异步的，重载之后给它几轮时间落地。 */
    private static final int ENTITY_SETTLE_ROUNDS = 40;
    /** 每一轮之间让出一点时间给世界生成 / 后台线程，否则空转推进不了。 */
    private static final long PARK_NANOS = 200_000L;
    /** 卸载流水线连续多少轮没有进展就放弃（剩下的交给重建那一步兜住）。 */
    private static final int STALL_ROUNDS = 40;

    /** 单次原地回滚的统计，用于日志与自测。 */
    public static final class Result {
        public int scannedChunks;
        public int affectedChunks;
        public int unloadedChunks;
        public int reloadedChunks;
        /** 视野外、交给原版流水线异步读回来的区块数。 */
        public int deferredChunks;
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
        /** 细分子步骤，只进日志：用来定位下一次该优化哪一段。 */
        public long collectMs;
        public long unloadPumpMs;
        public long entityDrainMs;
        public long cacheMs;
        public long holdersMs;
        public long loadMs;
        public long settleMs;
        public long stateWorldMs;
        public long statePlayersMs;

        public String summary() {
            return "chunks=" + affectedChunks + "/" + scannedChunks
                    + " unloaded=" + unloadedChunks
                    + " reloaded=" + reloadedChunks
                    + (deferredChunks == 0 ? "" : " deferred=" + deferredChunks)
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
                    + " sub[collect=" + collectMs
                    + " unloadPump=" + unloadPumpMs
                    + " entityDrain=" + entityDrainMs
                    + " cache=" + cacheMs
                    + " holders=" + holdersMs
                    + " load=" + loadMs
                    + " settle=" + settleMs
                    + " stateWorld=" + stateWorldMs
                    + " statePlayers=" + statePlayersMs + "]"
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
            SnapshotBlocks snapshotBlocks = SnapshotBlocks.load(SnapshotLayout.blockMapFile(worldRoot, slot));
            // 快照里块编码的 .mca 没有实体文件，读它的头部要过一个块存储会话；用完就关
            List<Target> targets;
            try (SnapshotBlockStore headerStore = SnapshotBlockIo.openStore(worldRoot)) {
                targets = collectTargets(server, worldRoot, slotDir, headerStore, snapshotBlocks, result);
            }
            result.collectMs = millisSince(stepNanos);

            long subNanos = System.nanoTime();
            unload(server, targets, result);
            drainUnloads(server);
            result.unloadPumpMs = millisSince(subNanos);

            subNanos = System.nanoTime();
            // 实体区块的卸载由区块状态驱动，靠 entityManager.tick() 推进，而且可能因为「实体还没读完」
            // 被推迟到下一 tick。必须在这一段里彻底排空，否则它会落到回滚窗口之外，把「改世界之后」的
            // 实体列表写回刚还原的文件。
            drainEntityUnloads(server);
            result.entityDrainMs = millisSince(subNanos);

            subNanos = System.nanoTime();
            // 到这里被回滚接管的区块都已经从内存里丢掉了；接下来由回滚自己重建 holder，不再需要挡重建
            Rewind.clearUnloadGuards();
            invalidateCaches(server, targets);
            result.cacheMs = millisSince(subNanos);
            result.unloadMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            result.closedRegionFiles = closeStorageHandles(server);
            result.storageMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            SnapshotMirror.Result mirror = SnapshotBlockIo.restoreInto(worldRoot, slot, manifest);
            result.mirror = mirror.summary();
            result.mirrorCopied = mirror.copied;
            result.mirrorSkipped = mirror.skipped;
            result.mirrorFiles = mirror.files.size();
            result.copyMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            recreateHolders(server, targets, result);
            result.holdersMs = millisSince(stepNanos);
            result.reloadMs = result.holdersMs;

            // 玩家 / 时间天气必须排在这里：holder 已经重建、但区块还没成批重发。
            // 位置包要排在区块包前面，否则客户端得先处理完上百个区块包才会回 ack，而服务端在收到 ack
            // 之前是关着右键交互的（handleUseItemOn 里那道 awaitingPositionFromClient 的门）。
            // 也不能更早：player.load() 会走 Entity.setPosRaw，那时 holder 不在，位置就落不到位。
            stepNanos = System.nanoTime();
            restoreWorldAndPlayers(server, slotDir, result);
            result.stateMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            loadChunks(server, targets, result);
            result.reloadMs += millisSince(stepNanos);

            try {
                restoreSavedData(server, slotDir);
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload saved data in place", t);
            }
        } finally {
            // 回滚窗口一直开到整段结束：这期间任何落盘都是要被丢弃的（实体卸载的收尾写入尤其危险）
            Rewind.clearUnloadGuards();
            Rewind.endDiscard();
        }

        result.totalMs = millisSince(startedNanos);
        return result;
    }

    // ------------------------------------------------------------------ 1. 找受影响区块

    /**
     * 判据（任一成立就算受影响）：
     * <ul>
     *   <li>{@code isUnsaved()}：内存里改过还没落盘（方块 / 方块实体 / 光照 / 计划刻 / 结构都会置这个位）。</li>
     *   <li>区块 region 文件的 per-chunk 偏移/时间戳与快照不一致（改过且已经落盘）。</li>
     *   <li>实体或 POI 的 region 文件同样不一致（落过盘的实体改动）。</li>
     *   <li>内存里还有「会被保存的实体」，或者快照里这个区块本来有实体。
     *       这条是实体回滚的关键：实体存储没有 per-chunk 脏标记，新放的实体在自动保存之前
     *       磁盘上完全看不出来，只能靠内存状态判断。</li>
     * </ul>
     * 所以代价与「真正改了多少区块」成正比，与视距无关。
     */
    private static List<Target> collectTargets(MinecraftServer server, Path worldRoot, Path slotDir,
            SnapshotBlockStore store, SnapshotBlocks blocks, Result result) throws IOException {
        List<Target> targets = new ArrayList<>();
        Map<Path, int[]> headerCache = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            List<Path> liveDirs = liveRegionDirs(level);
            List<Path> snapshotDirs = new ArrayList<>(liveDirs.size());
            for (Path liveDir : liveDirs) {
                snapshotDirs.add(slotDir.resolve(SnapshotLayout.relativize(worldRoot, liveDir)));
            }
            EntitySectionStorage<Entity> sections = entityManagerAccess(level).rewind$sectionStorage();
            for (var holder : mapAccess(chunkMap).rewind$getChunks()) {
                if (holder.getTicketLevel() > ChunkLevel.MAX_LEVEL) {
                    // 已经在卸载路上：内存里的内容马上就会自己丢掉，磁盘上会被快照覆盖，不用管
                    continue;
                }
                // 1.20.1 没有 getLatestChunk()，等价的入口是 getLastAvailable()（见类注释）
                if (!(holder.getLastAvailable() instanceof LevelChunk chunk)) {
                    continue;
                }
                result.scannedChunks++;
                ChunkPos pos = chunk.getPos();
                if (chunk.isUnsaved()
                        || regionChunkChanged(store, slotDir, blocks, liveDirs, snapshotDirs, pos, headerCache)
                        || hasSaveableEntities(sections, pos)
                        || snapshotHasEntities(store, slotDir, blocks, snapshotDirs, pos, headerCache)) {
                    targets.add(new Target(level, pos));
                }
            }
        }
        result.affectedChunks = targets.size();
        return targets;
    }

    /** 内存里这个区块还有没有会被保存的实体（玩家不算，{@code Player.shouldBeSaved()} 为 false）。 */
    private static boolean hasSaveableEntities(EntitySectionStorage<Entity> sections, ChunkPos pos) {
        return sections.getExistingSectionsInChunk(pos.toLong())
                .flatMap(section -> section.getEntities())
                .anyMatch(EntityAccess::shouldBeSaved);
    }

    /** 快照里的实体文件在这个区块有没有数据（{@code RegionFile.clear} 会把偏移归零，所以偏移非 0 就是有）。 */
    private static boolean snapshotHasEntities(SnapshotBlockStore store, Path slotDir, SnapshotBlocks blocks,
            List<Path> snapshotDirs, ChunkPos pos, Map<Path, int[]> cache) throws IOException {
        // liveRegionDirs 的顺序是「区块 / 实体 / POI」
        if (snapshotDirs.size() < 2) {
            return false;
        }
        int[] header = snapshotHeader(cache, store, slotDir, blocks, snapshotDirs.get(1), regionFileName(pos));
        return header[(pos.x & 31) + (pos.z & 31) * 32] != 0;
    }

    private static String regionFileName(ChunkPos pos) {
        return "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
    }

    /** 这个维度里所有「按区块存 region 文件」的存储目录：区块 / 实体 / POI。 */
    private static List<Path> liveRegionDirs(ServerLevel level) {
        ServerChunkCache source = level.getChunkSource();
        List<Path> dirs = new ArrayList<>(3);
        addFolder(dirs, chunkStorageAccess(source.chunkMap).rewind$worker());
        Object permanent = entityManagerAccess(level).rewind$permanentStorage();
        if (permanent instanceof EntityStorage storage) {
            addFolder(dirs, entityStorageAccess(storage).rewind$worker());
        }
        addFolder(dirs, sectionStorageAccess(source.getPoiManager()).rewind$worker());
        return dirs;
    }

    private static void addFolder(List<Path> dirs, IOWorker worker) {
        if (worker == null) {
            return;
        }
        dirs.add(regionFileStorageAccess(ioWorkerAccess(worker).rewind$storage()).rewind$folder());
    }

    /** 任意一个存储里这个区块的偏移/时间戳与快照不一致就算变过。 */
    private static boolean regionChunkChanged(SnapshotBlockStore store, Path slotDir, SnapshotBlocks blocks,
            List<Path> liveDirs, List<Path> snapshotDirs, ChunkPos pos, Map<Path, int[]> cache) throws IOException {
        String name = regionFileName(pos);
        int index = (pos.x & 31) + (pos.z & 31) * 32;
        int count = Math.min(liveDirs.size(), snapshotDirs.size());
        for (int i = 0; i < count; i++) {
            int[] live = readRegionHeader(cache, liveDirs.get(i).resolve(name));
            int[] snapshot = snapshotHeader(cache, store, slotDir, blocks, snapshotDirs.get(i), name);
            if (live[index] != snapshot[index] || live[1024 + index] != snapshot[1024 + index]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 快照里这个 region 文件的头部。
     *
     * <p>块编码的 .mca 在槽位里没有实体文件，所以得从块存储拼出来——反正只要前两个 4 KiB 块。
     * 老格式的槽位（映射里没有这个文件）照旧直接读文件。
     */
    private static int[] snapshotHeader(Map<Path, int[]> cache, SnapshotBlockStore store, Path slotDir,
            SnapshotBlocks blocks, Path snapshotDir, String name) throws IOException {
        Path file = snapshotDir.resolve(name);
        SnapshotBlocks.Entry entry = blocks == null ? null : blocks.get(SnapshotLayout.relativize(slotDir, file));
        if (entry == null) {
            return readRegionHeader(cache, file);
        }
        int[] cached = cache.get(file);
        if (cached != null) {
            return cached;
        }
        byte[] prefix = store.readPrefix(entry.hashes, 8192);
        int[] header = decodeHeader(prefix);
        cache.put(file, header);
        return header;
    }

    /**
     * 头部前 4 KiB 是 1024 个偏移，接着 4 KiB 是 1024 个时间戳；读成 int[2048]。
     * 文件不存在或读不满时返回全 0，这样「两边都没有这个区块」会被判成没变，
     * 而不是因为「region 文件在一边不存在」就整片误判。
     */
    private static int[] readRegionHeader(Map<Path, int[]> cache, Path file) {
        int[] cached = cache.get(file);
        if (cached != null) {
            return cached;
        }
        int[] header = new int[2048];
        if (Files.isRegularFile(file)) {
            byte[] bytes = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                if (in.readNBytes(bytes, 0, 8192) == 8192) {
                    header = decodeHeader(bytes);
                }
            } catch (IOException e) {
                Rewind.LOGGER.warn("Rewind: cannot read region header {}", file, e);
            }
        }
        cache.put(file, header);
        return header;
    }

    /** 把 8 KiB 的 region 头部解析成 2048 个 int（前 1024 个偏移、后 1024 个时间戳，都是大端）。 */
    private static int[] decodeHeader(byte[] bytes) {
        int[] header = new int[2048];
        int count = Math.min(2048, bytes.length / 4);
        for (int i = 0; i < count; i++) {
            int base = i * 4;
            header[i] = ((bytes[base] & 0xFF) << 24)
                    | ((bytes[base + 1] & 0xFF) << 16)
                    | ((bytes[base + 2] & 0xFF) << 8)
                    | (bytes[base + 3] & 0xFF);
        }
        return header;
    }

    // ------------------------------------------------------------------ 2. 强制卸载

    private static void unload(MinecraftServer server, List<Target> targets, Result result) {
        // 先按维度登记「本轮接管的区块」：ticket 系统仍然认为它们该加载，会通过邻居传播把 holder
        // 重建出来（进而拉起 worldgen 任务、把区块钉住），这段时间必须挡住重建。
        Map<ServerLevel, LongSet> guards = new LinkedHashMap<>();
        for (Target target : targets) {
            guards.computeIfAbsent(target.level, level -> new LongOpenHashSet()).add(target.pos.toLong());
        }
        guards.forEach((level, positions) -> {
            long[] sorted = positions.toLongArray();
            Arrays.sort(sorted);
            Rewind.beginUnloadGuard(level.getChunkSource().chunkMap, sorted);
        });

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
        int lastPending = Integer.MAX_VALUE;
        int stalled = 0;
        for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
            int pending = pumpUnloadsOnce(server);
            if (pending == 0) {
                return;
            }
            if (pending >= lastPending) {
                // 没进展就别空转了：`processUnloads` 收不掉的 holder 是那些还被加载流水线钉着的，
                // 等下去也不会自己好。
                if (++stalled > STALL_ROUNDS) {
                    Rewind.LOGGER.warn(
                            "Rewind: in-place rollback gave up draining chunk unloads ({} still pending, {} still tracked)",
                            pending, countStillTracked(server));
                    return;
                }
                // 只有「这一轮没推进」时才让出时间给后台线程：还在推进就接着推。
                // 原来每轮无条件 park 200µs，几十轮下来光等待就十几毫秒。
                LockSupport.parkNanos(PARK_NANOS);
            } else {
                stalled = 0;
            }
            lastPending = pending;
        }
        Rewind.LOGGER.warn("Rewind: in-place rollback could not fully drain chunk unloads ({} still pending)", lastPending);
    }

    private static int pumpUnloadsOnce(MinecraftServer server) {
        int pending = 0;
        for (ServerLevel level : server.getAllLevels()) {
            ServerChunkCache source = level.getChunkSource();
            ChunkMapAccess access = mapAccess(source.chunkMap);
            access.rewind$processUnloads(() -> true);
            sourceAccess(source).rewind$runDistanceManagerUpdates();
            access.rewind$processUnloads(() -> true);
            pending += access.rewind$toDrop().size()
                    + access.rewind$pendingUnloads().size()
                    + access.rewind$unloadQueue().size();
        }
        return pending;
    }

    /**
     * 1.21.1 那份实现数的是 {@code ChunkHolder.getGenerationRefCount() != 0} 的 holder 数（「被生成任务
     * 钉住的那几个」）。1.20.1 的 {@code ChunkHolder} 没有这个字段，所以这里退一步数「还在 toDrop 名单里、
     * 并且仍然挂在 updatingChunkMap 上」的 holder——同样表达「没卸干净的还剩几个」，只是分不出原因。
     */
    private static int countStillTracked(MinecraftServer server) {
        int tracked = 0;
        for (ServerLevel level : server.getAllLevels()) {
            ChunkMapAccess access = mapAccess(level.getChunkSource().chunkMap);
            for (long pos : access.rewind$toDrop()) {
                if (access.rewind$getUpdatingChunkIfPresent(pos) != null) {
                    tracked++;
                }
            }
        }
        return tracked;
    }

    private static void tickEntityManagers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            levelAccess(level).rewind$entityManager().tick();
        }
    }

    /**
     * 把实体区块的卸载彻底排空。
     *
     * <p>卸载请求落在 {@code chunksToUnload} 上，只有 {@code entityManager.tick()} 会推进它；而
     * {@code storeChunkSections} 在实体还没读回来（status 不是 LOADED）时会直接放弃、留到下一 tick。
     * 所以单跑一次 tick 不够——那些被推迟的卸载会落到回滚窗口之外，把「改世界之后」的实体列表
     * 写回刚还原的文件。这里一直泵到没有待卸载的区块为止。
     *
     * <p>但**连续多轮没进展就放弃**，和区块卸载那条一样：区块本身没卸掉（被 worldgen 钉住）时，
     * 实体这边也就没得卸，再等下去只是空转——`PARK_NANOS` 写的是 200µs，Windows 上每次 park 实际
     * 要睡 1ms 出头，跑满 {@value #MAX_DRAIN_ROUNDS} 轮就是几百毫秒白等（实测 529ms）。
     */
    private static void drainEntityUnloads(MinecraftServer server) {
        int lastPending = Integer.MAX_VALUE;
        int stalled = 0;
        for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
            int pending = 0;
            for (ServerLevel level : server.getAllLevels()) {
                levelAccess(level).rewind$entityManager().tick();
                pending += entityManagerAccess(level).rewind$chunksToUnload().size();
            }
            if (pending == 0) {
                return;
            }
            // 和区块卸载那条一样：还在推进就接着推，只有「这一轮没推进」时才让出时间。
            if (pending >= lastPending) {
                if (++stalled > STALL_ROUNDS) {
                    Rewind.LOGGER.warn(
                            "Rewind: in-place rollback gave up draining entity-chunk unloads ({} still pending)",
                            pending);
                    return;
                }
                LockSupport.parkNanos(PARK_NANOS);
            } else {
                stalled = 0;
            }
            lastPending = pending;
        }
        Rewind.LOGGER.warn("Rewind: in-place rollback could not fully drain entity-chunk unloads");
    }

    /**
     * 作废按区块缓存的派生数据，否则重载回来的区块会带着「改世界之后」的缓存。
     *
     * <ul>
     *   <li>实体存储的 {@code emptyChunks}：区块被卸载时如果当时没有可保存的实体，这个位置会被记成
     *       「空区块」，之后 {@code loadEntities} 直接返回空——快照里本来有实体的区块就再也读不出来。</li>
     *   <li>POI 的分段缓存：床位 / 工作站这类兴趣点按 16³ 分段缓存在内存里，不改就会留下幻影 POI。
     *       1.20.1 上作废的方式是删 {@code SectionStorage} 的 {@code storage} 与 {@code dirty} 两个
     *       section 键（见类注释）。</li>
     * </ul>
     */
    private static void invalidateCaches(MinecraftServer server, List<Target> targets) {
        for (ServerLevel level : server.getAllLevels()) {
            Object permanent = entityManagerAccess(level).rewind$permanentStorage();
            LongSet emptyChunks = permanent instanceof EntityStorage storage
                    ? entityStorageAccess(storage).rewind$emptyChunks()
                    : null;
            SectionStorageAccess poi = sectionStorageAccess(level.getChunkSource().getPoiManager());
            for (Target target : targets) {
                if (target.level != level) {
                    continue;
                }
                if (emptyChunks != null) {
                    emptyChunks.remove(target.pos.toLong());
                }
                for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
                    long key = SectionPos.asLong(target.pos.x, sectionY, target.pos.z);
                    poi.rewind$sectionCache().remove(key);
                    poi.rewind$dirtySections().remove(key);
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
            closed += closeWorker(chunkWorker(source));
            closed += closeWorker(poiWorker(source.getPoiManager()));
            closed += closeWorker(entityWorker(level));
        }
        return closed;
    }

    private static IOWorker chunkWorker(ServerChunkCache source) {
        return chunkStorageAccess(source.chunkMap).rewind$worker();
    }

    private static IOWorker poiWorker(PoiManager poi) {
        return sectionStorageAccess(poi).rewind$worker();
    }

    /** 实体存储的 worker；1.20.1 的 {@code EntityStorage} 不继承 {@code ChunkStorage}，自己持有它。 */
    private static IOWorker entityWorker(ServerLevel level) {
        Object permanent = entityManagerAccess(level).rewind$permanentStorage();
        return permanent instanceof EntityStorage storage ? entityStorageAccess(storage).rewind$worker() : null;
    }

    /** 排空三条存储链（区块 / POI / 实体）排队中的写入，不 fsync。 */
    private static void flushWorkers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerChunkCache source = level.getChunkSource();
            drainWorker(chunkWorker(source));
            drainWorker(poiWorker(source.getPoiManager()));
            drainWorker(entityWorker(level));
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

    /** 重建 holder 并推进流水线：让被卸载掉的区块重新排队，从（已还原的）磁盘读回来。 */
    private static void recreateHolders(MinecraftServer server, List<Target> targets, Result result) {
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
    }

    /** 把重建好的区块读回来：视野内的同步读（这也是原版把新数据推给客户端的触发点），视野外交给原版流水线。 */
    private static void loadChunks(MinecraftServer server, List<Target> targets, Result result) {
        long startedNanos = System.nanoTime();
        // 缓存里可能还留着「重建之前」的 ChunkAccess；每个维度清一次就够。
        // 原来把它放在循环里逐区块清，等于每读回来一个就把刚读的那个踢出缓存，纯浪费。
        Set<ServerLevel> cleared = new HashSet<>();
        // 视野半径：服务端视距 + 1 个区块，留一圈余量，免得玩家一挪就正好缺块
        int syncRadius = server.getPlayerList().getViewDistance() + 1;
        boolean onlyNearPlayers = RollbackSettings.syncChunksNearPlayer();
        for (Target target : targets) {
            if (!target.unloaded) {
                continue;
            }
            ServerChunkCache source = target.level.getChunkSource();
            if (cleared.add(target.level)) {
                sourceAccess(source).rewind$clearCache();
            }
            ChunkMapAccess access = mapAccess(source.chunkMap);
            // 区块类型缓存也要作废：不然异步读回来的区块可能沿用「改世界之后」的那份类型
            access.rewind$chunkTypeCache().remove(target.pos.toLong());
            if (onlyNearPlayers && !nearAnyPlayer(server, target.level, target.pos, syncRadius)) {
                // 不在任何玩家视野里：不在这里同步等它。ticket 已经还原，原版流水线会按需异步读回来。
                result.deferredChunks++;
                continue;
            }
            try {
                source.getChunk(target.pos.x, target.pos.z, ChunkStatus.FULL, true);
                result.reloadedChunks++;
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload chunk {} in {}",
                        target.pos, target.level.dimension().location(), t);
            }
        }
        result.loadMs = millisSince(startedNanos);

        // 实体重新读盘走的是后台队列，给它几轮时间落地（晚一两 tick 也无所谓，过渡还在盖着）。
        // 没有待卸载的区块时没什么可等；有的话也只在「这一轮没推进」时才让出时间——
        // 原来 40 轮无条件 park 200µs（光等待 8ms 起步），还每轮对三个维度各 tick 一次。
        long settleNanos = System.nanoTime();
        int lastPending = Integer.MAX_VALUE;
        for (int round = 0; round < ENTITY_SETTLE_ROUNDS; round++) {
            tickEntityManagers(server);
            int pending = 0;
            for (ServerLevel level : server.getAllLevels()) {
                pending += entityManagerAccess(level).rewind$chunksToUnload().size();
            }
            if (pending == 0 && round >= 1) {
                // 至少 tick 两轮，保留原来「晚一两 tick」的语义
                break;
            }
            if (pending >= lastPending) {
                LockSupport.parkNanos(PARK_NANOS);
            }
            lastPending = pending;
        }
        result.settleMs = millisSince(settleNanos);
    }

    /** 这个区块在不在任何一个玩家的视野里（按区块的切比雪夫距离算）。 */
    private static boolean nearAnyPlayer(MinecraftServer server, ServerLevel level, ChunkPos pos, int radius) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel() != level) {
                continue;
            }
            ChunkPos playerChunk = player.chunkPosition();
            if (Math.abs(playerChunk.x - pos.x) <= radius && Math.abs(playerChunk.z - pos.z) <= radius) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 5. 内存状态

    /**
     * 玩家 / 时间天气。位置见 {@link #run} 里的注释：必须排在 holder 重建之后、区块成批重发之前。
     */
    private static void restoreWorldAndPlayers(MinecraftServer server, Path slotDir, Result result) {
        long stepNanos = System.nanoTime();
        try {
            result.restoredWorldData = restoreWorldData(server, slotDir);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to restore world data in place", t);
        }
        result.stateWorldMs = millisSince(stepNanos);
        stepNanos = System.nanoTime();
        try {
            result.restoredPlayers = restorePlayers(server, slotDir);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to restore players in place", t);
        }
        result.statePlayersMs = millisSince(stepNanos);
    }

    /** 时间 / 天气 / 出生点来自 level.dat；原地回滚不改磁盘上的 level.dat，直接把值写进活着的 WorldData。 */
    private static boolean restoreWorldData(MinecraftServer server, Path slotDir) throws IOException {
        Path file = slotDir.resolve("level.dat");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        CompoundTag data = readNbt(file).getCompound("Data");
        ServerLevel overworld = server.overworld();
        if (overworld.getLevelData() instanceof ServerLevelData levelData) {
            // 游戏刻在 level.dat 里的键是 "Time"，不是 "GameTime"——后者只是 getGameTime() 那个 getter
            // 的名字，磁盘上从来不存在这个键（PrimaryLevelData.createTag 写的是 putLong("Time", ...)）。
            // 读错键会让这个 if 永不成立，回滚就静默不还原世界时间。
            if (data.contains("Time", 99)) {
                levelData.setGameTime(data.getLong("Time"));
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
            restorePlayer(server, player, readNbt(file));
            restored++;
        }
        return restored;
    }

    private static void restorePlayer(MinecraftServer server, ServerPlayer player, CompoundTag tag) {
        // 先把「改世界之后加的 buff」清掉：客户端会收到对应的移除包
        player.removeAllEffects();
        if (!RewindServerConfig.syncRecipeBook()) {
            // 配方书默认不管：ServerPlayer.readAdditionalSaveData 会把 NBT 里的 recipeBook 读回来
            // （ServerRecipeBook.fromNbt 要按名字逐条查配方表），实测这一下就是 150–600ms。
            // 关着时连读都不读——服务端与客户端两边都保持回溯前的状态，等于完全不管这东西。
            tag.remove("recipeBook");
        }
        // 1.20.1 的 ServerPlayer 没有覆写 load()，这里直接走 Entity.load → Player.readAdditionalSaveData，
        // 物品栏 / 属性 / 经验 / 效果都在那一条链里读回来
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

        // 快捷栏选中槽位也得推给客户端：load() 只改了服务端这一份，客户端还停在它自己的槽位上。
        // 物品栏内容是一起回滚的，两边槽位不一致就直接表现为「客户端拿着 A、服务端用的是 B」。
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));

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
        // 配方书默认不管（RewindServerConfig.syncRecipeBook()，默认 false）：重发整份配方书在大整合包里
        // 可能几百毫秒，而它只影响客户端那份显示——服务端那份已经随 playerdata 一起回滚了。
        if (RewindServerConfig.syncRecipeBook()) {
            player.getRecipeBook().sendInitialRecipeBook(player);
        }
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
     * 清掉，再用 {@code ScoreboardSaveData}（1.20.1 里承载「活着的记分板 ↔ 磁盘」的那个 SavedData）
     * 把快照内容灌回同一个记分板对象；老的那个 save data 实例仍然持有脏标记回调，读的是同一个记分板，
     * 下一次自动保存会写回正确内容。
     *
     * <p>袭击：{@code ServerLevel.raids} 是 final 字段，直接换实例。
     */
    private static void restoreSavedData(MinecraftServer server, Path slotDir) throws IOException {
        Path file = slotDir.resolve("data").resolve("scoreboard.dat");
        if (Files.isRegularFile(file)) {
            CompoundTag data = readNbt(file).getCompound("data");
            ServerScoreboard scoreboard = server.getScoreboard();
            for (Objective objective : new ArrayList<>(scoreboard.getObjectives())) {
                scoreboard.removeObjective(objective);
            }
            for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
                scoreboard.removePlayerTeam(team);
            }
            // 1.20.1 没有 Scoreboard.dataFactory()：原版建服时是
            // computeIfAbsent(scoreboard::createData, scoreboard::createData, "scoreboard")，
            // 这里做同一件事，只是不往 dirtyListeners 里再挂一个监听器（见类注释）
            new ScoreboardSaveData(scoreboard).load(data);
        }

        for (ServerLevel level : server.getAllLevels()) {
            String fileId = Raids.getFileId(level.dimensionTypeRegistration());
            DimensionDataStorage levelStorage = level.getDataStorage();
            cacheOf(levelStorage).remove(fileId);
            // 1.20.1 没有 Raids.factory(level)，用原版建服时那条工厂（Raids.load + new Raids）
            levelAccess(level).rewind$setRaids(
                    levelStorage.<Raids>computeIfAbsent(tag -> Raids.load(level, tag), () -> new Raids(level), fileId));
        }
    }

    /** 1.20.1 的 {@code NbtIo} 只有 {@code readCompressed(File)} 一种重载（没有 Path 版）。 */
    private static CompoundTag readNbt(Path file) throws IOException {
        return NbtIo.readCompressed(file.toFile());
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

    private static IOWorkerAccess ioWorkerAccess(IOWorker worker) {
        return (IOWorkerAccess) (Object) worker;
    }

    private static RegionFileStorageAccess regionFileStorageAccess(RegionFileStorage storage) {
        return (RegionFileStorageAccess) (Object) storage;
    }

    private static DimensionDataStorageAccess dimensionStorageAccess(DimensionDataStorage storage) {
        return (DimensionDataStorageAccess) (Object) storage;
    }
}
