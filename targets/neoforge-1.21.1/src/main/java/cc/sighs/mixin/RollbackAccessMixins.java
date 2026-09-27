package cc.sighs.mixin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.BooleanSupplier;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 原地回滚需要的原版内部入口。
 *
 * <p>原地回滚（{@code cc.sighs.rewind.server.InPlaceRollback}）不关世界、不重开世界，而是在活着的
 * 集成服务器里做四件事：把受影响的区块从内存里丢掉（且不落盘）→ 释放 region 文件句柄并回拷快照 →
 * 让这些区块从磁盘重新读回来 → 把新数据重新发给客户端。这四件事每一步都扎在原版的私有/包私有成员上，
 * 全部集中在这里，业务代码只依赖本文件里的访问器接口。
 *
 * <p>全部是 {@code @Accessor} / {@code @Invoker}，不改变任何原版行为；{@code @Mutable} 只用在
 * {@code ServerLevel.raids} 上（原地回滚要换掉那个被强引用的 SavedData 实例）。
 */
public final class RollbackAccessMixins {
    private RollbackAccessMixins() {
    }

    // ------------------------------------------------------------------ 区块调度

    /** 区块调度：把某个 ChunkHolder 的 ticket level 顶到「未加载」，或者反过来重建 holder。 */
    @Mixin(ChunkMap.class)
    public interface ChunkMapAccess {
        /** 当前所有可见 ChunkHolder（只读视图，遍历时不要改状态）。 */
        @Invoker("getChunks")
        Iterable<ChunkHolder> rewind$getChunks();

        @Invoker("getUpdatingChunkIfPresent")
        ChunkHolder rewind$getUpdatingChunkIfPresent(long pos);

        /**
         * 原版唯一的 ticket level 落地口。level > {@code ChunkLevel.MAX_LEVEL} 表示卸载，
         * 传 {@code null} holder + 未加载的 oldLevel 表示重新建立 holder（从磁盘加载）。
         */
        @Invoker("updateChunkScheduling")
        ChunkHolder rewind$updateChunkScheduling(long pos, int level, ChunkHolder holder, int oldLevel);

        /** 排空卸载队列：把 toDrop 里的 holder 真的丢掉（含原版的卸载收尾）。 */
        @Invoker("processUnloads")
        void rewind$processUnloads(BooleanSupplier supplier);

        @Accessor("toDrop")
        LongSet rewind$toDrop();

        @Accessor("pendingUnloads")
        Long2ObjectLinkedOpenHashMap<ChunkHolder> rewind$pendingUnloads();

        @Accessor("unloadQueue")
        Queue<Runnable> rewind$unloadQueue();

        /** 「这个位置磁盘上是不是完整区块」的缓存：回滚后必须作废，否则保存判断会用到旧结论。 */
        @Accessor("chunkTypeCache")
        Long2ByteMap rewind$chunkTypeCache();
    }

    /** 距离管理器：holder 建好之后要挂到这里，下一次 runAllUpdates 才会真的去跑加载/卸载的 future。 */
    @Mixin(DistanceManager.class)
    public interface DistanceManagerAccess {
        @Accessor("chunksToUpdateFutures")
        Set<ChunkHolder> rewind$chunksToUpdateFutures();
    }

    /** 区块缓存：{@code getChunk} 有 4 项最近访问缓存，卸载后必须清掉，否则会拿到已经丢掉的区块。 */
    @Mixin(ServerChunkCache.class)
    public interface ServerChunkCacheAccess {
        @Invoker("clearCache")
        void rewind$clearCache();

        /** 推进 ticket 图 + 区块加载/卸载流水线（原版每 tick 走的就是它）。 */
        @Invoker("runDistanceManagerUpdates")
        boolean rewind$runDistanceManagerUpdates();
    }

    // ------------------------------------------------------------------ 存储句柄

    /** 区块存储的写盘 worker；{@code ChunkMap} 本身就是 {@code ChunkStorage}。 */
    @Mixin(ChunkStorage.class)
    public interface ChunkStorageAccess {
        @Accessor("worker")
        IOWorker rewind$worker();
    }

    /** POI / 实体存储用的也是这个 worker 包装。 */
    @Mixin(SimpleRegionStorage.class)
    public interface SimpleRegionStorageAccess {
        @Accessor("worker")
        IOWorker rewind$worker();
    }

    @Mixin(IOWorker.class)
    public interface IOWorkerAccess {
        @Accessor("storage")
        RegionFileStorage rewind$storage();
    }

    /**
     * region 文件句柄缓存。Windows 上文件句柄会锁住 .mca，覆盖之前必须把句柄关掉；
     * 原版 {@code close()} 只关不清理缓存，所以这里要连缓存一起清空，让下一次访问重新打开。
     */
    @Mixin(RegionFileStorage.class)
    public interface RegionFileStorageAccess {
        @Accessor("regionCache")
        Long2ObjectLinkedOpenHashMap<RegionFile> rewind$regionCache();

        /** 该存储对应的目录（{@code region/}、{@code poi/}、{@code entities/}）。 */
        @Accessor("folder")
        Path rewind$folder();
    }

    /** {@code PoiManager} 继承自它。 */
    @Mixin(SectionStorage.class)
    public interface SectionStorageAccess {
        @Accessor("simpleRegionStorage")
        SimpleRegionStorage rewind$simpleRegionStorage();
    }

    /** 实体存储：回滚后要作废 {@code emptyChunks}，否则快照里本来有实体的区块会被当成空区块。 */
    @Mixin(EntityStorage.class)
    public interface EntityStorageAccess {
        @Accessor("simpleRegionStorage")
        SimpleRegionStorage rewind$simpleRegionStorage();

        @Accessor("emptyChunks")
        LongSet rewind$emptyChunks();
    }

    // ------------------------------------------------------------------ 维度与存档数据

    @Mixin(ServerLevel.class)
    public interface ServerLevelAccess {
        @Accessor("entityManager")
        PersistentEntitySectionManager<Entity> rewind$entityManager();

        /** 袭击数据被 {@code ServerLevel} 强引用，换掉实例才能真正回滚。 */
        @Mutable
        @Accessor("raids")
        void rewind$setRaids(Raids raids);
    }

    @Mixin(PersistentEntitySectionManager.class)
    public interface EntitySectionManagerAccess {
        @Accessor("permanentStorage")
        EntityPersistentStorage<Entity> rewind$permanentStorage();

        /** 按区块缓存的实体分段：用来判断某个区块内存里还有没有「会被保存的实体」。 */
        @Accessor("sectionStorage")
        EntitySectionStorage<Entity> rewind$sectionStorage();

        /** 等待实体区块卸载完成的判据（原版只在自己的 tick() 里推进它）。 */
        @Accessor("chunksToUnload")
        LongSet rewind$chunksToUnload();
    }

    /** 存档数据缓存：回滚后清掉对应条目，下一次 {@code computeIfAbsent} 会重新读盘。 */
    @Mixin(DimensionDataStorage.class)
    public interface DimensionDataStorageAccess {
        @Accessor("cache")
        Map<String, SavedData> rewind$cache();
    }
}
