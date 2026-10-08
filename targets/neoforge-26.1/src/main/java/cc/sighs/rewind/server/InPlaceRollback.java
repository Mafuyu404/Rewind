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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import cc.sighs.mixin.RollbackAccessMixins.AttachmentHolderAccess;
import cc.sighs.mixin.RollbackAccessMixins.ChunkMapAccess;
import cc.sighs.mixin.RollbackAccessMixins.DistanceManagerAccess;
import cc.sighs.mixin.RollbackAccessMixins.EntitySectionManagerAccess;
import cc.sighs.mixin.RollbackAccessMixins.EntityStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.GenerationChunkHolderAccess;
import cc.sighs.mixin.RollbackAccessMixins.IOWorkerAccess;
import cc.sighs.mixin.RollbackAccessMixins.RegionFileStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.SavedDataStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.SectionStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.ServerChunkCacheAccess;
import cc.sighs.mixin.RollbackAccessMixins.ServerLevelAccess;
import cc.sighs.mixin.RollbackAccessMixins.SimpleRegionStorageAccess;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.common.compat.SophisticatedCoreCompat;
import cc.sighs.rewind.common.config.RollbackSettings;
import cc.sighs.rewind.common.store.SnapshotBlockIo;
import cc.sighs.rewind.snapshot.SnapshotBlockStore;
import cc.sighs.rewind.snapshot.SnapshotBlocks;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotManifest;
import cc.sighs.rewind.snapshot.SnapshotMirror;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.Stopwatches;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.gamerules.GameRuleMap;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.timers.TimerQueue;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreboardSaveData;
import net.neoforged.neoforge.attachment.LevelAttachmentsSavedData;

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
 *   <li><b>内存状态</b>：玩家 / 世界时间单独还原；每个维度与服务器级的存档数据缓存里，**能重新读盘**的条目
 *       整体作废、从（已还原的）磁盘重读，而**被长生命周期对象用字段强引用**的那些故意保留（见
 *       {@link #PROTECTED_SAVED_DATA}）；其中袭击、记分板、天气、等级数据附件显式重建。</li>
 * </ol>
 *
 * <p>已知边界（比「关世界再重开」那条路少了什么）：**磁盘**与「每次用时 {@code SavedDataStorage.get}
 * 取一次」的存档数据都能回滚（含模组写在 {@code <维度>/data/<namespace>/<path>.dat} 里的那些）；
 * 但**模组自己缓存的解码结果**不归世界生命周期管，同一进程里还会被复用（典型：精妙核心的包装器缓存，
 * 见 {@link SophisticatedCoreCompat}），也没有通用手段能替它清掉。
 *
 * <p>另一条取舍写在这里：26.1 有一批原版存档数据被长生命周期对象用字段强引用（游戏规则、命令存储、
 * 流浪商人、区块票据、随机序列、世界时钟、自定义 boss 条、计划事件、秒表、末地龙战），而
 * {@code SavedDataStorage.scheduleSave()} 只遍历缓存——所以它们**故意不清缓存**（见
 * {@link #PROTECTED_SAVED_DATA}）：内容不参与回滚、但会继续正常落盘。真正回到存档点内容的只有能重新
 * 读盘的那几类——袭击、记分板、天气、等级数据附件。装了这类模组、数据看起来没回滚时，把 COMMON 配置
 * {@code rollback.inPlaceRollback} 关掉，退回「关世界 → 覆盖 → 重开」那条路——服务端对象全部重建，
 * 模组跟着世界重载走一遍。
 *
 * <h2>26.1 的实现差异（相对 {@code targets/neoforge-1.21.1}）</h2>
 * <ul>
 *   <li>{@code DimensionDataStorage} → {@code SavedDataStorage}，缓存键由 {@code String} 变成
 *       {@code SavedDataType<?>}；{@code Raids.getFileId()/factory()} 变成了 {@code Raids.TYPE}。</li>
 *   <li>记分板：{@code ServerScoreboard.dataFactory().deserializer()} 没了，改成
 *       {@code ServerScoreboard.load(ScoreboardSaveData.Packed)}，数据从
 *       {@code ScoreboardSaveData.Packed.CODEC} 反序列化。</li>
 *   <li>玩家：{@code player.load(CompoundTag)} → {@code player.load(ValueInput)}（NBT 走
 *       {@code TagValueInput}），维度从 {@code ServerPlayer.SavedPosition} 读；
 *       {@code PlayerList.placeNewPlayer} 的登录流程就是这么做的。</li>
 *   <li>时间 / 出生点：26.1 的 level.dat 里时间叫 {@code Time}（没有 {@code DayTime} 了），出生点是一个
 *       {@code LevelData.RespawnData} 编解码的 {@code spawn} 子标签。</li>
 *   <li>天气：不再是 level.dat 的字段，而是服务器级 {@code WeatherData} 这个 SavedData
 *       （{@code <存档>/data/minecraft/weather.dat}）。</li>
 *   <li>存档数据的位置：26.1 的 {@code SavedDataStorage} 按 {@code Identifier} 落成
 *       {@code <dataFolder>/<namespace>/<path>.dat}，所以这里不再硬编码 {@code data/scoreboard.dat}，
 *       一律「作废缓存 → 让存储层从已还原的磁盘重新读」，路径长什么样都无所谓。</li>
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
        // 回滚窗口先关掉，而且要在做任何别的事之前。RewindApi 在调进来之前就把它打开了，而窗口开着的时候
        // RegionFileStorage.write 会被整段取消（见 RollbackDiscardMixins）。26.1 的 ChunkMap.saveChunksEagerly
        // 会先把区块的 unsaved 标记清掉、再异步落盘，如果这次异步写正好落在这个窗口里，它就被静默丢掉：
        // 内存里「已经不脏」、磁盘上「也没变」，这个区块从两个判据里同时消失，回滚当它没改过——表现为
        // 「世界回滚成功了，但某个区块原样留在改后的状态」。
        // 所以窗口只留到真正开始覆盖文件之前（下面的 beginDiscard 重新打开），中间这一段
        // （读槽位清单 / 追平写入 / 找受影响区块）必须让写入正常落地。
        Rewind.endDiscard();
        Path slotDir = SnapshotLayout.slotDir(worldRoot, slot);
        SnapshotManifest manifest = SnapshotManifest.load(SnapshotLayout.manifestFile(worldRoot, slot));

        try {
            // 先把「已经排队但还没落盘」的写入落下去：这类区块在内存里已经不是 unsaved、磁盘上又还没变，
            // 两个判据都会漏掉它。这段时间世界不会 tick（我们就在服务端线程上），所以不会再有新的写入插进来。
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

            // 玩家 / 世界时间必须排在这里：holder 已经重建、但区块还没成批重发。
            // 位置包要排在区块包前面，否则客户端得先处理完上百个区块包才会回 ack，而服务端在收到 ack
            // 之前是关着右键交互的（handleUseItemOn 里那道 awaitingPositionFromClient 的门）。
            // 也不能更早：player.load() 会走 Entity.setPosRaw，NeoForge 在那里会 level.getChunk() 请求
            // 区块，holder 不在就会直接 NPE。
            stepNanos = System.nanoTime();
            restoreWorldAndPlayers(server, slotDir, result);
            result.stateMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            loadChunks(server, targets, result);
            result.reloadMs += millisSince(stepNanos);

            try {
                // 传入 mirror 是为了把这次回滚覆盖到的存档数据文件打进日志（见 logRestoredSavedData）
                restoreSavedData(server, mirror);
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
            // 26.1 删掉了 ChunkMap.getChunks()，直接读 visibleChunkMap（它原本就是那个方法的数据源）
            for (ChunkHolder holder : mapAccess(chunkMap).rewind$visibleChunks().values()) {
                if (holder.getTicketLevel() > ChunkLevel.MAX_LEVEL) {
                    // 已经在卸载路上：内存里的内容马上就会自己丢掉，磁盘上会被快照覆盖，不用管
                    continue;
                }
                if (!(holder.getLatestChunk() instanceof LevelChunk chunk)) {
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
        return sections.getExistingSectionsInChunk(pos.pack())
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
        return header[(pos.x() & 31) + (pos.z() & 31) * 32] != 0;
    }

    private static String regionFileName(ChunkPos pos) {
        return "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
    }

    /** 这个维度里所有「按区块存 region 文件」的存储目录：区块 / 实体 / POI。 */
    private static List<Path> liveRegionDirs(ServerLevel level) {
        ServerChunkCache source = level.getChunkSource();
        List<Path> dirs = new ArrayList<>(3);
        // 26.1：ChunkMap 自己继承了 SimpleRegionStorage，worker 字段就在那里
        addFolder(dirs, simpleRegionStorageAccess(source.chunkMap).rewind$worker());
        Object permanent = entityManagerAccess(level).rewind$permanentStorage();
        if (permanent instanceof EntityStorage storage) {
            addFolder(dirs, workerOf(entityStorageAccess(storage).rewind$simpleRegionStorage()));
        }
        addFolder(dirs, workerOf(sectionStorageAccess(source.getPoiManager()).rewind$simpleRegionStorage()));
        return dirs;
    }

    private static void addFolder(List<Path> dirs, IOWorker worker) {
        if (worker == null) {
            return;
        }
        dirs.add(regionFileStorageAccess(ioWorkerAccess(worker).rewind$storage()).rewind$folder());
    }

    private static IOWorker workerOf(SimpleRegionStorage storage) {
        return storage == null ? null : simpleRegionStorageAccess(storage).rewind$worker();
    }

    /** 任意一个存储里这个区块的偏移/时间戳与快照不一致就算变过。 */
    private static boolean regionChunkChanged(SnapshotBlockStore store, Path slotDir, SnapshotBlocks blocks,
            List<Path> liveDirs, List<Path> snapshotDirs, ChunkPos pos, Map<Path, int[]> cache) throws IOException {
        String name = regionFileName(pos);
        int index = (pos.x() & 31) + (pos.z() & 31) * 32;
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
        // 重建出来（进而拉起 worldgen 任务、把 refCount 钉住），这段时间必须挡住重建。
        Map<ServerLevel, LongSet> guards = new LinkedHashMap<>();
        for (Target target : targets) {
            guards.computeIfAbsent(target.level, level -> new LongOpenHashSet()).add(target.pos.pack());
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
            long key = target.pos.pack();
            var holder = chunkMap.getUpdatingChunkIfPresent(key);
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
                // 没进展就别空转了：`processUnloads` 会跳过 generationRefCount 非 0 的 holder，
                // 那说明它被某个 worldgen 任务钉着，等下去也不会自己好。
                if (++stalled > STALL_ROUNDS) {
                    Rewind.LOGGER.warn(
                            "Rewind: in-place rollback gave up draining chunk unloads ({} still pending, {} pinned by generation tasks)",
                            pending, countPinnedByGeneration(server));
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

    private static int countPinnedByGeneration(MinecraftServer server) {
        int pinned = 0;
        for (ServerLevel level : server.getAllLevels()) {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            ChunkMapAccess access = mapAccess(chunkMap);
            for (long pos : access.rewind$toDrop()) {
                ChunkHolder holder = chunkMap.getUpdatingChunkIfPresent(pos);
                // 26.1：这一计数移到了父类 GenerationChunkHolder 上，没有公开 getter
                if (holder != null && generationAccess(holder).rewind$generationRefCount().get() != 0) {
                    pinned++;
                }
            }
        }
        return pinned;
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
     *       26.1 的 {@code SectionStorage.remove(long)} 换成了 {@code remove(ChunkPos)}，而且它会把整列
     *       分段（以及 {@code loadedChunks} 标记）一次清掉，正是这里要的粒度。</li>
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
                    emptyChunks.remove(target.pos.pack());
                }
                poi.remove(target.pos);
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
            closed += closeWorker(simpleRegionStorageAccess(source.chunkMap).rewind$worker());
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
            drainWorker(simpleRegionStorageAccess(source.chunkMap).rewind$worker());
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

    /** 重建 holder 并推进流水线：让被卸载掉的区块重新排队，从（已还原的）磁盘读回来。 */
    private static void recreateHolders(MinecraftServer server, List<Target> targets, Result result) {
        for (Target target : targets) {
            ChunkMap chunkMap = target.level.getChunkSource().chunkMap;
            ChunkMapAccess access = mapAccess(chunkMap);
            long key = target.pos.pack();
            if (!target.unloaded) {
                // 卸载时被跳过了（还有生成任务在飞）：保持原样，这个区块不会回滚，记一笔
                result.stuckChunks++;
                continue;
            }
            var holder = chunkMap.getUpdatingChunkIfPresent(key);
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
            access.rewind$chunkTypeCache().remove(target.pos.pack());
            if (onlyNearPlayers && !nearAnyPlayer(server, target.level, target.pos, syncRadius)) {
                // 不在任何玩家视野里：不在这里同步等它。ticket 已经还原，原版流水线会按需异步读回来。
                result.deferredChunks++;
                continue;
            }
            try {
                source.getChunk(target.pos.x(), target.pos.z(), ChunkStatus.FULL, true);
                result.reloadedChunks++;
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload chunk {} in {}",
                        target.pos, target.level.dimension().identifier(), t);
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
            if (player.level() != level) {
                continue;
            }
            ChunkPos playerChunk = player.chunkPosition();
            if (Math.abs(playerChunk.x() - pos.x()) <= radius && Math.abs(playerChunk.z() - pos.z()) <= radius) {
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

    /**
     * 世界时间 / 出生点来自 level.dat。这里不改磁盘上的任何文件，直接把快照值写进活着的
     * {@code WorldData}。
     *
     * <p>天气不在这里：26.1 把它挪进了服务器级 {@code WeatherData} SavedData，由 {@link #restoreWeather}
     * 在存档数据重建那一步统一处理（必须排在那一步的缓存作废之后，否则刚重建好的条目会被清掉）。
     */
    private static boolean restoreWorldData(MinecraftServer server, Path slotDir) throws IOException {
        boolean restored = false;
        Path file = slotDir.resolve("level.dat");
        if (Files.isRegularFile(file)) {
            CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
            // 用 WorldData.overworldData() 而不是 level.getLevelData()：非主世界拿到的是
            // DerivedLevelData，它的 setGameTime 是个空实现
            ServerLevelData levelData = server.getWorldData().overworldData();
            if (data.contains("Time")) {
                // 26.1 删掉了 DayTime，世界时间就是 level.dat 里的 Time
                levelData.setGameTime(data.getLongOr("Time", levelData.getGameTime()));
            }
            Tag spawn = data.get("spawn");
            if (spawn != null) {
                LevelData.RespawnData respawn = LevelData.RespawnData.CODEC
                        .parse(NbtOps.INSTANCE, spawn)
                        .result()
                        .orElse(null);
                if (respawn != null) {
                    levelData.setSpawn(respawn);
                }
            }
            restored = true;
        }
        // 26.1：forceTimeSynchronization() 改名成 forceGameTimeSynchronization()，顺带带上 clocks
        server.forceGameTimeSynchronization();
        return restored;
    }

    /**
     * 天气：作废服务器级 {@code SavedDataStorage} 里的 {@code WeatherData} 缓存，让它从（已被快照
     * 覆盖的）磁盘重新读，再把值灌回活着的实例。
     *
     * <p>不硬编码 {@code data/weather.dat}：26.1 的 SavedData 落盘路径是
     * {@code <dataFolder>/<namespace>/<path>.dat}，走存储层就与路径规则无关。
     *
     * <p>{@code MinecraftServer.weatherData} 是 final 字段，读（{@code getWeatherData}）与每一 tick 的
     * 天气推进都用那个活着的实例，所以内容灌回去之后还得把**那个实例**登记进缓存——只 {@code get} 的话
     * 缓存里放的是刚从磁盘读出来的新实例，活着的那个反而没人遍历，之后天气变化就再也不落盘。
     *
     * <p>调用点：{@link #restoreSavedData} 清空缓存之后。
     */
    private static void restoreWeather(MinecraftServer server) {
        SavedDataStorage storage = server.getDataStorage();
        WeatherData saved = storage.get(WeatherData.TYPE);
        if (saved == null) {
            return;
        }
        WeatherData live = server.getWeatherData();
        live.setClearWeatherTime(saved.getClearWeatherTime());
        live.setRainTime(saved.getRainTime());
        live.setThunderTime(saved.getThunderTime());
        live.setRaining(saved.isRaining());
        live.setThundering(saved.isThundering());
        cacheOf(storage).put(WeatherData.TYPE, Optional.of((SavedData) live));
    }

    /**
     * 玩家状态从快照的玩家数据文件读回来；原版没有「重读玩家」的入口，只能 load 之后手动补齐客户端同步。
     *
     * <p>26.1 把玩家数据从 {@code playerdata/<uuid>.dat} 搬到了 {@code players/data/<uuid>.dat}
     * （{@code LevelResource.PLAYER_DATA_DIR}）。这里不写死目录名：按原版那套算出玩家数据目录，
     * 再换算成快照槽位里的相对路径，免得版本一挪位置就整段静默失效（{@code restored=0}，
     * 表现就是「世界回去了、玩家自己的位置和背包没回去」）。
     */
    private static int restorePlayers(MinecraftServer server, Path slotDir) throws IOException {
        int restored = 0;
        Path worldRoot = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        Path relativePlayerData = worldRoot.relativize(server.getWorldPath(LevelResource.PLAYER_DATA_DIR));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Path file = slotDir.resolve(relativePlayerData).resolve(player.getUUID() + ".dat");
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
        if (!RewindServerConfig.syncRecipeBook()) {
            // 配方书默认不管：ServerPlayer.readAdditionalSaveData 会把 NBT 里的 recipeBook 读回来
            // （ServerRecipeBook.fromNbt 要按名字逐条查配方表），实测这一下就是 150–600ms。
            // 关着时连读都不读——服务端与客户端两边都保持回溯前的状态，等于完全不管这东西。
            // 26.1 走 ValueInput，摘除必须在 TagValueInput.create 之前对 CompoundTag 做。
            tag.remove("recipeBook");
        }

        // 26.1：player.load(CompoundTag) 换成了 Entity.load(ValueInput)；维度从 SavedPosition 读
        // （原版登录流程 PrepareSpawnTask 也是这么做的），坐标 / 朝向由 load 自己从 Pos/Rotation 读
        ServerPlayer.SavedPosition savedPosition = ServerPlayer.SavedPosition.MAP_CODEC.codec()
                .parse(NbtOps.INSTANCE, tag)
                .result()
                .orElse(ServerPlayer.SavedPosition.EMPTY);
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, player.level().registryAccess(), tag);
        player.load(input);
        // 游戏模式不用单独写：ServerPlayer.readAdditionalSaveData 会从 playerGameType 读回来

        // 维度可能与建点时不在一起：那种情况要走跨维度传送，否则客户端的世界不会跟着换
        ServerLevel savedLevel = savedPosition.dimension().map(server::getLevel).orElse(null);
        Vec3 position = player.position();
        if (savedLevel != null && savedLevel != player.level()) {
            player.teleportTo(savedLevel, position.x, position.y, position.z, Set.of(), player.getYRot(), player.getXRot(), false);
        } else {
            player.connection.teleport(position.x, position.y, position.z, player.getYRot(), player.getXRot());
        }

        // 快捷栏选中槽位也得推给客户端：load() 只改了服务端这一份，客户端还停在它自己的槽位上。
        // 物品栏内容是一起回滚的，两边槽位不一致就直接表现为「客户端拿着 A、服务端用的是 B」。
        // 26.1：ClientboundSetCarriedItemPacket → ClientboundSetHeldSlotPacket，
        // 读的也从 Inventory.selected 字段换成了 getSelectedSlot()
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));

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

    /**
     * 把「内存里的存档数据」拉回存档点那一刻。
     *
     * <p>做法是**排除法**：只把不在 {@link #PROTECTED_SAVED_DATA} 里的缓存条目作废掉，之后任何
     * {@code get} / {@code computeIfAbsent} 都会从磁盘重读（那些文件在几步之前刚被快照覆盖过）。
     * 这样所有「每次用时取一次」的存档数据——**包括模组写在
     * {@code <维度>/data/<namespace>/<path>.dat} 里的那些**——都会自动拿到快照内容，不需要认识任何具体模组；
     * 而被长生命周期对象用字段强引用的条目原样留着，继续正常落盘（内容不参与回滚，见类注释的「已知边界」）。
     *
     * <p>作废之后还要显式重建**能重新读盘**的那几类（它们也正是不能进 {@link #PROTECTED_SAVED_DATA} 的）：
     *
     * <ul>
     *   <li><b>袭击</b>：{@code ServerLevel.raids} 是 final 字段，直接换实例；用它的 {@code SavedDataType}
     *       走一次 {@code computeIfAbsent}，就会从（已还原的）磁盘重新读。</li>
     *   <li><b>等级数据附件</b>（NeoForge 的 {@code neoforge:data_attachments}）：那个 SavedData 只是个
     *       壳子，真数据挂在 {@code Level} 自己的附件表上，由它的反序列化器灌回去；而反序列化是「按 key 覆盖」
     *       而不是替换，所以要先清空那张表，快照里没有的附件才不会留下来。</li>
     *   <li><b>记分板</b>：26.1 里它挂在**服务器级**存储上（{@code server.getDataStorage()}），
     *       {@code MinecraftServer.saveAllChunks} 也是用 {@code computeIfAbsent(ScoreboardSaveData.TYPE)}
     *       拿它——作废缓存会连它一起摘掉，所以必须重建，否则 {@code SavedDataStorage.scheduleSave()} 再也
     *       遍历不到它，scoreboard.dat 从此不再落盘。{@code storage.get} 既读出快照内容、又把条目登记回去。</li>
     *   <li><b>天气</b>：26.1 把天气从 level.dat 搬成了服务器级 {@code WeatherData}，见
     *       {@link #restoreWeather}。必须排在缓存作废之后，否则刚重建的条目会被清掉。</li>
     * </ul>
     *
     * <p>最后通知模组侧丢掉它们自己缓存的解码结果（见 {@link SophisticatedCoreCompat}）。
     */
    private static void restoreSavedData(MinecraftServer server, SnapshotMirror.Result mirror) {
        // 1. 作废每个维度与服务器级那一份里「能重新读盘」的存档数据缓存
        for (ServerLevel level : server.getAllLevels()) {
            clearReloadable(level.getDataStorage());
        }
        clearReloadable(server.getDataStorage());

        // 2. 重建能被重新读盘的那几个
        for (ServerLevel level : server.getAllLevels()) {
            SavedDataStorage storage = level.getDataStorage();
            levelAccess(level).rewind$setRaids(storage.computeIfAbsent(Raids.TYPE));
            clearLevelAttachments(level);
            LevelAttachmentsSavedData.init(level);
        }
        restoreScoreboard(server);
        restoreWeather(server);

        logRestoredSavedData(mirror.files);

        // 3. 模组自己缓存的解码结果不归世界生命周期管，只能单独通知它们丢掉
        SophisticatedCoreCompat.clearCaches();
    }

    /**
     * 摘掉 {@code storage} 缓存里不属于 {@link #PROTECTED_SAVED_DATA} 的条目。
     *
     * <p>只有「能重新读盘」的条目才能摘：{@code scheduleSave()} 只遍历缓存，受保护的那些被长生命周期对象
     * 用字段强引用、摘掉就再也没人登记回去，那个 {@code .dat} 从此不再落盘。
     */
    private static void clearReloadable(SavedDataStorage storage) {
        cacheOf(storage).keySet().removeIf(type -> !isProtectedSavedData(type));
    }

    /** 清掉 {@code Level} 内存里那张附件表（反序列化器只按 key 覆盖，不清的话快照里没有的附件会留下来）。 */
    private static void clearLevelAttachments(ServerLevel level) {
        Map<?, ?> attachments = ((AttachmentHolderAccess) (Object) level).rewind$attachments();
        if (attachments != null) {
            attachments.clear();
        }
    }

    /**
     * 记分板：从（已还原的）服务器级磁盘读回快照内容，灌进活着的 {@code ServerScoreboard}。
     *
     * <p>先清掉现有 objective / team：{@code Scoreboard.addObjective} 对重名会抛异常，直接 load 到活着的
     * 记分板上必然失败（原版日志里那句 {@code Error loading saved data: scoreboard}）。{@code storage.get}
     * 既把快照数据读出来、又把缓存条目重新登记回去（{@code saveAllChunks} 随后会用 {@code computeIfAbsent}
     * 拿同一个条目），所以它不会像被摘掉的条目那样再也不落盘。
     */
    private static void restoreScoreboard(MinecraftServer server) {
        ScoreboardSaveData saved = server.getDataStorage().get(ScoreboardSaveData.TYPE);
        if (saved == null) {
            return;
        }
        ServerScoreboard scoreboard = server.getScoreboard();
        for (Objective objective : new ArrayList<>(scoreboard.getObjectives())) {
            scoreboard.removeObjective(objective);
        }
        for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
            scoreboard.removePlayerTeam(team);
        }
        scoreboard.load(saved.getData());
    }

    /**
     * 26.1 原版（含 NeoForge 自己）会写进存档数据目录的那些 id。
     *
     * <p>26.1 的落盘路径是 {@code <dataFolder>/<namespace>/<path>.dat}（{@code SavedDataStorage.getDataFile}
     * + {@code Identifier.resolveAgainst}），所以 id 是 {@code <namespace>:<path>}。不在这份名单里的就算
     * 「模组数据」——原地回滚只能保证它们的**磁盘文件**被还原。
     */
    private static final Set<String> VANILLA_SAVED_DATA = Set.of(
            "minecraft:scoreboard", "minecraft:weather", "minecraft:random_sequences",
            "minecraft:game_rules", "minecraft:world_gen_settings", "minecraft:custom_boss_events",
            "minecraft:scheduled_events", "minecraft:world_clocks", "minecraft:stopwatches",
            "minecraft:maps/last_id", "minecraft:command_storage",
            "minecraft:raids", "minecraft:chunk_tickets", "minecraft:world_border",
            "minecraft:ender_dragon_fight", "minecraft:wandering_trader",
            "neoforge:data_attachments");

    /**
     * 把这次回滚覆盖到的存档数据文件打进日志，遇到模组数据再额外提示一句。
     *
     * <p>这是「原地回滚少了什么」唯一能被看见的线索：模组把世界状态写在这些文件里时，磁盘回滚了、
     * 内存不一定回滚，游戏里就可能出现「文件回去了、数据没回去」。真遇到了就关掉
     * {@code rollback.inPlaceRollback} 走完整重开那条路。
     */
    private static void logRestoredSavedData(List<String> files) {
        List<String> dataFiles = new ArrayList<>();
        Set<String> modIds = new HashSet<>();
        for (String file : files) {
            if (!isSavedDataFile(file)) {
                continue;
            }
            dataFiles.add(file);
            String id = savedDataId(file);
            if (!isVanillaSavedData(id)) {
                modIds.add(id);
            }
        }
        if (dataFiles.isEmpty()) {
            return;
        }
        Rewind.LOGGER.info("Rewind: saved data covered by this rollback: {}", String.join(", ", dataFiles));
        if (!modIds.isEmpty()) {
            Rewind.LOGGER.warn("Rewind: rollback also covers non-vanilla saved data ({}); if a mod's data looks like it "
                            + "did not roll back, disable rollback.inPlaceRollback and retry",
                    String.join(", ", modIds));
        }
    }

    /** {@code data/<namespace>/<path>.dat} 或 {@code <维度>/data/<namespace>/<path>.dat} 才算存档数据文件。 */
    private static boolean isSavedDataFile(String relative) {
        return relative.endsWith(".dat") && (relative.startsWith("data/") || relative.contains("/data/"));
    }

    /**
     * 从归档相对路径反推存档数据 id：{@code data/minecraft/scoreboard.dat} → {@code minecraft:scoreboard}。
     * path 里还可以再带斜杠（地图是 {@code minecraft:maps/<n>}，命令存储是 {@code <namespace>:command_storage}）。
     */
    private static String savedDataId(String relative) {
        String tail;
        if (relative.startsWith("data/")) {
            tail = relative.substring("data/".length());
        } else {
            int marker = relative.indexOf("/data/");
            tail = marker >= 0 ? relative.substring(marker + "/data/".length()) : relative;
        }
        if (tail.endsWith(".dat")) {
            tail = tail.substring(0, tail.length() - ".dat".length());
        }
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash) + ':' + tail.substring(slash + 1);
    }

    private static boolean isVanillaSavedData(String id) {
        return VANILLA_SAVED_DATA.contains(id) || id.startsWith("minecraft:maps/");
    }

    /**
     * **不清缓存**的存档数据：被长生命周期对象用字段强引用、摘掉缓存条目就再也没人登记回去的那些。
     *
     * <p>{@code SavedDataStorage.scheduleSave()} 只遍历 {@code cache}，所以条目一旦被摘掉、又没人重新登记，
     * 对应的 {@code .dat} 就再也不落盘。26.1 里这些类型全是字段持有（1.21.1 那会儿它们大多是
     * 「每次用时取一次」，作废缓存自然会重读），所以这里用排除法：它们**故意留在缓存里**，
     * 内容不参与回滚（见类注释的「已知边界」），但会继续正常落盘。
     *
     * <p>每一条的「被谁强引用」：
     * <ul>
     *   <li>{@code minecraft:chunk_tickets} → {@code ServerChunkCache.ticketStorage}（final 字段）</li>
     *   <li>{@code minecraft:ender_dragon_fight} → {@code ServerLevel.dragonFight}</li>
     *   <li>{@code minecraft:random_sequences} → {@code MinecraftServer.randomSequences}（final 字段）</li>
     *   <li>{@code minecraft:world_gen_settings} → {@code MinecraftServer.worldGenSettings}（final 字段）</li>
     *   <li>{@code minecraft:custom_boss_events} → {@code MinecraftServer.customBossEvents}（final 字段）</li>
     *   <li>{@code minecraft:world_clocks} → {@code MinecraftServer.clockManager}（final 字段）</li>
     *   <li>{@code minecraft:scheduled_events} → {@code MinecraftServer.scheduledEvents}（final 字段）</li>
     *   <li>{@code minecraft:stopwatches} → {@code MinecraftServer.stopwatches}</li>
     *   <li>{@code minecraft:game_rules} → {@code MinecraftServer.gameRules} 手里的 {@code GameRules.rules}</li>
     *   <li>{@code minecraft:wandering_trader} → {@code WanderingTraderSpawner.traderData}</li>
     * </ul>
     *
     * <p>命令存储（{@code <任意命名空间>:command_storage}）也是强引用的（{@code CommandStorage.namespaces}），
     * 而且 26.1 的 {@code CommandStorage.get} 命中内存里那份 Container 就直接返回、不走存储层，
     * 所以它由 {@link #isProtectedSavedData} 按 path 一并保护：清掉缓存条目既回滚不了内容
     * （读取命中内存那份），还会让那份 Container 掉出存储缓存、{@code scheduleSave} 再也写不到它。
     */
    private static final Set<SavedDataType<?>> PROTECTED_SAVED_DATA = Set.of(
            TicketStorage.TYPE,
            EnderDragonFight.TYPE,
            RandomSequences.TYPE,
            WorldGenSettings.TYPE,
            CustomBossEvents.TYPE,
            ServerClockManager.TYPE,
            TimerQueue.TYPE,
            Stopwatches.TYPE,
            GameRuleMap.TYPE,
            WanderingTraderData.TYPE);

    /** 这个类型的缓存条目要不要留着：被长生命周期对象强引用的都留。 */
    private static boolean isProtectedSavedData(SavedDataType<?> type) {
        // 命令存储的 id 是 <任意命名空间>:command_storage，命名空间动态，按 path 认
        return PROTECTED_SAVED_DATA.contains(type) || type.id().getPath().equals("command_storage");
    }

    private static Map<SavedDataType<?>, Optional<SavedData>> cacheOf(SavedDataStorage storage) {
        return storageAccess(storage).rewind$cache();
    }

    // ------------------------------------------------------------------ 访问器包装

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static ChunkMapAccess mapAccess(ChunkMap chunkMap) {
        return (ChunkMapAccess) (Object) chunkMap;
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

    private static GenerationChunkHolderAccess generationAccess(ChunkHolder holder) {
        return (GenerationChunkHolderAccess) (Object) holder;
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

    private static SavedDataStorageAccess storageAccess(SavedDataStorage storage) {
        return (SavedDataStorageAccess) (Object) storage;
    }
}
