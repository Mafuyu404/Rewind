package cc.sighs.rewind.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
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
            unload(server, targets, result);
            drainUnloads(server);
            // 实体区块的卸载由区块状态驱动，靠 entityManager.tick() 推进，而且可能因为「实体还没读完」
            // 被推迟到下一 tick。必须在这一段里彻底排空，否则它会落到回滚窗口之外，把「改世界之后」的
            // 实体列表写回刚还原的文件。
            drainEntityUnloads(server);
            // 到这里被回滚接管的区块都已经从内存里丢掉了；接下来由回滚自己重建 holder，不再需要挡重建
            Rewind.clearUnloadGuards();
            invalidateCaches(server, targets);
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
            result.reloadMs = millisSince(stepNanos);

            // 玩家 / 时间天气必须排在这里：holder 已经重建、但区块还没成批重发。
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
                restoreSavedData(server);
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
            } else {
                stalled = 0;
            }
            lastPending = pending;
            LockSupport.parkNanos(PARK_NANOS);
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
     */
    private static void drainEntityUnloads(MinecraftServer server) {
        for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
            boolean quiet = true;
            for (ServerLevel level : server.getAllLevels()) {
                levelAccess(level).rewind$entityManager().tick();
                if (!entityManagerAccess(level).rewind$chunksToUnload().isEmpty()) {
                    quiet = false;
                }
            }
            if (quiet) {
                return;
            }
            LockSupport.parkNanos(PARK_NANOS);
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

    /** 把重建好的区块同步读回来；这也是原版把新数据重新推给客户端的触发点。 */
    private static void loadChunks(MinecraftServer server, List<Target> targets, Result result) {
        for (Target target : targets) {
            if (!target.unloaded) {
                continue;
            }
            ServerChunkCache source = target.level.getChunkSource();
            ChunkMapAccess access = mapAccess(source.chunkMap);
            sourceAccess(source).rewind$clearCache();
            try {
                source.getChunk(target.pos.x(), target.pos.z(), ChunkStatus.FULL, true);
                access.rewind$chunkTypeCache().remove(target.pos.pack());
                result.reloadedChunks++;
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload chunk {} in {}",
                        target.pos, target.level.dimension().identifier(), t);
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

    /**
     * 玩家 / 时间天气。位置见 {@link #run} 里的注释：必须排在 holder 重建之后、区块成批重发之前。
     */
    private static void restoreWorldAndPlayers(MinecraftServer server, Path slotDir, Result result) {
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
    }

    /**
     * 世界时间 / 出生点来自 level.dat，天气来自服务器级 {@code WeatherData}（26.1 把它从 level.dat
     * 拆成了 SavedData）。
     *
     * <p>这里不改磁盘上的任何文件：level.dat 的时间 / 出生点直接写进活着的 {@code WorldData}，
     * 天气则把快照值灌回那个被 {@code MinecraftServer} 强引用的 {@code WeatherData} 实例
     * （实例是 final 字段，换不掉，只能就地改）。
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
        restoreWeather(server);
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
     */
    private static void restoreWeather(MinecraftServer server) {
        SavedDataStorage storage = server.getDataStorage();
        cacheOf(storage).remove(WeatherData.TYPE);
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
        player.getRecipeBook().sendInitialRecipeBook(player);
    }

    /**
     * 被强引用的 SavedData 要换掉实例（或者把内容重新灌进去）才算真回滚。
     *
     * <p>两条都走「作废缓存 → 让存储层从磁盘重新读」：这里的磁盘文件在几步之前刚被
     * {@link SnapshotBlockIo#restoreInto} 换成快照内容，所以重新读到的就是存档点那一刻的值。
     *
     * <p>记分板：26.1 的 {@code ScoreboardSaveData} 挂在服务器级存储上，而且
     * {@code Scoreboard.addObjective} 对重名会抛异常，直接 load 到活着的记分板上必然失败
     * （原版日志里那句 {@code Error loading saved data: scoreboard}）。所以先把现有 objective / team
     * 清掉，再用 {@code ServerScoreboard.load(Packed)} 把快照内容灌回同一个记分板对象；
     * 老的那个 save data 实例仍然持有脏标记回调，读的是同一个记分板，下一次自动保存会写回正确内容。
     *
     * <p>袭击：{@code ServerLevel.raids} 是 final 字段，直接换实例。
     */
    private static void restoreSavedData(MinecraftServer server) {
        restoreScoreboard(server);

        for (ServerLevel level : server.getAllLevels()) {
            SavedDataStorage storage = level.getDataStorage();
            cacheOf(storage).remove(Raids.TYPE);
            levelAccess(level).rewind$setRaids(storage.computeIfAbsent(Raids.TYPE));
        }
    }

    private static void restoreScoreboard(MinecraftServer server) {
        SavedDataStorage storage = server.getDataStorage();
        cacheOf(storage).remove(ScoreboardSaveData.TYPE);
        ScoreboardSaveData saved = storage.get(ScoreboardSaveData.TYPE);
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
