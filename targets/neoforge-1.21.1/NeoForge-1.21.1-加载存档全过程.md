# NeoForge 1.21.1：从点击存档到「完全加载」的全过程源码分析

> 本文所有结论均来自反编译源码实读，标 `path:line` 的形式，路径相对源码树根。

## 0. 本文说明

### 0.1 源码树

| 项 | 值 |
|---|---|
| 源码根 | `D:/work/JEI Editor/build/neo-src`（ModDevGradle 生成的 `neo-src`） |
| 游戏版本 | Minecraft `1.21.1`（`SharedConstants.VERSION_STRING = "1.21.1"`，`WORLD_VERSION = 3955`） |
| 网络协议 | `RELEASE_NETWORK_PROTOCOL_VERSION = 767` |
| 加载器 | NeoForge 21.1.x（已应用补丁，`net/minecraft` 内直接可见 `NeoForge.EVENT_BUS.post(...)` / `EventHooks.*` / `CommonHooks.*` / `ClientHooks.*` / `ServerLifecycleHooks.*`） |
| 覆盖范围 | `net/minecraft/**`、`net/neoforged/neoforge/**`、`com/mojang/**`、`mcp/**` |

> 注：`net/neoforged/fml/**` **未包含**在这棵树里。因此凡走 `net.neoforged.fml.*`（`ModLoader.postEvent`、`FMLCommonSetupEvent` 等）的事件，只能看到调用方，fire 点实现不在树中。

### 0.2 三个必须先钉死的前提

1. **单人加载存档 = 完整的集成服务器启动 + 完整的网络协议登录流程**，不是「直接读地图」。客户端与服务端在同一进程的不同线程，连接走 `Connection.connectToLocalServer`（`LocalChannel` 内存通道）。
2. **单人连接是 memory connection**（`Connection.isMemoryConnection()` → `channel instanceof LocalChannel`）。这一点决定了后续很多分支：注册表同步被跳过、区块不限流一次发完。
3. **首次进入已有存档，客户端只收 `ClientboundLoginPacket`，不收 `ClientboundRespawnPacket`**，`ClientLevel` 只创建一次。1.21.1 里 `ClientboundRespawnPacket` 只用于「死亡重生」和「跨维度传送」。

### 0.3 阅读约定

- `path:line` 中的路径都相对源码树根，例如 `net/minecraft/client/Minecraft.java:2054`。
- 「Render thread」= 客户端主线程。Minecraft 的主线程就是渲染线程，`ViewArea.java:31-32` 与 `ApplyFrustum` 里都用 `Minecraft.isSameThread()` 断言确认。
- 「Server thread」= 名字就叫 `"Server thread"` 的那条线程（`MinecraftServer.java:267` 创建，归入 `SidedThreadGroups.SERVER`）。
- 标注「★」的是 NeoForge 相对 vanilla 的注入点。

---

## 1. 总览时序图

```
[客户端主线程] 鼠标双击存档 / Enter / Select 按钮
  → WorldListEntry.joinWorld()                                    WorldSelectionList.java:493
  → Minecraft.createWorldOpenFlows()                              Minecraft.java:2050
  → WorldOpenFlows.openWorld(id, onFail)                          WorldOpenFlows.java:257
       forceSetScreen("selectWorld.data_read")                    :258
       createWorldAccess → new LevelStorageAccess                  LevelStorageSource.java:350
           └── DirectoryLock.create → FileChannel.tryLock         DirectoryLock.java:28   ★ session.lock 到手
       forceSetScreen("selectWorld.data_read")                    :266
       getDataTag()  →  NbtIo.readCompressed + LEVEL/PLAYER/WGS   LevelStorageSource.java:221/228-230
                        （主线程同步读盘 + 三重 DataFixer）
       getSummary(dynamic) / readAdditionalLevelSaveData          :272 / :273  ★ Neo
       版本兼容检查 & 备份确认                                     :300-338
       forceSetScreen("selectWorld.resource_load")                :341
       ServerPacksSource.createPackRepository(access)             :342
       loadWorldStem → loadWorldDataBlocking                      :142 → :196
            WorldLoader.load(initConfig, supplier, WorldStem::new,
                             Util.backgroundExecutor(), minecraft) WorldLoader.java:24
            managedBlock(future::isDone)                           WorldOpenFlows.java:201  ★ 名义阻塞仍泵任务
       levelstem.generator().validate()                            :348-350
       实验性确认 / resources.zip / 磁盘空间检查                     :378-452
  → Minecraft.doWorldLoad(access, packs, stem, false)              Minecraft.java:2054
       disconnect()                                               :2055
       saveDataTag(...)  →  写回 level.dat                         :2060  ★ 含 Neo 的 fml.LoadingModList
       MinecraftServer.spin(...)  ⟹ 构造 + 启动 "Server thread"    MinecraftServer.java:265-277
       while (progressListener.get() == null) Thread.yield();     Minecraft.java:2083   ★ 真忙等
       setScreen(new LevelLoadingScreen(...))                     :2087-2088
       for (; !server.isReady(); ) { screen.tick(); runTick(false); Thread.sleep(16); }   :2091-2099
       startMemoryChannel() + Handshake + ServerboundHelloPacket  :2103-2110

[Server thread] runServer → initServer                              MinecraftServer.java:668 / IntegratedServer.java:70
       handleServerAboutToStart  ⇒  ServerAboutToStartEvent         IntegratedServer.java:76   ★ Neo
       loadLevel()                                                 MinecraftServer.java:328
            progressListenerFactory.create(spawnChunkRadius)       :335-336
            createLevels(listener)                                 :337 → :356
                 overworld: new ServerLevel(...)                   :367-369
                 LevelEvent.Load(overworld)                        :375   ★ Neo（早于 setInitialSpawn）
                 setInitialSpawn(...) ⇒ CreateSpawnPosition        :378 → :432   ★ Neo 可取消
                      └─ PlayerRespawnLogic.getSpawnPosInChunk
                           └─ ServerLevel.getChunk ⇒ 首次阻塞式加载  PlayerRespawnLogic.java:18
                 其它维度: new ServerLevel(...) + LevelEvent.Load    :409-424 / :425   ★ Neo
            prepareLevels(listener)                                :339 → :490
                 setDefaultSpawnPos ⇒ START region ticket          ServerLevel.java:1369-1387
                 while (getTickingGenerated() < 25) waitUntilNextTick()    :501-504   ★ 同步阻塞
       handleServerStarting ⇒ ServerStartingEvent                  IntegratedServer.java:81   ★ Neo
  handleServerStarted ⇒ ServerStartedEvent                         MinecraftServer.java:674   ★ Neo
  while (running) tickServer(...)                                  :680-707
       ... 末尾: for (ServerPlayer p) p.connection.chunkSender.sendNextChunks(p)   :1064-1069

[Netty / LocalChannel] ServerboundHelloPacket → 登录 → 配置阶段协商 → ClientboundLoginPacket
[主线程] ClientPacketListener.handleLogin                           ClientPacketListener.java:393
       new ClientLevel(...)                                        :409   ★ 内部 fire LevelEvent.Load
       Minecraft.setLevel(level, ReceivingLevelScreen.Reason.OTHER) :421
       ClientHooks.firePlayerLogin(...)                            :432   ★ ClientPlayerNetworkEvent.LoggingIn
       level.addEntity(player)                                     :434   ★ EntityJoinLevelEvent
       startWaitingForNewLevel(...) → LevelLoadStatusManager        :438 → :1431-1434
       （服务端 placeNewPlayer 推区块流；LEVEL_CHUNKS_LOAD_START 先到）
[主线程] 收区块 → queueLightUpdate（每渲染帧抽 10 个）
       → LevelRenderer.compileSections 调度 section 编译
       → Util.backgroundExecutor() 生成顶点 → Render thread 上传 GPU
       → LevelLoadStatusManager.tick() 判定 isSectionCompiled(playerPos) → LEVEL_READY
       → ReceivingLevelScreen.onClose()
```

---

## 2. 阶段一：点击存档 → 读到 level.dat（全程主线程）

### 2.1 三个入口，一个汇聚点

| 入口 | 位置 |
|---|---|
| 双击存档条目，或点击左侧 32px 图标 | `WorldSelectionList.java:469-487` |
| 选中后按 Enter / Space | `WorldSelectionList.java:137-151` |
| 底部 Select 按钮 | `SelectWorldScreen.java:42-46` |

三者最终都调用 `WorldSelectionList.WorldListEntry.joinWorld()`（`WorldSelectionList.java:493-504`）：

```java
public void joinWorld() {
    if (this.summary.primaryActionActive()) {
        if (this.summary instanceof LevelSummary.SymlinkLevelSummary) {
            this.minecraft.setScreen(NoticeWithLinkScreen.createWorldSymlinkWarningScreen(...));  // 符号链接警告
        } else {
            this.minecraft.createWorldOpenFlows().openWorld(this.summary.getLevelId(), () -> {
                WorldSelectionList.this.reloadWorldList();     // ← 失败回退
                this.minecraft.setScreen(this.screen);
            });
        }
    }
}
```

`Minecraft.createWorldOpenFlows()`（`Minecraft.java:2050-2052`）**每次点击都 new 一个 `WorldOpenFlows`**。

### 2.2 `openWorld` 的完整链路

```
WorldListEntry.joinWorld()                                       WorldSelectionList.java:493
 └─ Minecraft.createWorldOpenFlows()                             Minecraft.java:2050
 └─ WorldOpenFlows.openWorld(levelId, onFail)                    WorldOpenFlows.java:257
     ├─ forceSetScreen(GenericMessageScreen("selectWorld.data_read"))       :258
     ├─ createWorldAccess(levelId)                                :111
     │   └─ levelSource.validateAndCreateAccess(id)               LevelStorageSource.java:344
     │       ├─ worldDirValidator.validateDirectory(path, true)   :346   ← 符号链接检查
     │       └─ new LevelStorageAccess(id, path)                  :350   ← ★ session.lock 在此
     └─ openWorldLoadLevelData(access, onFail)                    :265
         ├─ forceSetScreen(GenericMessageScreen("selectWorld.data_read"))   :266
         ├─ access.getDataTag()                                   LevelStorageSource.java:489  ← ★ 读 level.dat + DataFixer
         ├─ access.getSummary(dynamic)                            LevelStorageSource.java:484
         ├─ access.readAdditionalLevelSaveData(false)             LevelStorageSource.java:467  ← ★ Neo 模组列表
         └─ openWorldCheckVersionCompatibility(...)               :300
             ├─ [不兼容]  AlertScreen "selectWorld.incompatible"   :305-312
             ├─ [需备份]  BackupConfirmScreen                      :324-333
             └─ openWorldLoadLevelStem(access, dynamic, false, onFail)     :332 / :335
                 ├─ forceSetScreen(GenericMessageScreen("selectWorld.resource_load"))  :341
                 ├─ ServerPacksSource.createPackRepository(access)         :342
                 ├─ loadWorldStem(dynamic, false, packRepo)                :346   ← ★ WorldLoader.load（阻塞）
                 ├─ levelstem.generator().validate()  遍历全部 LevelStem   :348-350
                 └─ openWorldCheckWorldStemCompatibility(...)              :375
                     └─ openWorldLoadBundledResourcePack(...)              :404
                         ├─ loadBundledResourcePack(downloadedPackSource, access)   :408  读 resources.zip
                         └─ thenAcceptAsync(..., minecraft) → openWorldCheckDiskSpace   :411 → :426
                             └─ openWorldDoLoad(access, worldStem, packRepo)   :450
                                 └─ minecraft.doWorldLoad(access, packRepo, worldStem, false)   :454-456
```

几个易漏的细节：

- `openWorld` 与 `openWorldLoadLevelData` **各自都设了一次** `GenericMessageScreen("selectWorld.data_read")`（`:258` / `:266`），因为中途可能插入 `RecoverWorldDataScreen`。
- `openWorldLoadLevelStem` 传入的是**同一个 `Dynamic<?>` 对象**——1.21 里 level.dat 只从磁盘完整读一次，后续复用。
- 磁盘空间检查阈值 `DISK_SPACE_WARNING_THRESHOLD = 67108864`（64 MB，`LevelStorageSource.java:85`）。
- `doWorldLoad` 第 4 个参数（此处 `false`）最终传到 `ClientHandshakePacketListenerImpl` 作为 `isNewWorld` 语义（`Minecraft.java:2106`）；新建世界路径传 `true`（`WorldOpenFlows.java:102`）。
- 失败兜底：`openWorldLoadLevelStem` 的 catch 会弹 `DatapackLoadFailureScreen`，用户可选「以安全模式重试」（把 safeMode 参数改为 `true` 递归调用自己，`:354-357`）。

### 2.3 `session.lock` 在 `openWorld` 的第三行就拿了

`LevelStorageSource.java:408-418`：

```java
LevelStorageAccess(String p_289967_, Path p_289988_) throws IOException {
    this.levelId = p_289967_;
    this.levelDirectory = new LevelStorageSource.LevelDirectory(p_289988_);
    this.lock = DirectoryLock.create(p_289988_);          // :417 ★
}
```

`DirectoryLock.create`（`DirectoryLock.java:20-43`）：

```java
Path path = p_13641_.resolve("session.lock");
FileUtil.createDirectoriesSafe(p_13641_);
FileChannel filechannel = FileChannel.open(path, CREATE, WRITE);
filechannel.write(DUMMY.duplicate());       // 写入 "\u2603"
filechannel.force(true);
FileLock filelock = filechannel.tryLock();  // :28
if (filelock == null) throw DirectoryLock.LockException.alreadyLocked(path);   // :30
```

释放点在 `LevelStorageAccess.close()`（`LevelStorageSource.java:644`）/ `safeClose()`（`:432`）。后续所有主要操作前都有 `checkLock()`（`:460`），失效则抛 `IllegalStateException("Lock is no longer valid")`。

存档列表页显示的「locked」是另一条路径：`LevelStorageSource.loadLevelSummaries`（`:175`）里 `DirectoryLock.isLocked(...)`（`:182`），异步执行、不持有锁。

### 2.4 level.dat 的完整读取与三重 DataFixer

`WorldOpenFlows.openWorldLoadLevelData` 第一件事就是 `getDataTag()`（`:271`）→ `LevelStorageSource.java:489-502`:

```java
public Dynamic<?> getDataTag() throws IOException { return this.getDataTag(false); }

private Dynamic<?> getDataTag(boolean p_307503_) throws IOException {
    this.checkLock();
    return LevelStorageSource.readLevelDataTagFixed(
        p_307503_ ? this.levelDirectory.oldDataFile() : this.levelDirectory.dataFile(),
        LevelStorageSource.this.fixerUpper);
}
```

`LevelStorageSource.java:220-231` —— **level.dat 的唯一完整读取 + 升级点**：

```java
static CompoundTag readLevelDataTagRaw(Path p_307408_) throws IOException {
    return NbtIo.readCompressed(p_307408_, NbtAccounter.create(104857600L));            // :221
}

static Dynamic<?> readLevelDataTagFixed(Path p_307371_, DataFixer p_307468_) throws IOException {
    CompoundTag compoundtag = readLevelDataTagRaw(p_307371_);                           // :225
    CompoundTag compoundtag1 = compoundtag.getCompound("Data");                         // :226
    int i = NbtUtils.getDataVersion(compoundtag1, -1);                                  // :227
    Dynamic<?> dynamic = DataFixTypes.LEVEL.updateToCurrentVersion(p_307468_, new Dynamic<>(NbtOps.INSTANCE, compoundtag1), i);  // :228
    dynamic = dynamic.update("Player", p -> DataFixTypes.PLAYER.updateToCurrentVersion(p_307468_, p, i));                         // :229
    return dynamic.update("WorldGenSettings", p -> DataFixTypes.WORLD_GEN_SETTINGS.updateToCurrentVersion(p_307468_, p, i));     // :230
}
```

**三个 DataFixer 依次应用：`DataFixTypes.LEVEL` → `PLAYER` → `WORLD_GEN_SETTINGS`**，目标是 `SharedConstants.getCurrentVersion().getDataVersion().getVersion()`（即 3955）。

之后这个 `Dynamic` 对象一路作为参数往下传，**不再读盘**：

- `getSummary(dynamic)`（`:272`）→ `LevelStorageSource.java:484-487` → `makeLevelSummary`（`:279-293`）→ `LevelVersion.parse`（`LevelVersion.java:22`）+ `LevelSettings.parse`（`LevelSettings.java:32`）+ `readDataConfig`（`LevelStorageSource.java:127`）。
- `loadWorldStem(dynamic, ...)`（`:346`）→ `LevelStorageSource.getLevelDataAndDimensions(dynamic, ...)`（`:135-148`）。

`PrimaryLevelData.parse`（`PrimaryLevelData.java:169-209`）反序列化 `Time` / `Player` / `SpawnX/Y/Z` / `WasModded` / `DayTime` / `clearWeatherTime` / `WanderingTraderId` / `ServerBrands` / `DragonFight` 等字段，并含 NeoForge 附加字段：

```java
).withConfirmedWarning(p_78538_ != Lifecycle.stable() && p_78531_.get("confirmedExperimentalSettings").asBoolean(false));  // :204
result.setDayTimeFraction(p_78531_.get("neoDayTimeFraction").asFloat(0f));    // :206  ★ Neo
result.setDayTimePerTick(p_78531_.get("neoDayTimePerTick").asFloat(-1f));     // :207  ★ Neo
```

**写回 level.dat 的时机**：`Minecraft.doWorldLoad` 的实质第一行（`Minecraft.java:2060`）:

```java
p_261564_.saveDataTag(p_261470_.registries().compositeAccess(), p_261470_.worldData());
```

→ `LevelStorageSource.java:504-528`：`WorldData.createTag` → `CommonHooks.writeAdditionalLevelSaveData`（`:512` ★ Neo）→ `saveLevelData`（`:516`），走 `Files.createTempFile` + `NbtIo.writeCompressed` + `Util.safeReplaceFile(level.dat, tmp, level.dat_old)`（`:520-524`）。**所以升级后的 NBT 在进入集成服务器前才落盘**，且每次加载都会重写一次。

异常恢复：`NbtException | ReportedNbtException | IOException` → `RecoverWorldDataScreen`（`WorldOpenFlows.java:274-283`）；`OutOfMemoryError` → `MemoryReserve.release()` + 崩溃报告（`:284-295`）。

### 2.5 存档列表其实已经跑过一次 DataFixer

`LevelStorageSource.java:175-214`，在 `Util.backgroundExecutor()` 后台线程里：

```java
list.add(CompletableFuture.supplyAsync(() -> {
    boolean flag = DirectoryLock.isLocked(...);                       // :182
    return this.readLevelSummary(leveldirectory, flag);              // :189
}, Util.backgroundExecutor()));                                       // :210
```

`readLevelSummary`（`:233`）用 `readLightweightData`（`:304-311`）**故意跳过 `Data/Player` 与 `Data/WorldGenSettings` 两个大字段**，然后 `DataFixTypes.LEVEL.updateToCurrentVersion`（`:248`）。

所以 DataFixer 在整条流程里被执行 **3 次**：列表轻量一次、点开完整一次、加写入时编码一次。

---

## 3. 阶段二：WorldStem 组装（数据包 + 注册表 + tags）

### 3.1 主线程「名义阻塞但仍在泵任务」

`WorldOpenFlows.loadWorldDataBlocking`（`:196-203`）：

```java
private <D, R> R loadWorldDataBlocking(WorldLoader.PackConfig packConfig,
        WorldLoader.WorldDataSupplier<D> supplier, WorldLoader.ResultFactory<D, R> factory) throws Exception {
    WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.INTEGRATED, 2);  // :199
    CompletableFuture<R> future = WorldLoader.load(initConfig, supplier, factory,
                                                   Util.backgroundExecutor(),   // 工作线程池
                                                   this.minecraft);             // 主线程串行执行器
    this.minecraft.managedBlock(future::isDone);   // :201 ★
    return future.get();                           // :202
}
```

`BlockableEventLoop.managedBlock`（`BlockableEventLoop.java:127-144`）：

```java
public void managedBlock(BooleanSupplier p_18702_) {
    this.blockingCount++;
    try {
        while (!p_18702_.getAsBoolean()) {
            if (!this.pollTask()) {
                this.waitForTasks();      // Thread.yield() + LockSupport.parkNanos(100_000L)
            }
        }
    } finally { this.blockingCount--; }
}
```

语义：**主线程名义上阻塞，但持续 poll 并执行主线程任务队列**。这就是加载期间 UI 不假死、Screen 仍能收到 tick 的原因。

`Minecraft` 本身就是 `ReentrantBlockableEventLoop<Runnable>`（`Minecraft.java:257`），所以异步链的最后一跳必定落回主线程。

### 3.2 `WorldLoader.load` 六步

`WorldLoader.java:24-70`：

```java
Pair<WorldDataConfiguration, CloseableResourceManager> pair = p_214363_.packConfig.createResourceManager();   // :32
CloseableResourceManager closeableresourcemanager = pair.getSecond();                                        // :33
LayeredRegistryAccess<RegistryLayer> layeredregistryaccess = RegistryLayer.createRegistryAccess();           // :34
LayeredRegistryAccess<RegistryLayer> layeredregistryaccess1 = loadAndReplaceLayer(
    closeableresourcemanager, layeredregistryaccess, RegistryLayer.WORLDGEN,
    net.neoforged.neoforge.registries.DataPackRegistriesHooks.getDataPackRegistries()                          // :36 ★ Neo
);
RegistryAccess.Frozen registryaccess$frozen = layeredregistryaccess1.getAccessForLoading(RegistryLayer.DIMENSIONS);  // :38
RegistryAccess.Frozen registryaccess$frozen1 = RegistryDataLoader.load(
    closeableresourcemanager, registryaccess$frozen, RegistryDataLoader.DIMENSION_REGISTRIES);                 // :39-41
WorldDataConfiguration worlddataconfiguration = pair.getFirst();                                              // :42
WorldLoader.DataLoadOutput<D> dataloadoutput = p_214364_.get(                                                  // :43
    new WorldLoader.DataLoadContext(closeableresourcemanager, worlddataconfiguration,
                                    registryaccess$frozen, registryaccess$frozen1));
LayeredRegistryAccess<RegistryLayer> layeredregistryaccess2 = layeredregistryaccess1.replaceFrom(
    RegistryLayer.DIMENSIONS, dataloadoutput.finalDimensions);                                                 // :46-48
return ReloadableServerResources.loadResources(                                                                // :49
        closeableresourcemanager, layeredregistryaccess2, worlddataconfiguration.enabledFeatures(),
        p_214363_.commandSelection(), p_214363_.functionCompilationLevel(), p_214366_, p_214367_)
    .whenComplete((a, b) -> { if (b != null) closeableresourcemanager.close(); })                              // :58-62
    .thenApplyAsync(p_335216_ -> {
        p_335216_.updateRegistryTags();                                                                        // :64 ★ 绑定标签
        return p_214365_.create(closeableresourcemanager, p_335216_, layeredregistryaccess2, dataloadoutput.cookie);  // :65 → new WorldStem
    }, p_214367_);                                                                                             // :66 ★ 主线程执行器
```

两个 Executor 的含义：第 4 个参数 = `Util.backgroundExecutor()`（准备工作线程池），第 5 个参数 = `this.minecraft`（主线程串行执行器）。

**`WorldDataSupplier` 在读档路径下的实现**是 `WorldOpenFlows.loadWorldStem` 的 lambda（`WorldOpenFlows.java:144-150`）：

```java
p_307082_ -> {
    Registry<LevelStem> registry = p_307082_.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM);
    LevelDataAndDimensions leveldataanddimensions = LevelStorageSource.getLevelDataAndDimensions(
        p_307491_, p_307082_.dataConfiguration(), registry, p_307082_.datapackWorldgen());
    return new WorldLoader.DataLoadOutput<>(leveldataanddimensions.worldData(),
                                            leveldataanddimensions.dimensions().dimensionsRegistryAccess());
}
```

**`ResultFactory`** 就是 `WorldStem::new`（`WorldOpenFlows.java:151`）。

`LevelStorageSource.getLevelDataAndDimensions`（`:135-148`）里对 level.dat 的解析：

```java
Dynamic<?> dynamic  = RegistryOps.injectRegistryContext(p_307313_, p_307648_);  // :138
Dynamic<?> dynamic1 = dynamic.get("WorldGenSettings").orElseEmptyMap();         // :139
WorldGenSettings worldgensettings = WorldGenSettings.CODEC.parse(dynamic1).getOrThrow();   // :140
LevelSettings levelsettings = LevelSettings.parse(dynamic, p_307486_);           // :141
WorldDimensions.Complete complete = worldgensettings.dimensions().bake(p_307597_);         // :142
Lifecycle lifecycle = complete.lifecycle().add(p_307648_.allRegistriesLifecycle());        // :143
PrimaryLevelData primaryleveldata = PrimaryLevelData.parse(                      // :144
    dynamic, levelsettings, complete.specialWorldProperty(), worldgensettings.options(), lifecycle);
return new LevelDataAndDimensions(primaryleveldata, complete);                    // :147
```

`WorldDimensions.bake`（`WorldDimensions.java:164-186`）把数据包的 `LEVEL_STEM` 与存档里的 dimensions 合并成一个 frozen `MappedRegistry<LevelStem>`。

### 3.3 `WorldStem` 是什么

`WorldStem.java:7-14` —— 只有四个字段：

```java
public record WorldStem(
    CloseableResourceManager resourceManager,          // 数据包资源管理器
    ReloadableServerResources dataPackResources,       // 配方/标签/战利品表/函数等
    LayeredRegistryAccess<RegistryLayer> registries,   // 分层注册表（含 WORLDGEN + DIMENSIONS）
    WorldData worldData                                // 已解析的 level.dat
) implements AutoCloseable { ... }
```

它被交给 `IntegratedServer` 构造函数（`Minecraft.java:2066`），服务器再拆进 `MinecraftServer`（`MinecraftServer.java:290-297`）。

### 3.4 线程：异步 + 主线程阻塞等待的混合

- `WorldLoader.load` 立刻返回 `CompletableFuture`（阶段 `createResourceManager` 是**同步跑在调用线程 = 主线程**上的）。
- `ReloadableServerResources.loadResources`（`ReloadableServerResources.java:98-137`）内部 `SimpleReloadInstance.create(..., Util.backgroundExecutor(), ...)`（`:118-120`）并行跑各 reload listener。
- 最终 `thenApplyAsync(..., p_214367_)` 用**主线程执行器**。
- 主线程卡在 `Minecraft.managedBlock(completablefuture::isDone)`。

### 3.5 UI 上显示什么

这一段是**三层依次切换的 Screen**，注意没有文字化「进度阶段」：

| 顺序 | Screen | 触发点 | 文案 key | 行为 |
|---|---|---|---|---|
| 1 | `GenericMessageScreen` | `WorldOpenFlows.java:258` | `selectWorld.data_read` | 居中文字 + 全景背景，`shouldCloseOnEsc() = false` |
| 2 | `GenericMessageScreen` | `WorldOpenFlows.java:266` | `selectWorld.data_read` | 同上 |
| 3 | `GenericMessageScreen` | `WorldOpenFlows.java:341` | `selectWorld.resource_load` | 同上 |
| 4 | `LevelLoadingScreen` | `Minecraft.java:2087-2088` | `loading.progress`（百分比） | 区块状态网格 |
| 5 | `ReceivingLevelScreen` | `ClientPacketListener.java:421` → `:1433` | `multiplayer.downloadingTerrain` | 地形下载遮罩 |

`LevelLoadingScreen`（`LevelLoadingScreen.java:18-117`）显示的是一个由 `ChunkStatus` 决定颜色的**区块状态网格**（`COLORS` 表在 `:23-37`）：

```java
p_280803_.put(ChunkStatus.EMPTY, 5526612);
p_280803_.put(ChunkStatus.STRUCTURE_STARTS, 10066329);
p_280803_.put(ChunkStatus.STRUCTURE_REFERENCES, 6250897);
p_280803_.put(ChunkStatus.BIOMES, 8434258);
p_280803_.put(ChunkStatus.NOISE, 13750737);
p_280803_.put(ChunkStatus.SURFACE, 7497737);
p_280803_.put(ChunkStatus.CARVERS, 3159410);
p_280803_.put(ChunkStatus.FEATURES, 2213376);
p_280803_.put(ChunkStatus.INITIALIZE_LIGHT, 13421772);
p_280803_.put(ChunkStatus.LIGHT, 16769184);
p_280803_.put(ChunkStatus.SPAWN, 15884384);
p_280803_.put(ChunkStatus.FULL, 16777215);
```

这 12 个就是真正的「进度阶段」（`ChunkStatus.java:20-31` 的顺序）：

```
empty → structure_starts → structure_references → biomes → noise → surface
→ carvers → features → initialize_light → light → spawn → full
```

- 网格上方一行百分比（`LevelLoadingScreen.java:82-86` → `getFormattedProgress():69-71` → `Component.translatable("loading.progress", clamp(progress, 0, 100))`）。
- 百分比数据源：`StoringChunkProgressListener.getProgress()`（`:82-84`）委托 `LoggerChunkProgressListener.getProgress()`（`:65-67`），**只统计已到 `ChunkStatus.FULL` 的格子数 / 总格子数**。
- 日志里的 `menu.preparingSpawn` 文案（`LoggerChunkProgressListener.java:51`）**只进日志，不上屏幕**。
- `ProgressScreen`（`ProgressScreen.java:12-79`）在这条路径上**完全没被用到**，只在 `Minecraft.disconnect()`（`Minecraft.java:2127`）和删除世界（`WorldSelectionList.java:512`）时出现。

### 3.6 这一段里 NeoForge 干了什么

| 位置 | 钩子 / 内容 |
|---|---|
| `ServerPacksSource.java:76` | `ResourcePackLoader.populatePackRepository(packRepository, PackType.SERVER_DATA, false)` → fire `AddPackFindersEvent`（mod bus） |
| `MinecraftServer.java:1534-1537` | `CommonHooks.getModDataPacks()` / `getModDataPacksWithVanilla()` 把 mod 数据包塞进配置 |
| `WorldLoader.java:36` | `DataPackRegistriesHooks.getDataPackRegistries()` 替换原版 WORLDGEN 注册表列表 |
| `LevelSettings.java:42` | `CommonHooks.parseLifecycle(tag.get("forgeLifecycle"))` |
| `ReloadableServerResources.java:114` | `EventHooks.onResourceReload` → fire `AddReloadListenerEvent` |
| `ReloadableServerResources.java:115-117, 126-134` | `ContextAwareReloadListener.injectContext(...)` 注入/清空上下文 |
| `Commands.java:251` | **`EventHooks.onCommandRegister` → fire `RegisterCommandsEvent`** |
| `ReloadableServerResources.java:143` | **`TagsUpdatedEvent(registryAccess, false, false)`** |

> **纠正一个常见误解**：**没有 `RegisterServerCommandsEvent` 这个名字**，全树 grep 零命中。对应的就是 `net.neoforged.neoforge.event.RegisterCommandsEvent`，包也不在 `event/server/` 下。
>
> 而且它的触发时机**非常早**：`Commands` 由 `ReloadableServerResources` 构造（`ReloadableServerResources.java:49`），而 `ReloadableServerResources` 由 `WorldLoader.load` 创建（`WorldLoader.java:49`）。也就是说 **`RegisterCommandsEvent` 在 `WorldLoader` 阶段就已经 fire 了，早于 `new IntegratedServer(...)` 和 `initServer`**。`/reload` 时会通过 `MinecraftServer.reloadResources`（`MinecraftServer.java:1485`）再 fire 一次。

---

## 4. 阶段三：启动集成服务器与客户端忙等

### 4.1 `Minecraft.doWorldLoad`（`Minecraft.java:2054-2111`）

```java
this.disconnect();                                                    // :2055 清掉旧世界（换 ProgressScreen + 等服务器关闭）
this.progressListener.set(null);                                      // :2056
Instant instant = Instant.now();                                      // :2057 用于统计加载耗时
p_261564_.saveDataTag(p_261470_.registries().compositeAccess(), p_261470_.worldData());   // :2060 ★ 写回 level.dat
Services services = Services.create(this.authenticationService, this.gameDirectory);
services.profileCache().setExecutor(this);
SkullBlockEntity.setup(services, this);
GameProfileCache.setUsesAuthentication(false);
this.singleplayerServer = MinecraftServer.spin(                       // :2065 ★ 起 "Server thread"
    p_231361_ -> new IntegratedServer(p_231361_, this, p_261564_, p_261826_, p_261470_, services,
        p_319374_ -> {
            StoringChunkProgressListener l = StoringChunkProgressListener.createFromGameruleRadius(p_319374_ + 0);
            this.progressListener.set(l);                             // :2068 ★ 服务端线程回填
            return ProcessorChunkProgressListener.createStarted(l, this.progressTasks::add);   // :2069 跨线程 mailbox
        }));
this.isLocalServer = true;                                            // :2072
this.updateReportEnvironment(ReportEnvironment.local());
this.quickPlayLog.setWorldData(QuickPlayType.SINGLEPLAYER, p_261564_.getLevelId(), ...);
```

`MinecraftServer.spin`（`MinecraftServer.java:265-277`）：

```java
public static <S extends MinecraftServer> S spin(Function<Thread, S> p_129873_) {
    AtomicReference<S> atomicreference = new AtomicReference<>();
    Thread thread = new Thread(net.neoforged.fml.util.thread.SidedThreadGroups.SERVER,
                               () -> atomicreference.get().runServer(), "Server thread");     // :267 ★ Neo 线程组
    thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught exception in server thread", e));
    if (Runtime.getRuntime().availableProcessors() > 4) thread.setPriority(8);                // :270
    S s = (S)p_129873_.apply(thread);       // ★ 构造函数在调用者线程（客户端主线程）上执行
    atomicreference.set(s);
    thread.start();                         // ★ runServer() 在 Server thread
    return s;
}
```

**`new IntegratedServer(...)` 是在客户端主线程上构造的**，只有 `runServer()` 跑在 Server thread 上。

`IntegratedServer` 构造函数（`IntegratedServer.java:53-67`）里就已经调 `new IntegratedPlayerList(...)`（`:65`）；而 `MinecraftServer` 构造函数（`MinecraftServer.java:279-320`）里调了 **`this.playerDataStorage = p_236724_.createPlayerStorage();`（`:307`）** —— 这是 `LevelStorageSource.createPlayerStorage`（`:479-482`）的真实调用点，它内部会先 `checkLock()`。

### 4.2 两段忙等

```java
while (this.progressListener.get() == null) {          // :2083-2085
    Thread.yield();
}
```

**纯忙等**，不泵任务、不渲染。等的是 `:2068` 那行由 Server thread 在 `loadLevel()` → `progressListenerFactory.create(...)` 时写入的引用。

```java
LevelLoadingScreen levelloadingscreen = new LevelLoadingScreen(this.progressListener.get());
this.setScreen(levelloadingscreen);                    // :2088（用 setScreen，不是 forceSetScreen）
this.profiler.push("waitForServer");

for (; !this.singleplayerServer.isReady() || this.overlay != null; this.handleDelayedCrash()) {
    levelloadingscreen.tick();                         // :2092
    this.runTick(false);                               // :2093 ★ 手动泵一帧（false = 不做 tick 循环计时）
    try {
        Thread.sleep(16L);                             // :2096
    } catch (InterruptedException ignored) { }
}
this.profiler.pop();
```

`isReady` 由 Server thread 在每个 tick 结束时置 `true`（`MinecraftServer.java:721`），读取端 `:1355-1357`。这期间主线程**每 16 ms 手动跑一帧**，所以 `LevelLoadingScreen` 能渲染、`progressTasks` 能被排空。这是「伪阻塞」——看着卡住，实际在渲染。

### 4.3 建立本地连接

```java
Duration duration = Duration.between(instant, Instant.now());
SocketAddress socketaddress = this.singleplayerServer.getConnection().startMemoryChannel();     // :2103
Connection connection = Connection.connectToLocalServer(socketaddress);                         // :2104
connection.initiateServerboundPlayConnection(
    socketaddress.toString(), 0,
    new ClientHandshakePacketListenerImpl(connection, this, null, null, p_261465_, duration, p -> {}, null));  // :2106
connection.send(new ServerboundHelloPacket(this.getUser().getName(), this.getUser().getProfileId()));          // :2109
this.pendingConnection = connection;                                                            // :2110
```

**`doWorldLoad` 到此就返回了。** 后续 `setScreen` / `setLevel` 发生在后续 tick 处理网络包时。

### 4.4 `setScreen` 内部的次序（`Minecraft.java:1013-1066`）

```java
if (SharedConstants.IS_RUNNING_IN_IDE && Thread.currentThread() != this.gameThread) {
    LOGGER.error("setScreen called from non-game thread");           // :1014-1016 ← 仅 IDE 内的线程断言
}
net.neoforged.neoforge.client.ClientHooks.clearGuiLayers(this);      // :1035 ★ Neo
Screen old = this.screen;
if (p_91153_ != null) {
    var event = new ScreenEvent.Opening(old, p_91153_);              // :1038 ★ Neo
    if (NeoForge.EVENT_BUS.post(event).isCanceled()) return;          // :1039 ★ 可取消
    p_91153_ = event.getNewScreen();
}
if (old != null && p_91153_ != old) {
    NeoForge.EVENT_BUS.post(new ScreenEvent.Closing(old));            // :1044 ★ Neo
    old.removed();                                                   // :1045
}
this.screen = p_91153_;                                              // :1048
if (this.screen != null) this.screen.added();                        // :1050
BufferUploader.reset();                                              // :1053
if (p_91153_ != null) {
    this.mouseHandler.releaseMouse();
    KeyMapping.releaseAll();
    p_91153_.init(this, guiScaledWidth, guiScaledHeight);            // :1057
    this.noRender = false;
} else {
    this.soundManager.resume();
    this.mouseHandler.grabMouse();
}
this.updateTitle();                                                  // :1064
```

`forceSetScreen`（`:2231-2236`）比 `setScreen` 多一次 `runTick(false)`（即「立刻渲染一帧」）。
`updateScreenAndTick`（`:2221-2229`）= `cameraEntity = null` + `pendingConnection = null` + `setScreen(...)` + `runTick(false)`。

---

## 5. 阶段四：服务端建世界（Server thread）

### 5.1 `IntegratedServer.initServer`（`IntegratedServer.java:69-83`）

```java
public boolean initServer() {
    LOGGER.info("Starting integrated minecraft server version {}", ...);
    this.setUsesAuthentication(true);                                        // :72
    this.setPvpAllowed(true);                                                // :73
    this.setFlightAllowed(true);                                             // :74
    this.initializeKeyPair();                                                // :75 → MinecraftServer.java:1218
    net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerAboutToStart(this);   // :76 ★ Neo
    this.loadLevel();                                                        // :77 ★ 全部建维度/出生点/预加载
    GameProfile gameprofile = this.getSingleplayerProfile();
    String s = this.getWorldData().getLevelName();
    this.setMotd(gameprofile != null ? gameprofile.getName() + " - " + s : s);            // :80
    net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerStarting(this);        // :81 ★ Neo
    return true;
}
```

对比 `DedicatedServer` 的 `initServer`：后者还要绑端口 / `ServerConnectionListener.startTcpServerListener`，集成服务器不需要。

**`IntegratedServer` 没有覆写 `loadLevel`**，直接沿用 `MinecraftServer.loadLevel` 的 vanilla 实现。

### 5.2 `MinecraftServer.loadLevel`（`MinecraftServer.java:328-351`）

```java
protected void loadLevel() {
    boolean flag = false;                                            // ← 恒 false（死分支）
    ProfiledDuration profiledduration = JvmProfiler.INSTANCE.onWorldLoadedStarted();
    this.worldData.setModdedInfo(this.getServerModName(), this.getModdedStatus().shouldReportAsModified());
    ChunkProgressListener chunkprogresslistener = this.progressListenerFactory
        .create(this.worldData.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS));   // :335-336
    this.createLevels(chunkprogresslistener);                        // :337
    this.forceDifficulty();                                          // :338（空实现，:353-354）
    this.prepareLevels(chunkprogresslistener);                       // :339
    if (profiledduration != null) profiledduration.finish();
}
```

`WorldLoader` 里 `DataLoadContext` / `DataLoadOutput` 定义在 `WorldLoader.java:86-92`；`PackConfig.createResourceManager`（`:98-105`）会调 `MinecraftServer.configurePackRepository`（`MinecraftServer.java:1527`）。

### 5.3 `createLevels` —— 每个维度建一个 `ServerLevel`

`MinecraftServer.java:356-430`：

```java
ServerLevelData serverleveldata = this.worldData.overworldData();                     // :357
boolean flag = this.worldData.isDebugWorld();                                         // :358
Registry<LevelStem> registry = this.registries.compositeAccess()
        .registryOrThrow(Registries.LEVEL_STEM);                                      // :359 ← 维度注册表
WorldOptions worldoptions = this.worldData.worldGenOptions();                         // :360
long i = worldoptions.seed();
long j = BiomeManager.obfuscateSeed(i);                                               // :361-362
List<CustomSpawner> list = ImmutableList.of(PhantomSpawner(), PatrolSpawner(),
        CatSpawner(), VillageSiege(), WanderingTraderSpawner(serverleveldata));       // :363-365

LevelStem levelstem = registry.get(LevelStem.OVERWORLD);                              // :366
ServerLevel serverlevel = new ServerLevel(this, this.executor, this.storageSource,
        serverleveldata, Level.OVERWORLD, levelstem, p_129816_, flag, j,
        list, true /*tickTime*/, null /*randomSequences*/);                          // :367-369
this.levels.put(Level.OVERWORLD, serverlevel);                                        // :370

DimensionDataStorage dimensiondatastorage = serverlevel.getDataStorage();             // :371
this.readScoreboard(dimensiondatastorage);                                            // :372
this.commandStorage = new CommandStorage(dimensiondatastorage);                       // :373
WorldBorder worldborder = serverlevel.getWorldBorder();                               // :374
NeoForge.EVENT_BUS.post(new LevelEvent.Load(levels.get(Level.OVERWORLD)));            // :375 ★ Neo
if (!serverleveldata.isInitialized()) {                                               // :376
    try {
        setInitialSpawn(serverlevel, serverleveldata, worldoptions.generateBonusChest(), flag);   // :378
        serverleveldata.setInitialized(true);                                         // :379
        if (flag) this.setupDebugLevel(this.worldData);                               // :381
    } catch (Throwable throwable1) { /* CrashReport "Exception initializing level" */ }
    serverleveldata.setInitialized(true);                                             // :394
}
this.getPlayerList().addWorldborderListener(serverlevel);                             // :397
if (this.worldData.getCustomBossEvents() != null) this.getCustomBossEvents().load(...);   // :398-400
RandomSequences randomsequences = serverlevel.getRandomSequences();                   // :402

for (Entry<ResourceKey<LevelStem>, LevelStem> entry : registry.entrySet()) {          // :404
    ResourceKey<LevelStem> resourcekey = entry.getKey();
    if (resourcekey != LevelStem.OVERWORLD) {                                         // :406
        ResourceKey<Level> resourcekey1 = ResourceKey.create(Registries.DIMENSION, resourcekey.location());  // :407
        DerivedLevelData derivedleveldata = new DerivedLevelData(this.worldData, serverleveldata);           // :408
        ServerLevel serverlevel1 = new ServerLevel(this, this.executor, this.storageSource,
                derivedleveldata, resourcekey1, entry.getValue(), p_129816_, flag, j,
                ImmutableList.of() /*无 customSpawner*/, false /*tickTime*/,
                randomsequences);                                                     // :409-422
        worldborder.addListener(new BorderChangeListener.DelegateBorderChangeListener(serverlevel1.getWorldBorder()));  // :423
        this.levels.put(resourcekey1, serverlevel1);                                  // :424
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(levels.get(resourcekey)));        // :425 ★ Neo
    }
}
worldborder.applySettings(serverleveldata.getWorldBorder());                          // :429
```

几个要点：

- **只有 Overworld 用 `worldData.overworldData()` 作为 `ServerLevelData`**，其它维度用 `DerivedLevelData(worldData, serverleveldata)`（只读代理）。
- **只有 Overworld 允许 tickTime**（`p_215009_ = true`），其它维度 `false`（见 `ServerLevel.tickTime`，`ServerLevel.java:441-450`）。
- **只有 Overworld 传 customSpawner 列表**，其它维度传 `ImmutableList.of()`。
- 其它维度共享 Overworld 的 `RandomSequences`（`:402 → :421`）。
- `:425` 用 `levels.get(resourcekey)`（`ResourceKey<LevelStem>`）查 `Map<ResourceKey<Level>, ServerLevel>` —— 这**不是 bug**：`Registries.DIMENSION` 与 `Registries.LEVEL_STEM` 的 registry key **都是 `"minecraft:dimension"`**（`Registries.java:231-232`），而 `ResourceKey.create` 按 `InternKey(registry, location)` 做 interning（`ResourceKey.java:33-37`），所以两个泛型不同的 key 实际是**同一个对象**。
- **`LevelEvent.Load(overworld)`（`:375`）fire 在 `setInitialSpawn`（`:378`）之前**。所以监听 `LevelEvent.Load` 时 Overworld 的出生点还没算出来（`levelData.isInitialized()` 仍为 false）。要改出生点应该用 `LevelEvent.CreateSpawnPosition`。

### 5.4 `ServerLevel` 构造函数初始化顺序

`ServerLevel.java:208-305`（父类 `Level` 的 super 调用在 `:222-232`）：

| 行 | 内容 |
|---|---|
| 233 | `this.tickTime = p_215009_` |
| 234-236 | `server` / `serverLevelData` / `ChunkGenerator chunkgenerator = p_215004_.generator()` |
| 237 | `boolean flag = server.forceSynchronousWrites()`（集成服务器 = `minecraft.options.syncWrites`，`IntegratedServer.java:291-293`） |
| 239-249 | `EntityStorage(SimpleRegionStorage(RegionStorageInfo(levelId, dim, "entities"), ...))` |
| **250** | **`this.entityManager = new PersistentEntitySectionManager<>(Entity.class, new EntityCallbacks(), entitypersistentstorage)`** |
| **251-264** | **`this.chunkSource = new ServerChunkCache(this, storageAccess, datafixer, server.getStructureManager(), executor, chunkgenerator, viewDistance, simulationDistance, flag, progressListener, this.entityManager::updateChunkStatus, () -> server.overworld().getDataStorage())`** |
| 265 | `this.chunkSource.getGeneratorState().ensureStructuresGenerated()`（**fire-and-forget，不阻塞**） |
| 266 | `this.portalForcer = new PortalForcer(this)` |
| 267 | `this.updateSkyBrightness()` |
| **268** | **`this.prepareWeather()`**（天气初始计时；运行期在 `advanceWeatherCycle()`，由 `tick` → `:342` 驱动） |
| 269 | `this.getWorldBorder().setAbsoluteMaxWorldSize(server.getAbsoluteMaxWorldSize())` |
| **270** | **`this.raids = this.getDataStorage().computeIfAbsent(Raids.factory(this), Raids.getFileId(...))`** |
| 271-273 | 非单人时 `serverLevelData.setGameType(server.getDefaultGameType())` |
| **276-287** | **`this.structureCheck = new StructureCheck(chunkSource.chunkScanner(), registryAccess(), server.getStructureManager(), dimension, chunkgenerator, chunkSource.randomState(), this, chunkgenerator.getBiomeSource(), i, datafixer)`** ← 1.21.1 **有** `StructureCheck` |
| 288 | `this.structureManager = new StructureManager(this, worldGenOptions, this.structureCheck)` |
| 289-293 | End 维度 → `this.dragonFight = new EndDragonFight(this, i, worldData.endDragonFightData())`；否则 null |
| 295 | `this.sleepStatus = new SleepStatus()` |
| 296 | `this.gameEventDispatcher = new GameEventDispatcher(this)` |
| 297-299 | `this.randomSequences = Objects.requireNonNullElseGet(p_288977_, () -> getDataStorage().computeIfAbsent(RandomSequences.factory(i), "random_sequences"))` |
| **301** | **★ Neo: `LevelAttachmentsSavedData.init(this)`** |
| **302-304** | **★ Neo: `this.customSpawners = EventHooks.getCustomSpawners(this, p_215008_)`** |

> `customSpawners` 被 NeoForge **刻意挪到构造器末尾**（源码里有注释说明）：这样 `ServerLevelEvent.CustomSpawners` / `ModifyCustomSpawnersEvent` 的处理器拿到的是完全初始化好的 level。

`StructureCheck` 构造（`StructureCheck.java:53-75`）只存字段，不做 I/O。

**`ServerChunkCache` 构建顺序**（`ServerChunkCache.java:68-107`）：

```java
this.level = p_214982_;                                                    // :82
this.mainThreadProcessor = new MainThreadExecutor(p_214982_);              // :83
this.mainThread = Thread.currentThread();                                  // :84 ★ 就是 Server thread
File file1 = storageAccess.getDimensionPath(dimension).resolve("data").toFile();   // :85-86
this.dataStorage = new DimensionDataStorage(file1, fixer, registryAccess());       // :87
this.chunkMap = new ChunkMap(this, storageAccess, fixer, structureTemplateManager,
        executor, mainThreadProcessor, this, chunkgenerator,
        progressListener, chunkStatusUpdateListener, overworldDataStorage,
        viewDistance, syncWrites);                                         // :88-102
this.lightEngine     = this.chunkMap.getLightEngine();                     // :103
this.distanceManager = this.chunkMap.getDistanceManager();                 // :104
this.distanceManager.updateSimulationDistance(p_214989_);                  // :105
this.clearCache();                                                        // :106
```

`ServerChunkCache` 把自己构造时所在的线程记为「主线程」（`:84`）。因为整条链从 `runServer → initServer → loadLevel` 走下来，所以 `ServerChunkCache.mainThread == Server thread`。

**`ChunkMap` 构建顺序**（`ChunkMap.java:145-205`，`extends ChunkStorage`）：

```java
super(new RegionStorageInfo(levelId, dimension, "chunk"),
      storageAccess.getDimensionPath(dimension).resolve("region"),
      fixer, syncWrites);                    // :160-165 → ChunkStorage.java:32-35 → new IOWorker(...) + RegionFileStorage
this.randomState = RandomState.create(...);                                    // :171-175
this.chunkGeneratorState = generator.createState(STRUCTURE_SET, randomState, seed);   // :177
ProcessorMailbox worldgen = create(executor, "worldgen");                      // :179
ProcessorHandle  main     = of("main", mainThreadExecutor::tell);              // :180
ProcessorMailbox light    = create(executor, "light");                         // :183
this.queueSorter = new ChunkTaskPriorityQueueSorter(List.of(3 mailboxes), executor, MAX_VALUE);   // :184-186
this.worldgenMailbox   = queueSorter.getProcessor(worldgen, false);            // :187
this.mainThreadMailbox = queueSorter.getProcessor(main, false);                // :188
this.lightEngine = new ThreadedLevelLightEngine(lightChunkGetter, this, hasSkyLight, light, ...); // :189-191
this.distanceManager = new ChunkMap.DistanceManager(executor, mainThreadExecutor);   // :192
this.poiManager = new PoiManager(...);                                         // :194-202
this.setServerViewDistance(viewDistance);                                      // :203
this.worldGenContext = new WorldGenContext(level, generator, structureTemplateManager, lightEngine, mainThreadMailbox);   // :204
```

关于 `ensureStructuresGenerated`（`ServerLevel.java:265`）：它**不阻塞**——

```java
// ChunkGeneratorStructureState.java:170-175
public void ensureStructuresGenerated() {
    if (!this.hasGeneratedPositions) {
        this.generatePositions();      // 返回的 CompletableFuture 被丢弃（内部用 Util.sequence + backgroundExecutor）
        this.hasGeneratedPositions = true;
    }
}
```

真正 `join()` 的是 `getRingPositionsFor`（`:179-181`），用在 `findNearestMapStructure` 路径上。

### 5.5 出生点怎么定（`MinecraftServer.setInitialSpawn`，`:432-477`）

```java
private static void setInitialSpawn(ServerLevel p_177897_, ServerLevelData p_177898_,
                                    boolean p_177899_ /*generateBonusChest*/, boolean p_177900_ /*isDebug*/) {
    if (p_177900_) {
        p_177898_.setSpawn(BlockPos.ZERO.above(80), 0.0F);                              // :434
    } else {
        ServerChunkCache serverchunkcache = p_177897_.getChunkSource();                 // :436
        if (EventHooks.onCreateWorldSpawn(p_177897_, p_177898_)) return;                // :437 ★ Neo 可取消
        ChunkPos chunkpos = new ChunkPos(serverchunkcache.randomState().sampler().findSpawnPosition());   // :438
        int i = serverchunkcache.getGenerator().getSpawnHeight(p_177897_);              // :439
        if (i < p_177897_.getMinBuildHeight()) {
            BlockPos blockpos = chunkpos.getWorldPosition();
            i = p_177897_.getHeight(Heightmap.Types.WORLD_SURFACE, blockpos.getX() + 8, blockpos.getZ() + 8);  // :442
        }
        p_177898_.setSpawn(chunkpos.getWorldPosition().offset(8, i, 8), 0.0F);          // :445 先落一个候选点
        int j1 = 0, j = 0, k = 0, l = -1;

        for (int i1 = 0; i1 < Mth.square(11); i1++) {                                  // :451 121 个 chunk
            if (j1 >= -5 && j1 <= 5 && j >= -5 && j <= 5) {                             // :452 半径 5
                BlockPos blockpos1 = PlayerRespawnLogic.getSpawnPosInChunk(p_177897_,
                        new ChunkPos(chunkpos.x + j1, chunkpos.z + j));                 // :453
                if (blockpos1 != null) { p_177898_.setSpawn(blockpos1, 0.0F); break; }  // :454-456
            }
            // 螺旋游走 :460-467
        }

        if (p_177899_) {                                                                // :470 bonus chest
            p_177897_.registryAccess().registry(Registries.CONFIGURED_FEATURE)
                .flatMap(r -> r.getHolder(MiscOverworldFeatures.BONUS_CHEST))
                .ifPresent(h -> h.value().place(p_177897_, serverchunkcache.getGenerator(),
                                                p_177897_.random, p_177898_.getSpawnPos()));   // :474
        }
    }
}
```

搜索范围：以噪声采样器选出的 chunk 为中心、**半径 5 的 11×11 = 121 个 chunk**，螺旋游走。
> 注意：常量 `SPAWN_POSITION_SEARCH_RADIUS = 5`（`MinecraftServer.java:182`）在 1.21.1 里**没有任何引用**（全树 grep 只命中声明处），实际由 `:451` / `:452` 的字面量 `11` / `5` 决定。数值恰好一致，但不是被那个常量驱动的。

### 5.6 第一次阻塞式区块生成

`:453` 的 `PlayerRespawnLogic.getSpawnPosInChunk` → `getOverworldRespawnPos` → **`Level.getChunk(x, z)`**（`Level.java:195-197`，要求 `ChunkStatus.FULL`）：

```java
public LevelChunk getChunk(int p_46727_, int p_46728_) {
    return (LevelChunk)this.getChunk(p_46727_, p_46728_, ChunkStatus.FULL);           // :196
}
public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean requireChunk) {
    ChunkAccess chunkaccess = this.getChunkSource().getChunk(x, z, status, requireChunk);   // :203
    if (chunkaccess == null && requireChunk) throw new IllegalStateException("Should always be able to create a chunk!");
    return chunkaccess;
}
```

调用点 `PlayerRespawnLogic.java:18`。

**`ServerChunkCache.getChunk`（`ServerChunkCache.java:136-169`）—— 阻塞点**：

```java
public ChunkAccess getChunk(int p_8360_, int p_8361_, ChunkStatus p_330876_, boolean p_8363_) {
    if (Thread.currentThread() != this.mainThread) {                                  // :137
        return CompletableFuture.<ChunkAccess>supplyAsync(() -> this.getChunk(...), this.mainThreadProcessor).join();  // :138
    } else {
        for (int j = 0; j < 4; j++) { /* 4 槽 LRU 缓存检查 */ }                        // :144-151
        ChunkHolder chunkholder = this.getVisibleChunkIfPresent(i);
        if (chunkholder != null && chunkholder.currentlyLoading != null)
            return chunkholder.currentlyLoading;   // :155 ★ Neo：正在加载则绕过 future 链防死锁
        profilerfiller.incrementCounter("getChunkCacheMiss");
        CompletableFuture<ChunkResult<ChunkAccess>> completablefuture =
                this.getChunkFutureMainThread(p_8360_, p_8361_, p_330876_, p_8363_);  // :158
        this.mainThreadProcessor.managedBlock(completablefuture::isDone);             // :159 ★ 阻塞点
        ChunkResult<ChunkAccess> chunkresult = completablefuture.join();              // :160
        ChunkAccess chunkaccess1 = chunkresult.orElse(null);
        if (chunkaccess1 == null && p_8363_)
            throw Util.pauseInIde(new IllegalStateException("Chunk not there when requested: " + chunkresult.getError()));
        this.storeInCache(i, chunkaccess1, p_330876_);                                // :165
        return chunkaccess1;
    }
}
```

`getChunkFutureMainThread`（`:227-249`）：

```java
int j = ChunkLevel.byStatus(p_331599_);                                  // :230  FULL ⇒ 33
if (p_8460_) {
    this.distanceManager.addTicket(TicketType.UNKNOWN, chunkpos, j, chunkpos);   // :233 ★ UNKNOWN ticket
    if (this.chunkAbsent(chunkholder, j)) {
        profilerfiller.push("chunkLoad");
        this.runDistanceManagerUpdates();                                // :237 ★ 同步跑一遍 ticket 传播 + 派发
        ...
        if (this.chunkAbsent(chunkholder, j))
            throw Util.pauseInIde(new IllegalStateException("No chunk holder after ticket has been added"));   // :241
    }
}
return this.chunkAbsent(chunkholder, j) ? GenerationChunkHolder.UNLOADED_CHUNK_FUTURE
        : chunkholder.scheduleChunkGenerationTask(p_331599_, this.chunkMap);   // :246-248
```

`managedBlock` 的覆写在 `ServerChunkCache.java:531-534`：

```java
@Override
public void managedBlock(BooleanSupplier p_347606_) {
    super.managedBlock(() -> MinecraftServer.throwIfFatalException() && p_347606_.getAsBoolean());
}
```

而 `ServerChunkCache.MainThreadExecutor.pollTask`（`:563-570`）每次还会先 `runDistanceManagerUpdates()`。所以这套阻塞是「**在 Server thread 上阻塞，但持续泵 Server thread 的任务队列**」——chunk 完成的回调都在那个队列里，不是纯自旋。

`runDistanceManagerUpdates`（`:278-288`）：

```java
boolean flag  = this.distanceManager.runAllUpdates(this.chunkMap);   // ticket 传播 → 创建/更新 ChunkHolder
boolean flag1 = this.chunkMap.promoteChunkMap();                     // invisible → visible
this.chunkMap.runGenerationTasks();                                  // 派发 ChunkGenerationTask
if (!flag && !flag1) return false; else { this.clearCache(); return true; }
```

`DistanceManager.runAllUpdates`（`DistanceManager.java:108-147`）会 `chunksToUpdateFutures.forEach(h -> h.updateFutures(chunkMap, mainThreadExecutor))` —— 这是 `ChunkHolder.updateFutures`（`ChunkHolder.java:260-313`）的入口，也就是 `prepareAccessibleChunk` / `prepareTickingChunk` / `prepareEntityTickingChunk` 被调度的入口。

### 5.7 已存在区块从磁盘读出的完整链

```
ServerChunkCache.getChunk                                          ServerChunkCache.java:136
 └─ getChunkFutureMainThread                                       ServerChunkCache.java:227
     ├─ distanceManager.addTicket(TicketType.UNKNOWN, pos, level, pos)    :233 → DistanceManager.java:182
     └─ runDistanceManagerUpdates                                    :237 → :278
         ├─ distanceManager.runAllUpdates(chunkMap)                  DistanceManager.java:108
         │    └─ ChunkHolder.updateFutures(chunkMap, mainThreadExecutor)   :119 → ChunkHolder.java:260
         │         └─ ChunkMap.prepareTickingChunk / prepareAccessibleChunk / prepareEntityTickingChunk
         │              ChunkHolder.java:280 / :267 / :297 → ChunkMap.java:671 / :704 / :364
         │              └─ ChunkMap.getChunkRangeFuture            ChunkMap.java:293
         └─ chunkMap.runGenerationTasks()                            ChunkMap.java:665
              └─ runGenerationTask (worldgenMailbox)                 ChunkMap.java:656
                   └─ ChunkGenerationTask.runUntilWait               ChunkGenerationTask.java:41
                        └─ scheduleNextLayer（从 ChunkStatus.EMPTY 起） ChunkGenerationTask.java:57
                             └─ scheduleChunkInLayer                 :131
                                  └─ GenerationChunkHolder.applyStep GenerationChunkHolder.java:60
                                       └─ ChunkMap.applyStep         ChunkMap.java:618
                                            └─ targetStatus == EMPTY ⇒
                                               ChunkMap.scheduleChunkLoad      ChunkMap.java:621 → :548
                                                └─ readChunk                  ChunkMap.java:917
                                                     └─ ChunkStorage.read     ChunkStorage.java:105
                                                          └─ IOWorker.loadAsync           IOWorker.java:137（Util.ioPool()）
                                                               └─ RegionFileStorage.read  RegionFileStorage.java:49
                                                                    ├─ getRegionFile(pos)  :51 → :31（regionCache，上限 256）
                                                                    ├─ regionfile.getChunkDataInputStream(pos)   :54
                                                                    └─ NbtIo.read(datainputstream)               :59
```

```java
// ChunkMap.java:548-566
private CompletableFuture<ChunkAccess> scheduleChunkLoad(ChunkPos p_140418_) {
    return this.readChunk(p_140418_).thenApply(p_214925_ -> p_214925_.filter(p_214928_ -> {
            boolean flag = isChunkDataValid(p_214928_);                        // :550 需要 "Status" TAG_STRING
            if (!flag) LOGGER.error("Chunk file at {} is missing level data, skipping", p_140418_);
            return flag;
        })).thenApplyAsync(p_351774_ -> {
        this.level.getProfiler().incrementCounter("chunkLoad");
        if (p_351774_.isPresent()) {
            ChunkAccess chunkaccess = ChunkSerializer.read(this.level, this.poiManager,
                    this.storageInfo(), p_140418_, p_351774_.get());           // :559 ★ NBT → ChunkAccess
            this.markPosition(p_140418_, chunkaccess.getPersistedStatus().getChunkType());
            return chunkaccess;
        } else {
            return this.createEmptyChunk(p_140418_);                           // :563 new ProtoChunk(...)
        }
    }, this.mainThreadExecutor).exceptionallyAsync(p -> this.handleChunkLoadFailure(p, p_140418_), this.mainThreadExecutor);
}
```

```java
// ChunkMap.java:917-923
private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos p_214964_) {
    return this.read(p_214964_).thenApplyAsync(p -> p.map(this::upgradeChunkTag), Util.backgroundExecutor());
}
private CompoundTag upgradeChunkTag(CompoundTag p_214948_) {
    return this.upgradeChunkTag(this.level.dimension(), this.overworldDataStorage, p_214948_,
                                this.generator().getTypeNameForDataFixer());   // ChunkStorage.java:41-72 (DataFixTypes.CHUNK)
}
```

```java
// RegionFileStorage.java:49-63
@Nullable
public CompoundTag read(ChunkPos p_63707_) throws IOException {
    RegionFile regionfile = this.getRegionFile(p_63707_);                  // :51 按 dimPath/region/r.X.Z.mca 缓存打开
    try (DataInputStream datainputstream = regionfile.getChunkDataInputStream(p_63707_)) {   // :54
        if (datainputstream == null) return null;                          // 该 chunk 在 region 里不存在
        compoundtag = NbtIo.read(datainputstream);                         // :59
    }
    return compoundtag;
}
```

`ChunkSerializer.read`（`ChunkSerializer.java:85-275`）要点：

- `:86-90` 用 NBT 里的 `xPos`/`zPos` 校验位置，不符则 `reportMisplacedChunk`
- `:94` 读 `sections`；`:104-153` 每 section 用 `BLOCK_STATE_CODEC` / biome codec 反序列化 `PalettedContainer`，同时 `:137-152` 把 `BlockLight`/`SkyLight` 灌进 `LevelLightEngine.queueSectionData`
- `:156` `getChunkTypeFromTag` → `LEVELCHUNK` 还是 `PROTOCHUNK`
- `:168-187` `LEVELCHUNK` → `new LevelChunk(...)`，`:249` 返回 `new ImposterProtoChunk((LevelChunk)chunkaccess, false)`
- `:188-212` `PROTOCHUNK` → `new ProtoChunk(...)`，`:207-208` `setPersistedStatus(ChunkStatus.byName(tag.getString("Status")))`
- `:216-232` `setLightCorrect`、heightmaps、`setAllStarts` / `setAllReferences`
- **★ Neo 事件**：`:248` 与 `:272` post `ChunkDataEvent.Load`
- **★ Neo 数据**：`:186-187` 辅助光源 `LevelChunkAuxiliaryLightManager.LIGHT_NBT_KEY`；`:214-215` `readAttachmentsFromNBT`

`ChunkGenerationTask.canLoadWithoutGeneration`（`:82-107`）决定「能不能只加载不生成」：

```java
if (this.targetStatus == ChunkStatus.EMPTY) return true;
ChunkStatus persisted = this.cache.get(pos).getPersistedStatus();
if (persisted != null && !persisted.isBefore(this.targetStatus)) {
    // 检查 LOADING_PYRAMID 依赖半径内的邻居 persistedStatus 是否都够
    return true;
}
return false;   // 否则走 GENERATION_PYRAMID 真正生成
```

`chunkScanner()` 链（供 `StructureCheck` 用）：`ServerChunkCache.chunkScanner()`（`:509-511`）→ `ChunkStorage.chunkScanner()`（`ChunkStorage.java:129-131`）→ `IOWorker.scanChunk`（`:173`）→ `RegionFileStorage.scanChunk`（`:65`）→ region 文件流式扫描，**不构造完整 NBT**。

### 5.8 `prepareLevels` —— 预加载 25 个区块，同步阻塞

`MinecraftServer.java:490-527`：

```java
private void prepareLevels(ChunkProgressListener p_129941_) {
    ServerLevel serverlevel = this.overworld();                                       // :491
    LOGGER.info("Preparing start region for dimension {}", serverlevel.dimension().location());
    BlockPos blockpos = serverlevel.getSharedSpawnPos();                              // :493
    p_129941_.updateSpawnPos(new ChunkPos(blockpos));                                 // :494
    ServerChunkCache serverchunkcache = serverlevel.getChunkSource();                 // :495
    this.nextTickTimeNanos = Util.getNanos();
    serverlevel.setDefaultSpawnPos(blockpos, serverlevel.getSharedSpawnAngle());      // :497 ★ 加 START region ticket
    int i = this.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS);             // :498
    int j = i > 0 ? Mth.square(ChunkProgressListener.calculateDiameter(i)) : 0;       // :499

    while (serverchunkcache.getTickingGenerated() < j) {                              // :501 ★ 同步阻塞
        this.nextTickTimeNanos = Util.getNanos() + PREPARE_LEVELS_DEFAULT_DELAY_NANOS;   // +10ms（:180）
        this.waitUntilNextTick();                                                     // :503
    }

    this.nextTickTimeNanos = Util.getNanos() + PREPARE_LEVELS_DEFAULT_DELAY_NANOS;
    this.waitUntilNextTick();                                                         // :507

    for (ServerLevel serverlevel1 : this.levels.values()) {                            // :509
        ForcedChunksSavedData forcedchunkssaveddata =
            serverlevel1.getDataStorage().get(ForcedChunksSavedData.factory(), "chunks");   // :510
        if (forcedchunksaveddata != null) {
            LongIterator longiterator = forcedchunkssaveddata.getChunks().iterator();
            while (longiterator.hasNext()) {
                long k = longiterator.nextLong();
                ChunkPos chunkpos = new ChunkPos(k);
                serverlevel1.getChunkSource().updateChunkForced(chunkpos, true);        // :517
            }
            net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.reinstatePersistentChunks(serverlevel1, forcedchunkssaveddata);   // :519 ★ Neo
        }
    }

    this.nextTickTimeNanos = Util.getNanos() + PREPARE_LEVELS_DEFAULT_DELAY_NANOS;
    this.waitUntilNextTick();                                                         // :524
    p_129941_.stop();                                                                 // :525
    this.updateMobSpawningFlags();                                                    // :526
}
```

**ticket 类型与数量**：`ServerLevel.setDefaultSpawnPos`（`ServerLevel.java:1369-1387`）：

```java
int i = this.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) + 1;      // :1381
if (i > 1) {
    this.getChunkSource().addRegionTicket(TicketType.START, new ChunkPos(p_8734_), i, Unit.INSTANCE);   // :1383
}
```

`DistanceManager.addRegionTicket`（`:191-199`）：

```java
Ticket<T> ticket = new Ticket<>(t, ChunkLevel.byStatus(FullChunkStatus.FULL) - radius, value, forceTicks);   // :195
this.addTicket(i, ticket);                      // :197
this.tickingTicketsTracker.addTicket(i, ticket);   // :198
```

数值（NeoForge 21.1 默认值）：

- **`RULE_SPAWN_CHUNK_RADIUS` 默认 2，范围 0..32**（`GameRules.java:199-204`）——**NeoForge 把 vanilla 的默认值 10 改成了 2**。
- `setDefaultSpawnPos` 用 `radius = 3`，**ticket level = 33 - 3 = 30**（`ChunkLevel.byStatus(FullChunkStatus.FULL) == 33`，`ChunkLevel.java:50-57`）。
- level 30 意味着：距中心 d 的 chunk level = 30+d → `ENTITY_TICKING`（≤31）d ≤ 1；`BLOCK_TICKING`（≤32）d ≤ 2；`FULL`（≤33）d ≤ 3（`ChunkLevel.java:40-48`）。
- `j = Mth.square(calculateDiameter(2)) = (2*2+1)^2 = 25`（`ChunkProgressListener.calculateDiameter` = `2r+1`，`ChunkProgressListener.java:16-18`）。

**所以默认预加载 = 5×5 = 25 个区块，全部要求到达 BLOCK_TICKING。**

`getTickingGenerated` 的计数来源：`ChunkMap.prepareTickingChunk`（`ChunkMap.java:671-692`）：

```java
completablefuture1.handle((a, b) -> { this.tickingGenerated.getAndIncrement(); return null; });   // :687-690
```

`prepareTickingChunk` 由 `ChunkHolder.updateFutures` 在 ticket level 跨入 `BLOCK_TICKING` 时调用（`ChunkHolder.java:277-283`）。

**同步还是异步**：**同步阻塞（在 Server thread 上）**。

`waitUntilNextTick`（`MinecraftServer.java:824-827`）：

```java
protected void waitUntilNextTick() {
    this.runAllTasks();
    this.managedBlock(() -> !this.haveTime());
}
```

对比 `ChunkMap.saveAllChunks`（`:427`）用的是 `mainThreadExecutor.managedBlock(holder::isReadyForSaving)`，同样是「泵任务式阻塞」。

也就是说：**`prepareLevels` 完全同步地把 Server thread 按在 `loadLevel` 里，直到 25 个出生区块全部到 BLOCK_TICKING**。区块的字节 I/O 与噪声生成跑在 `ioPool` / `backgroundExecutor` 上，完成回调通过 `queueSorter` / mailbox 回到 Server thread，由上面的 `managedBlock` 循环消费。这就是客户端 `doWorldLoad` 期间 Server thread 长时间忙于 `loadLevel`、`isReady` 置位之前客户端只能看到 `LevelLoadingScreen` 的原因。

> 补充：`setDefaultSpawnPos` 还会 `broadcastAll(new ClientboundSetDefaultSpawnPositionPacket(...))`（`:1374`）。`prepareLevels` 时玩家列表还是空的（本地连接在 `Minecraft.java:2103-2110` 才建立），所以此时没有实际广播。

### 5.9 暂停机制的一个细节

`IntegratedServer.tickServer`（`IntegratedServer.java:91-124`）：

```java
flag = this.paused;
this.paused = Minecraft.getInstance().isPaused();
if (flag != this.paused) { /* 日志 */ }
if (this.paused) { this.tickPaused(); }       // :135-139 只给玩家加 TOTAL_WORLD_TIME 统计
else { super.tickServer(p_120049_); }         // :110
```

`Minecraft.isPaused()` 返回 `this.pause`（`Minecraft.java:2557-2559`），`pause` 由「有单人服务器 && 屏幕上/overlay 是 isPauseScreen && 未对局域网开放」决定（`:1228-1234`）。

**`LevelLoadingScreen` 不是 pause screen**，所以世界加载期间服务器不会暂停，`prepareLevels` 的 `waitUntilNextTick` 能正常推进。

---

## 6. 阶段五：登录与配置阶段（NeoForge 网络协商）

单人走 memory connection，这一点决定了后面很多分支。

### 6.1 登录 → 配置阶段

```
C2S LoginStart
→ ServerLoginPacketListenerImpl.handleLoginAcknowledgement              :234
    connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND)
    new ServerConfigurationPacketListenerImpl(...)                       :238
    startConfiguration()                                                 :242
→ ServerConfigurationPacketListenerImpl.startConfiguration()             :74-80
    :76 MinecraftUnregisterPayload(NetworkRegistry.getInitialServerUnregisterChannels())
    :77 MinecraftRegisterPayload(NetworkRegistry.getInitialListeningChannels(flow))
    :78 ModdedNetworkQueryPayload(Map.of())          ← 问客户端有哪些 mod channel
    :79 ClientboundPingPacket(0)                     ← 用来判定 vanilla / NeoForge 连接
→ 客户端 ClientConfigurationPacketListenerImpl.java:175-183
    收到 ModdedNetworkQueryPayload → connectionType = ConnectionType.NEOFORGE
    → NetworkRegistry.onNetworkQuery                      NetworkRegistry.java:493-495
    → 回发 ModdedNetworkQueryPayload.fromRegistry(PAYLOAD_REGISTRATIONS)
→ 服务端 ServerConfigurationPacketListenerImpl.java:116-119
    NetworkRegistry.initializeNeoForgeConnection(this, queries)          NetworkRegistry.java:352-385
      逐协议 NetworkComponentNegotiator.negotiate；失败则 :365 发 ModdedNetworkSetupFailedPayload 并断开
      成功 → :380 ModdedNetworkPayload(setup)，:383 MinecraftRegisterPayload
→ handlePong(id==0)                                                     :126-134 → runConfiguration() :83
```

### 6.2 `runConfiguration` 的任务队列（`:83-99`）

```java
:84 BrandPayload
:94 ConfigurationInitialization.configureEarlyTasks(this, configurationTasks::add)
:95 SynchronizeRegistriesTask
:97 addOptionalTasks()
:98 JoinWorldTask
```

其中 `:97 → :110` 是：

```java
ModLoader.postEventWithReturn(new RegisterConfigurationTasksEvent(this)).getConfigurationTasks()
```

→ `ConfigurationInitialization.configureModdedClient`（`ConfigurationInitialization.java:46-62`）按 channel 注册：`CommonVersionTask`、`CommonRegisterTask`、`SyncConfig`、`RegistryDataMapNegotiation`、`CheckExtensibleEnums`、`CheckFeatureFlags`。

**单人被短路的地方**（`ConfigurationInitialization.java:35-41`）：

```java
if (listener.hasChannel(FrozenRegistrySyncStartPayload.TYPE) &&
    listener.hasChannel(FrozenRegistryPayload.TYPE) &&
    listener.hasChannel(FrozenRegistrySyncCompletedPayload.TYPE) &&
    !listener.getConnection().isMemoryConnection()) {          // ← :38
    tasks.accept(new SyncRegistries());
}
```

memory connection ⇒ `SyncRegistries` 任务**根本不入队**，NeoForge 的注册表快照同步包一个都不发。配套地 `RegistryManager.generateRegistryPackets(true)` 也直接返回空列表（`RegistryManager.java:245-251`）：

```java
public List<...> generateRegistryPackets(boolean isLocal) {
    if (isLocal) return List.of();      // 单人（memory connection）不发
    ...
}
```

同时原版 `ClientboundRegistryDataPacket` 也跳过。

**实际执行的任务顺序**：

```
[SyncRegistries 跳过] → SynchronizeRegistriesTask → ServerResourcePackConfigurationTask?
→ CommonVersionTask → CommonRegisterTask → SyncConfig → RegistryDataMapNegotiation
→ CheckExtensibleEnums → CheckFeatureFlags → JoinWorldTask
```

| 任务 | 发出的 payload |
|---|---|
| `SyncRegistries.run`（`:30-34`，单人跳过） | `FrozenRegistrySyncStartPayload` → N×`FrozenRegistryPayload` → `FrozenRegistrySyncCompletedPayload` |
| `SynchronizeRegistriesTask.sendRegistries`（`:36-45`） | `ClientboundRegistryDataPacket` ×N + **`ClientboundUpdateTagsPacket`（`:44`）** |
| `CommonVersionTask.run`（`:28`） | `CommonVersionPayload` |
| `CommonRegisterTask` | `CommonRegisterPayload` |
| `SyncConfig.run`（`:28-32`）→ `ConfigSync.syncConfigs()` | N×`ConfigFilePayload` |
| `RegistryDataMapNegotiation` | `KnownRegistryDataMapsPayload` → 客户端回 `KnownRegistryDataMapsReplyPayload` |
| `CheckExtensibleEnums`（`:71`） | `ExtensibleEnumDataPayload` |
| `CheckFeatureFlags`（`:50`） | `FeatureFlagDataPayload` |

> **标签（tags）是在配置阶段发的，不在 `placeNewPlayer` 里。** 运行期重载才会再发一次：`PlayerList.reloadResources()`（`PlayerList.java:893-903`，`:899`）。
>
> 所有 `ICustomConfigurationTask` 的 payload 都被 `ICustomConfigurationTask.start()`（`ICustomConfigurationTask.java:44`）统一包成 `new ClientboundCustomPayloadPacket(payload)`。

### 6.3 收尾并进入 play 阶段

服务端 `ServerConfigurationPacketListenerImpl.java:163-199`：

```java
NetworkRegistry.onConfigurationFinished(this)                       // :172
PlayerList.getPlayerForLogin(gameProfile, clientInformation)        // :187 → new ServerPlayer(...)
PlayerList.placeNewPlayer(connection, serverplayer, cookie)         // :188
```

客户端 `ClientConfigurationPacketListenerImpl.java:125-166`：`handleConfigurationFinished` → `RegistryDataCollector.collectGameRegistries` → 新建 `ClientPacketListener` → `NetworkRegistry.onConfigurationFinished`。

`NetworkRegistry.onConfigurationFinished`（`NetworkRegistry.java:789-810`）发 `MinecraftUnregisterPayload` + `MinecraftRegisterPayload`（重组 ad-hoc channel）。

### 6.4 `ServerPlayer` 的创建点

唯一正常路径：

```java
// PlayerList.java:421-423
public ServerPlayer getPlayerForLogin(GameProfile p_215625_, ClientInformation p_302018_) {
    return new ServerPlayer(this.server, this.server.overworld(), p_215625_, p_302018_);
}
```

调用方唯一：`ServerConfigurationPacketListenerImpl.java:187-188`。另一处是测试框架 `GameTestHelper.java:308`。

注意此时维度是 `server.overworld()`，**还没定**；真正的维度在 `placeNewPlayer` 里根据存档数据重设。

`ServerPlayer` 构造（`ServerPlayer.java:284-296`）：

```java
super(p_254435_, p_254435_.getSharedSpawnPos(), p_254435_.getSharedSpawnAngle(), p_253651_);
this.textFilter = p_254143_.createTextFilterForPlayer(this);
this.gameMode = p_254143_.createGameModeForPlayer(this);
this.server = p_254143_;
this.stats = p_254143_.getPlayerList().getPlayerStats(this);
this.advancements = p_254143_.getPlayerList().getPlayerAdvancements(this);
this.moveTo(this.adjustSpawnLocation(p_254435_, p_254435_.getSharedSpawnPos()).getBottomCenter(), 0.0F, 0.0F);
this.updateOptions(p_301997_);
this.object = null;
```

数据包监听器的构造（**`chunkSender` 诞生的地方**）：

```java
// ServerGamePacketListenerImpl.java:239-247
public ServerGamePacketListenerImpl(MinecraftServer p_9770_, Connection p_9771_, ServerPlayer p_9772_, CommonListenerCookie p_301978_) {
    super(p_9770_, p_9771_, p_301978_);
    this.chunkSender = new PlayerChunkSender(p_9771_.isMemoryConnection());   // :241 ★ 单机 = true
    this.player = p_9772_;
    p_9772_.connection = this;                                                // :243
}
```

---

## 7. 阶段六：`PlayerList.placeNewPlayer` 与服务端推区块

### 7.1 发包顺序

`net/minecraft/server/players/PlayerList.java:140-273`，严格按代码顺序：

| # | 行 | 包 / 动作 | 备注 |
|---|---|---|---|
| — | 152 | `this.load(p_11263_)` | 读玩家 NBT；单机 owner 路径 fire `PlayerEvent.LoadFromFile` |
| — | 166 | `p_11263_.setServerLevel(serverlevel1)` | 定维度 |
| — | 178 | `p_11263_.loadGameTypes(...)` | 定游戏模式（不发包） |
| — | 179-182 | `new ServerGamePacketListenerImpl(...)`；`setupInboundProtocol` | 无包 |
| 1 | **187-201** | **`ClientboundLoginPacket`** | 见 §7.2 |
| 2 | 202 | `ClientboundChangeDifficultyPacket` | |
| 3 | 203 | `ClientboundPlayerAbilitiesPacket` | |
| 4 | 204 | `ClientboundSetCarriedItemPacket` | 1.21.1 **没有** `ClientboundSetHeldItemPacket` |
| — | 205 | **★ Neo: `OnDatapackSyncEvent(this, p_11263_)`** | mod 常在这里补发自己的同步包 |
| 5 | 206 | `ClientboundUpdateRecipesPacket` | |
| 6 | 207 | `sendPlayerPermissionLevel` | → `ClientboundEntityEventPacket(player, 24..28)` + `sendCommands(player)`（`PlayerList.java:619-634`） |
| 7 | 209 | `getRecipeBook().sendInitialRecipeBook(player)` | `ClientboundRecipeBookAddPacket` + `ClientboundRecipeBookSettingsPacket` |
| 8 | 210 | `updateEntireScoreboard(serverlevel1.getScoreboard(), player)` | 队伍包 + 目标包（`:275-292`） |
| — | 211 | `server.invalidateStatus()` | |
| 9 | 219 | `broadcastSystemMessage(...)` | 发给**其他人**「xxx joined the game」 |
| 10 | **220** | `teleport(x, y, z, yRot, xRot)` | → `ClientboundPlayerPositionPacket`（并设 `awaitingPositionFromClient`） |
| 11 | 221-224 | `sendServerStatus(serverstatus)` | 仅当 `!cookie.transferred()`；→ `ClientboundServerDataPacket` |
| 12 | 226 | `ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(this.players)` | 把「已有的其他人」告诉新玩家（此时 `players` **还不含自己**） |
| — | 227-228 | `this.players.add(...)`; `playersByUUID.put(...)` | |
| 13 | 229 | `broadcastAll(PlayerInfoUpdate(List.of(player)))` | 把新玩家广播给所有人 |
| 14 | **230** | **`sendLevelInfo(player, serverlevel1)`** | 见 §7.3 |
| — | **231** | **`serverlevel1.addNewPlayer(p_11263_)`** | ★ 区块追踪从这里开始（§7.4） |
| — | 232 | `server.getCustomBossEvents().onPlayerConnect(player)` | |
| 15 | 233 | `sendActivePlayerEffects(player)` | 每个效果一个 `ClientboundUpdateMobEffectPacket`（`:520-528`） |
| — | 234-267 | RootVehicle 重新骑乘 | 无包 |
| — | 269 | `p_11263_.initInventoryMenu()` | 无包，只挂 SlotListener（`ServerPlayer.java:463-470`） |
| 16 | 271 | **★ Neo: `AttachmentSync.syncInitialPlayerAttachments(player)`** | `ClientboundCustomPayloadPacket` |
| 17 | **272** | **★ Neo: `EventHooks.firePlayerLoggedIn(player)` → `PlayerEvent.PlayerLoggedInEvent`** | 整个进服流程的**最后一个动作** |

### 7.2 `ClientboundLoginPacket` 携带了什么

`ClientboundLoginPacket.java:14-26`：

```java
public record ClientboundLoginPacket(
    int playerId,
    boolean hardcore,
    Set<ResourceKey<Level>> levels,       // 服务器里所有维度
    int maxPlayers,
    int chunkRadius,                      // == PlayerList.getViewDistance()
    int simulationDistance,
    boolean reducedDebugInfo,
    boolean showDeathScreen,
    boolean doLimitedCrafting,
    CommonPlayerSpawnInfo commonPlayerSpawnInfo,      // ★
    boolean enforcesSecureChat
) implements Packet<ClientGamePacketListener>
```

`CommonPlayerSpawnInfo.java:15-25`：

```java
public record CommonPlayerSpawnInfo(
    Holder<DimensionType> dimensionType,
    ResourceKey<Level> dimension,        // ★ 我在哪个维度
    long seed,                            // ★ 世界种子（已混淆）
    GameType gameType,                    // ★ 当前游戏模式
    @Nullable GameType previousGameType,
    boolean isDebug,
    boolean isFlat,
    Optional<GlobalPos> lastDeathLocation,
    int portalCooldown
)
```

服务端填充（`ServerPlayer.java:2075-2088`）：

```java
public CommonPlayerSpawnInfo createCommonSpawnInfo(ServerLevel p_294169_) {
    return new CommonPlayerSpawnInfo(
        p_294169_.dimensionTypeRegistration(),
        p_294169_.dimension(),
        BiomeManager.obfuscateSeed(p_294169_.getSeed()),   // ★ 种子被混淆
        this.gameMode.getGameModeForPlayer(),
        this.gameMode.getPreviousGameModeForPlayer(),
        p_294169_.isDebug(), p_294169_.isFlat(),
        this.getLastDeathLocation(), this.getPortalCooldown());
}
```

**客户端从这一个包就知道「我在哪个维度、什么种子、什么游戏模式」。**

### 7.3 `sendLevelInfo` 的顺序（`PlayerList.java:701-720`）

```java
701  WorldBorder worldborder = this.server.overworld().getWorldBorder();
703  send(new ClientboundInitializeBorderPacket(worldborder));
704  if (connection.hasChannel(ClientboundCustomSetTimePayload.TYPE))          // ★ Neo 分支
705      send(new ClientboundCustomSetTimePayload(...));                        //  亚 tick 昼夜
707  else send(new ClientboundSetTimePacket(gameTime, dayTime, daylight));     //  vanilla 分支
710  send(new ClientboundSetDefaultSpawnPositionPacket(sharedSpawnPos, sharedSpawnAngle));
711  if (level.isRaining()) { 712 START_RAINING; 713 RAIN_LEVEL_CHANGE; 714 THUNDER_LEVEL_CHANGE; }
717  send(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));   // ★
718  this.server.tickRateManager().updateJoiningPlayer(p_11230_);
719  AttachmentSync.syncInitialLevelAttachments(p_11231_, p_11230_);            // ★ Neo
```

**第 717 行的 `LEVEL_CHUNKS_LOAD_START` 就是服务端发出的「你可以开始收区块了」信号。**

### 7.4 关于 `ClientboundRespawnPacket` —— 需要纠正一个常见印象

**1.21.1 首次进入世界不发 `ClientboundRespawnPacket`。** 我逐行读了 `PlayerList.java:140-273`，没有任何 respawn 包；初始世界信息完全靠 `ClientboundLoginPacket` 的 `CommonPlayerSpawnInfo`。这是 1.20.2 起的重构结果。

全树仅有两处发送点：

```java
// 1) 死亡/打完末影龙复活
// PlayerList.java:478-481
byte b0 = (byte)(p_11238_ ? 1 : 0);
serverplayer.connection.send(new ClientboundRespawnPacket(serverplayer.createCommonSpawnInfo(serverlevel1), b0));

// 2) 跨维度传送
// ServerPlayer.java:886
this.connection.send(new ClientboundRespawnPacket(this.createCommonSpawnInfo(serverlevel), (byte)3));
```

来源：

- `ServerGamePacketListenerImpl.java:1627-1637` → `handleClientCommand(PERFORM_RESPAWN)`：打完末影龙 → `respawn(player, true, CHANGED_DIMENSION)` → `dataToKeep = 1`；死亡 → `respawn(player, false, KILLED)` → `dataToKeep = 0`。
- `ServerPlayer.changeDimension(DimensionTransition)`（`ServerPlayer.java:866-920`）→ `dataToKeep = 3`。

`flags` 位掩码定义（`ClientboundRespawnPacket.java:8-14, 34-36`）：

```java
public static final byte KEEP_ATTRIBUTE_MODIFIERS = 1;
public static final byte KEEP_ENTITY_DATA         = 2;
public static final byte KEEP_ALL_DATA            = 3;
public boolean shouldKeep(byte p_263573_) { return (this.dataToKeep & p_263573_) != 0; }
```

| 值 | 场景 | 语义 |
|---|---|---|
| `0` | 死亡重生 | 浮点属性重置为 base、实体数据不继承 |
| `1` | 打完末影龙复活 | 保留 attribute modifiers，**实体数据不保留**；但 `restoreFrom(player, true)`（`ServerPlayer.java:1414-1427`）会额外搬运背包/血量/饱食度/药水/经验/分数 |
| `3` | 跨维度 | 客户端 `shouldKeep(2)` 复制实体同步数据、`shouldKeep(1)` 复制 attributes |

客户端处理：`ClientPacketListener.java:1102-1168`。**「进世界时会先收 Login 再收 Respawn」是 1.20.2 之前的印象，1.21.1 不成立。**

### 7.5 区块怎么开始推

**总开关：`placeNewPlayer:231` 的 `serverlevel1.addNewPlayer(player)`**：

```
ServerLevel.addNewPlayer (ServerLevel.java:909-911)
  → ServerLevel.addPlayer (ServerLevel.java:917-928)
      :918  ★ Neo: NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(player, this))   // 可取消
      :926  this.entityManager.addNewEntityWithoutEvent(player)
  → PersistentEntitySectionManager.addEntityWithoutEvent (PersistentEntitySectionManager.java:83-105)
      :95-98  getEffectiveStatus(...) → Player.isAlwaysTicking()==true (Player.java:2165-2168)
              → Visibility.TICKING → isAccessible() → startTracking(player)
  → ServerLevel.EntityCallbacks.onTrackingStart (ServerLevel.java:1742-1747)
      :1743  ServerLevel.this.getChunkSource().addEntity(player)
  → ServerChunkCache.addEntity (ServerChunkCache.java:471-472)
  → ChunkMap.addEntity (ChunkMap.java:1080-1104)
      :1092-1093  if (entity instanceof ServerPlayer sp) this.updatePlayerStatus(sp, true);
      :1095-1099  for (TrackedEntity te : entityMap.values()) te.updatePlayer(sp);   // 已存在的实体开始发给该玩家
  → ChunkMap.updatePlayerStatus (ChunkMap.java:969-980)
      :973  playerMap.addPlayer(player, skipSpectator)
      :974  updatePlayerPos(player)
      :976  distanceManager.addPlayer(SectionPos.of(player), player)     ★ 注册 PLAYER ticket
      :979  player.setChunkTrackingView(ChunkTrackingView.EMPTY)
      :980  this.updateChunkTracking(player)                            ★
  → ChunkMap.updateChunkTracking (ChunkMap.java:1034-1044)
      :1043 applyChunkTrackingView(player, ChunkTrackingView.of(chunkpos, getPlayerViewDistance(player)))
  → ChunkMap.applyChunkTrackingView (ChunkMap.java:1046-1063)
      :1054-1055 send(new ClientboundSetChunkCacheCenterPacket(center.x, center.z))   // ★ 先告诉客户端"中心区块"
      :1058-1060 ChunkTrackingView.difference(old, new,
                     pos -> markChunkPendingToSend(player, pos),
                     pos -> dropChunk(player, pos))
      :1061 player.setChunkTrackingView(new)
```

`markChunkPendingToSend`（`ChunkMap.java:817-827`）：

```java
private void markChunkPendingToSend(ServerPlayer p_294638_, ChunkPos p_296183_) {
    LevelChunk levelchunk = this.getChunkToSend(p_296183_.toLong());
    if (levelchunk != null) markChunkPendingToSend(p_294638_, levelchunk);
}
private static void markChunkPendingToSend(ServerPlayer p_295834_, LevelChunk p_296281_) {
    p_295834_.connection.chunkSender.markChunkPendingToSend(p_296281_);
    net.neoforged.neoforge.event.EventHooks.fireChunkWatch(p_295834_, p_296281_, p_295834_.serverLevel());   // ★ ChunkWatchEvent.Watch
}
```

**如果那一刻区块还没生成好（`getChunkToSend` 返回 null），这个区块不会被标记**，而是之后补：

```java
// ChunkMap.java:694-701
private void onChunkReadyToSend(LevelChunk p_296003_) {
    ChunkPos chunkpos = p_296003_.getPos();
    for (ServerPlayer serverplayer : this.playerMap.getAllPlayers()) {
        if (serverplayer.getChunkTrackingView().contains(chunkpos)) markChunkPendingToSend(serverplayer, p_296003_);
    }
}
```

（由 `prepareTickingChunk` 在区块进入 FULL 时回调，`ChunkMap.java:664-693`。）

玩家移动时重算：`ChunkMap.move(ServerPlayer)`（`:997-1032`，`:1030` 再次 `updateChunkTracking`），由 `ServerGamePacketListenerImpl.handleMovePlayer` 的 `:955`（及 `:451`/`:876`）经 `serverLevel().getChunkSource().move(player)` 触发。

### 7.6 三层组件的职责划分

| 组件 | 文件 | 职责 |
|---|---|---|
| `ChunkMap.DistanceManager` | `ChunkMap.java:1225-1247`（实现 `DistanceManager.java`） | **只决定「区块要不要被加载/生成/实体 tick」**，与「发给谁」无关。`addPlayer`（`:227-234`）注册 `TicketType.PLAYER` region ticket（level = `ChunkLevel.byStatus(ENTITY_TICKING) - simulationDistance`，`:249-251`），驱动 `ChunkTicketTracker`（生成半径）/ `naturalSpawnChunkCounter`（8）/ `PlayerTicketTracker`（32）/ `tickingTicketsTracker` |
| `ChunkHolder` | `ChunkHolder.java:32-72` | 单区块的状态机与回调。`ChunkLevel.FULL_CHUNK_LEVEL = 33`、`ENTITY_TICKING_LEVEL = 31`（`ChunkLevel.java:10-13`）；`getChunkToSend()` 就是「已 FULL 且可序列化」 |
| `PlayerChunkSender` | `PlayerChunkSender.java`（每连接一个，`ServerGamePacketListenerImpl.java:241`） | **只负责「待发队列 + 每 tick 限流 + 组批发包」** |

### 7.7 每 tick 发多少区块（限流）

`PlayerChunkSender.java:25-34`：

```java
public static final float MIN_CHUNKS_PER_TICK = 0.01F;
public static final float MAX_CHUNKS_PER_TICK = 64.0F;      // ★ 上限
private static final float START_CHUNKS_PER_TICK = 9.0F;
private static final int   MAX_UNACKNOWLEDGED_BATCHES = 10;
private final LongSet pendingChunks = new LongOpenHashSet();
private final boolean memoryConnection;
private float desiredChunksPerTick = 9.0F;                   // 初始 9
private float batchQuota;
private int unacknowledgedBatches;
private int maxUnacknowledgedBatches = 1;                    // 初始只允许 1 个未确认批次
```

`sendNextChunks`（`:50-74`）：

```java
if (this.unacknowledgedBatches < this.maxUnacknowledgedBatches) {
    float f = Math.max(1.0F, this.desiredChunksPerTick);
    this.batchQuota = Math.min(this.batchQuota + this.desiredChunksPerTick, f);
    if (!(this.batchQuota < 1.0F)) {
        ... collectChunksToSend(...)
        servergamepacketlistenerimpl.send(ClientboundChunkBatchStartPacket.INSTANCE);
        for (LevelChunk c : list) sendChunk(servergamepacketlistenerimpl, serverlevel, c);
        servergamepacketlistenerimpl.send(new ClientboundChunkBatchFinishedPacket(list.size()));
        this.batchQuota -= (float)list.size();
    }
}
```

调用点**唯一**：`MinecraftServer.java:1064-1069`（`tickChildren` 末尾）：

```java
this.profiler.popPush("send chunks");
for (ServerPlayer serverplayer : this.playerList.getPlayers()) {
    serverplayer.connection.chunkSender.sendNextChunks(serverplayer);
    serverplayer.connection.resumeFlushing();       // 与 :1019 的 suspendFlushing 配对
}
```

即：`tickChildren` 开头 `:1019` 对所有玩家 `suspendFlushing()`，整 tick 的包先攒在 channel 里，最后统一 flush。

**单机的重要细节**（`collectChunksToSend`，`:85-111`）：

```java
int i = Mth.floor(this.batchQuota);
if (!this.memoryConnection && this.pendingChunks.size() > i) {
    // 按到玩家的距离取最近 i 个  ← 网络玩家走这条，受 batchQuota 限流
} else {
    // 全取  ← memoryConnection == true（单人集成服务器）走这条，不按 i 截断
}
```

**所以单人游戏第一次 `sendNextChunks` 会一次性把整个视距内所有已就绪区块打包发出**（batchQuota 被减成很负，随后按 9/tick 恢复，期间不再发包，直到新区块陆续加载）。多人网络连接才是「初始 9/tick，客户端逐批回执后提速到 64/tick 上限」。

客户端回执：

- `ClientPacketListener.java:2303-2311`：`handleChunkBatchStart → chunkBatchSizeCalculator.onBatchStart()`；`handleChunkBatchFinished → onBatchFinished(size)` 然后 `send(new ServerboundChunkBatchReceivedPacket(desiredChunksPerTick))`
- `ServerGamePacketListenerImpl.java:1850-1853 handleChunkBatchReceived` → `chunkSender.onChunkBatchReceivedByClient(desired)`（`PlayerChunkSender.java:113-121`）：`desiredChunksPerTick = clamp(value, 0.01, 64)`，`maxUnacknowledgedBatches` 提到 10

`dropChunk`（`:44-48`）→ 发 `ClientboundForgetLevelChunkPacket`（若还在 pending 里则只从队列移除）。

`ClientboundSetChunkCacheCenterPacket` 只在中心区块变化时发（`ChunkMap.java:1054-1055`），是客户端 chunk storage 的中心点，`updateChunkTracking:1037-1041` 有短路判断避免重复发。

### 7.8 `ClientboundLevelChunkWithLightPacket` 在哪构造

`PlayerChunkSender.sendChunk`（**唯一构造点**）：

```java
// PlayerChunkSender.java:76-83
private static void sendChunk(ServerGamePacketListenerImpl p_295237_, ServerLevel p_294963_, LevelChunk p_295144_) {
    p_295237_.send(p_295144_.getAuxLightManager(p_295144_.getPos()).sendLightDataTo(
            new ClientboundLevelChunkWithLightPacket(p_295144_, p_294963_.getLightEngine(), null, null)
    ));
    ChunkPos chunkpos = p_295144_.getPos();
    DebugPackets.sendPoiPacketsForChunk(p_294963_, chunkpos);
    net.neoforged.neoforge.event.EventHooks.fireChunkSent(p_295237_.player, p_295144_, p_294963_);   // ★ ChunkWatchEvent.Sent
}
```

包结构（`ClientboundLevelChunkWithLightPacket.java:22-28`）：

```java
public ClientboundLevelChunkWithLightPacket(LevelChunk chunk, LevelLightEngine engine,
        @Nullable BitSet blockLightMask, @Nullable BitSet skyLightMask) {
    this.x = chunkpos.x; this.z = chunkpos.z;
    this.chunkData = new ClientboundLevelChunkPacketData(chunk);          // 方块数据(高度图/生物群系/方块实体)
    this.lightData = new ClientboundLightUpdatePacketData(chunkpos, engine, blockLightMask, skyLightMask);   // 光照
}
```

- **光照**：两个 mask 传 `null`，语义为「该区块全部光照段」，**不做增量差量**。
- **★ NeoForge 额外光照**：`LevelChunk.getAuxLightManager(pos)`（`LevelChunk.java:681`）→ `LevelChunkAuxiliaryLightManager.sendLightDataTo`（`LevelChunkAuxiliaryLightManager.java:86-89`）：

  ```java
  return new ClientboundBundlePacket(List.of(chunkPacket,
          new ClientboundCustomPayloadPacket(new AuxiliaryLightDataPayload(owner.getPos(), Map.copyOf(lights)))));
  ```

  **实际发出去的是 `ClientboundBundlePacket`，里面包着 `ClientboundLevelChunkWithLightPacket` + 一个 `ClientboundCustomPayloadPacket`（mod 动态光照）。**
- 序列化本身在 `ClientboundLevelChunkPacketData(LevelChunk)` → 内部 `ChunkSerializer.write`。
- 分发顺序：`ClientboundChunkBatchStartPacket` → 若干 `ClientboundBundlePacket(ChunkWithLight + AuxLight)` → `ClientboundChunkBatchFinishedPacket(size)`（`PlayerChunkSender.java:62-68`）。

---

## 8. 阶段七：客户端建世界与收区块（主线程）

### 8.1 网络线程 → 主线程的桥

`Connection.channelRead0`（`Connection.java:183`）→ `genericsFtw`（`:206`）→ `packet.handle(listener)`。

每个 handler 第一行都是 `PacketUtils.ensureRunningOnSameThread`（`PacketUtils.java:21-40`）：

```java
if (!p_131366_.isSameThread()) {
    p_131366_.executeIfPossible(() -> { ... p_131364_.handle(p_131365_); ... });       // :23-26
    throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;               // :38
}
```

这个异常在 `channelRead0` 里被空 catch 掉（`Connection.java:192`）。

主线程在 `Minecraft.runTick`（`:1135`）第 `:1155` 行 `runAllTasks()`（`BlockableEventLoop.java:110-113`，`while (pollTask())`）里**一次性抽干队列**——位置在 tick 循环（`:1159-1162`）之前、渲染（`:1192-1198`）之前。**所以每个 tick 的所有包是成批应用的。**

客户端每 tick 顺序（`Minecraft.tick()`，`:1785`）：

| 行 | 内容 |
|---|---|
| 1787 | `ClientHooks.fireClientTickPre()` → `ClientTickEvent.Pre` ★ Neo |
| 1804-1806 | `gameMode.tick()` → `MultiPlayerGameMode.tick`（`:269`）→ `Connection.tick()`（`:409`）→ `TickablePacketListener.tick()` → `ClientPacketListener.tick()`（`:2466`） |
| 1828 | `this.screen.tick()`（`ReceivingLevelScreen.tick`） |
| 1846 / 1851 | `gameRenderer.tick()` / `levelRenderer.tick()` |
| 1856 | `this.level.tickEntities()` |
| 1882 | `this.level.tick(() -> true)` |
| 1915 | `fireClientTickPost()` → `ClientTickEvent.Post` ★ Neo |

### 8.2 `ClientPacketListener.handleLogin`（`:393-463`）

```
395  PacketUtils.ensureRunningOnSameThread(...)          // 网络线程 → 主线程
396  this.minecraft.gameMode = new MultiPlayerGameMode(this.minecraft, this);
397  CommonPlayerSpawnInfo commonplayerspawninfo = p_105030_.commonPlayerSpawnInfo();
398-400  levels（服务端发来的维度列表，shuffle 后存 this.levels）
401-404  dimension key / dimensionType holder / serverChunkRadius / serverSimulationDistance
405-406  isDebug, isFlat
407  ClientLevel.ClientLevelData data = new ClientLevel.ClientLevelData(Difficulty.NORMAL, hardcore, isFlat);
409-420  this.level = new ClientLevel(this, data, dimKey, dimType,
                                    serverChunkRadius, serverSimulationDistance,
                                    this.minecraft::getProfiler, this.minecraft.levelRenderer,
                                    isDebug, commonplayerspawninfo.seed());
421  this.minecraft.setLevel(this.level, ReceivingLevelScreen.Reason.OTHER);
422-428  if (player == null) this.minecraft.player = gameMode.createPlayer(this.level, new StatsCounter(), new ClientRecipeBook());
424      this.minecraft.player.setYRot(-180.0F);
430  this.minecraft.debugRenderer.clear();
431  this.minecraft.player.resetPos();
432  ClientHooks.firePlayerLogin(...)                      // ★ ClientPlayerNetworkEvent.LoggingIn
433  this.minecraft.player.setId(p_105030_.playerId());
434  this.level.addEntity(this.minecraft.player);          // ★ EntityJoinLevelEvent
435  this.minecraft.player.input = new KeyboardInput(this.minecraft.options);
436  this.minecraft.gameMode.adjustPlayer(this.minecraft.player);
437  this.minecraft.cameraEntity = this.minecraft.player;
438  this.startWaitingForNewLevel(this.minecraft.player, this.level, ReceivingLevelScreen.Reason.OTHER, null, null);
439-443  reducedDebugInfo / showDeathScreen / doLimitedCrafting / lastDeathLocation / portalCooldown
444  this.minecraft.gameMode.setLocalMode(gameType, previousGameType)   // ★ 游戏模式最终在这里生效
```

**`ClientLevel` 全树只在 `:409`（登录）和 `:1119`（`handleRespawn`，仅当维度变化）创建。**

`ClientLevel` 构造（`ClientLevel.java:180-204`）：

```java
:192  super(levelData, dimKey, registryAccess, dimType, profiler, true, isDebug, seed, 1000000);
:194  this.chunkSource = new ClientChunkCache(this, p_205509_);
:202  this.prepareWeather();
:203  NeoForge.EVENT_BUS.post(new LevelEvent.Load(this));      // ★ 比 Minecraft.setLevel 还早
```

`Minecraft.setLevel`（`Minecraft.java:2113-2124`）：

```java
public void setLevel(ClientLevel p_91157_, ReceivingLevelScreen.Reason p_341652_) {
    if (this.level != null) NeoForge.EVENT_BUS.post(new LevelEvent.Unload(this.level));         // :2114 ★ Neo
    this.updateScreenAndTick(DimensionTransitionScreenManager.getScreenFromLevel(p_91157_, this.level)
            .create(() -> false, p_341652_));                                                    // :2115 ★
    this.level = p_91157_;                                                                       // :2116
    this.updateLevelInEngines(p_91157_);                                                         // :2117
    if (!this.isLocalServer) { ... }                                                             // :2118-2123
}
```

`updateLevelInEngines`（`:2238-2243`）= `levelRenderer.setLevel` / `particleEngine.setLevel` / `blockEntityRenderDispatcher.setLevel` / `updateTitle()`。

**注意 `:2115` 传入的 `BooleanSupplier` 是 `() -> false`** —— 这块屏**永远不会自己关**，只能靠 30 秒超时。它其实只活了 `handleLogin` 内部的十几行，随后被 `:438` 的 `startWaitingForNewLevel` 换成真正的那块（`:1431-1434`）：

```java
this.levelLoadStatusManager = new LevelLoadStatusManager(p_304688_, p_304528_, this.minecraft.levelRenderer);
this.minecraft.setScreen(DimensionTransitionScreenManager.getScreen(toDim, fromDim)
        .create(this.levelLoadStatusManager::levelReady, p_341690_));
```

登录时 `Minecraft.level == null`，所以 `getScreenFromLevel` 走 `DimensionTransitionScreenManager.java:31-32`（`source == null` 分支）→ `getScreen(null, null)` → 默认工厂 `ReceivingLevelScreen::new`（`:52`）。

> **所以「进世界时客户端重建了世界/重建了屏」只是观感：代码上 `ClientLevel` 只建了一次，`ReceivingLevelScreen` 设了两次。**

### 8.3 `LocalPlayer` 怎么进 `ClientLevel`

**创建**：`handleLogin:423` → `MultiPlayerGameMode.createPlayer` → `new LocalPlayer(minecraft, level, connection, stats, recipeBook, shift, sprint)`（`LocalPlayer.java:141-160`）。重生走 `handleRespawn:1141-1147`。

**加入 level**：`handleLogin:434` `this.level.addEntity(this.minecraft.player)`

```java
// ClientLevel.java:345-350
public void addEntity(Entity p_104741_) {
    if (NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(p_104741_, this)).isCanceled()) return;   // :346 ★
    this.removeEntity(p_104741_.getId(), Entity.RemovalReason.DISCARDED);
    this.entityStorage.addEntity(p_104741_);
    p_104741_.onAddedToLevel();
}
```

`TransientEntitySectionManager.addEntity`（`:56-67`）：

```java
:63  this.callbacks.onTrackingStart(p_157654_);   // → ClientLevel.EntityCallbacks.onTrackingStart（:1097-1106），进 players 列表
:64  if (p_157654_.isAlwaysTicking() || entitysection.getStatus().isTicking()) {
:65      this.callbacks.onTickingStart(p_157654_);   // → tickingEntities.add(entity)（ClientLevel.java:1089-1091）
```

**关键：`Player.isAlwaysTicking()` 返回 `true`**（`Player.java:2165-2168`；`Entity` 返回 false，`Entity.java:3504-3507`）。所以 LocalPlayer 在 `:434` 那一刻就进了 `tickingEntities`，**不需要等任何区块包**。

**顺序**：LocalPlayer 在本方法内加入；其他玩家/实体随后才到（服务端 `PlayerInfoUpdate` 在 `PlayerList:226-229`，`ClientboundAddEntityPacket` 更晚），走 `handleAddEntity`（`:466-476`）→ `ClientLevel.addEntity`。所以 **LocalPlayer 一定在最前面**。

**第一个 tick 前的状态**：

- 已经在 tick。`Minecraft.tick():1856` → `ClientLevel.tickEntities`（`:273-283`）→ `tickNonPassenger`（`:290-304`，`:295` `EntityTickEvent.Pre`，`:296` `entity.tick()`，`:297` `EntityTickEvent.Post`）→ `LocalPlayer.tick`（`:202-221`）。
- `LocalPlayer.tick` 的门槛在客户端等于没有：

  ```java
  203  if (this.level().hasChunkAt(this.getBlockX(), this.getBlockZ())) {
  204      super.tick();
  ```

  `hasChunkAt` → `LevelReader.hasChunkAt`（`:176-179`）→ `ClientLevel.hasChunk`，而 **`ClientLevel.hasChunk` 恒返回 true**（`ClientLevel.java:336-339`）。所以物理（重力、`aiStep`、`move`）每 tick 都在跑。
- **不能移动、不能转头**：`KeyboardHandler.keyPress`（`KeyboardHandler.java:429`）里 `flag4 = this.minecraft.screen == null`，只有 `flag4` 为真时才 `KeyMapping.set(key, true)`（`:476-483`）。`ReceivingLevelScreen` 开着时按键状态不写入任何 `KeyMapping`，`KeyboardInput` 恒为中立；鼠标也被路由给 screen。
- **但重力不受保护**：`LocalPlayer.resetPos()`（`:653-669`）只把 y 向上挪到第一个无碰撞位置、清零 `deltaMovement`、`setXRot(0)`；随后每 tick 的 `super.tick()` 照常施加重力。缺失区块在客户端被当作空气（`ClientChunkCache.getChunk` `:72-82` 回落到 `emptyChunk`），所以**代码上玩家确实可能在「Loading terrain」期间下落**。实践中被服务端的 `ClientboundPlayerPositionPacket` 覆盖——`handleMovePlayer`（`:628-699`）设置位置/朝向并回 `ServerboundAcceptTeleportationPacket` + `ServerboundMovePlayerPacket.PosRot`（`:697-698`），而服务端在 `PlayerList:220` 紧接着就发了这个包。
- `aiStep` 里唯一显式识别这块屏的地方：`LocalPlayer.java:677` `if (!(this.minecraft.screen instanceof ReceivingLevelScreen)) { ...processPortalCooldown... }` —— 只屏蔽传送门眩晕，不屏蔽物理。

### 8.4 区块与光照到达

`ClientPacketListener.handleLevelChunkWithLight`（`:707-721`）：

```java
709  PacketUtils.ensureRunningOnSameThread(p_194241_, this, this.minecraft);
712  this.updateLevelChunk(i, j, p_194241_.getChunkData());          // 方块+方块实体，立刻
713  ClientboundLightUpdatePacketData lightdata = p_194241_.getLightData();
714  this.level.queueLightUpdate(() -> {                              // ★ 光照延后
715      this.applyLightData(i, j, lightdata);
716      LevelChunk levelchunk = this.level.getChunkSource().getChunk(i, j, false);
717      if (levelchunk != null) this.enableChunkLight(levelchunk, i, j);
718  });
```

`ClientChunkCache.replaceWithPacketData`（`ClientChunkCache.java:104-130`）：

```java
111-113  storage.inRange 不通过 → LOGGER.warn + return null
115-117  index / 旧 chunk / ChunkPos
118-121  !isValidChunk(...) → levelchunk = new LevelChunk(this.level, chunkpos);
                               levelchunk.replaceWithPacketData(buf, heightmaps, beConsumer);
                               this.storage.replace(i, levelchunk);
122-124  否则就地 replaceWithPacketData
126      this.level.onChunkLoaded(chunkpos);
127      NeoForge.EVENT_BUS.post(new ChunkEvent.Load(levelchunk, false));      // ★ Neo
128      return levelchunk;
```

`Storage.replace`（`:201-211`）里被换出的 chunk 走 `ClientLevel.unload`（`:320-324`）→ `clearAllBlockEntities` + `lightEngine.setLightEnabled(pos, false)` + `entityStorage.stopTicking`。

`ClientLevel.onChunkLoaded`（`:326-330`）是区块「真正参与逻辑」的点：

```java
this.tintCaches.forEach((k, v) -> v.invalidateForChunk(pos.x, pos.z));
this.entityStorage.startTicking(p_171650_);        // 该区块内实体进入 tickingEntities
this.levelRenderer.onChunkLoaded(p_171650_);       // → SectionOcclusionGraph.onChunkLoaded (LevelRenderer.java:3543)
```

**光照走的是每帧才抽的队列**：`queueLightUpdate`（`ClientLevel.java:206-208`）只是 `lightUpdateQueue.add(runnable)`。真正执行在 `pollLightUpdates`（`:210-222`）：

```java
int i = this.lightUpdateQueue.size();
int j = i < 1000 ? Math.max(10, i / 10) : i;    // 平时每帧最多 10 个，积压 ≥1000 就全清
```

而它**只被 `LevelRenderer.renderLevel:924` 调用**，即每渲染帧一次。

- `applyLightData`（`:2232-2243`）：逐 section `LevelLightEngine.queueSectionData(layer, SectionPos, DataLayer)`（`readSectionList`，`:2323-2337`），并在 `:2334` `this.level.setSectionDirtyWithNeighbors(...)`；最后 `:2242` `levellightengine.setLightEnabled(chunkpos, true)`。
- `enableChunkLight`（`:765-776`）：对每个 `LevelChunkSection`，`updateSectionStatus(SectionPos.of(chunkpos, y), section.hasOnlyAir())` + `setSectionDirtyWithNeighbors`。
- 之后同帧 `LevelLightEngine.runLightUpdates()`（`LevelLightEngine.java:43`）。

**「什么时候真正可见」**：

- 逻辑可见性 = **下一帧开头那一批 `runAllTasks()`**（同一帧内 tick 就能看到方块）。
- 渲染可见性更晚：需要异步 `SectionRenderDispatcher`（`Util.backgroundExecutor()`，`LevelRenderer.java:713-721`）把 section 网格编出来；`setSectionDirty` 只是打脏标记（`LevelRenderer:2529/2533`）。

### 8.5 `ReceivingLevelScreen` 什么时候关

`ReceivingLevelScreen.java:73-77`：

```java
public void tick() {
    if (this.levelReceived.getAsBoolean() || System.currentTimeMillis() > this.createdAt + 30000L)
        this.onClose();
}
```

`shouldCloseOnEsc()` → false（`:32-34`）；`isPauseScreen()` → false（`:86-88`）；`Reason` 枚举 `NETHER_PORTAL / END_PORTAL / OTHER`（`:91-95`）只影响背景绘制（`:48-61`）。

`levelReceived` = `levelLoadStatusManager::levelReady`。`LevelLoadStatusManager.java`：

```java
:14  private Status status = Status.WAITING_FOR_SERVER;

:22  public void tick() {
:23      switch (this.status) {
:24      case WAITING_FOR_PLAYER_CHUNK:
:25          BlockPos blockpos = this.player.blockPosition();
:26          boolean flag = this.level.isOutsideBuildHeight(blockpos.getY());
:27          if (flag || this.levelRenderer.isSectionCompiled(blockpos) || this.player.isSpectator() || !this.player.isAlive())
:28              this.status = Status.LEVEL_READY;

:35  public boolean levelReady() { return this.status == Status.LEVEL_READY; }

:39  public void loadingPacketsReceived() {
:40      if (this.status == Status.WAITING_FOR_SERVER) this.status = Status.WAITING_FOR_PLAYER_CHUNK;
```

**触发 `loadingPacketsReceived` 的包**（`ClientPacketListener.handleGameEvent`，`:1420-1421`）：

```java
} else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START
           && this.levelLoadStatusManager != null) {
    this.levelLoadStatusManager.loadingPacketsReceived();
```

`LEVEL_CHUNKS_LOAD_START` = type 13（`ClientboundGameEventPacket.java:27`），服务端在 `PlayerList.java:717` 发出。

**真正判定「地形到了」的是 `LevelRenderer.isSectionCompiled(player.blockPosition())`**（`LevelRenderer.java:3578-3582`），另有三个兜底：玩家在建筑高度外 / 旁观者 / 已死亡。**不是**「区块包到达」，也**不是**「玩家位置包到达」。

tick 它的两处（同一 tick 内 `gameMode.tick()` 在前、`screen.tick()` 在后）：

- `LevelLoadStatusManager.tick()` ← `ClientPacketListener.tick()`（`:2481-2483`）← `Connection.tick()`（`:411-413`）← `MultiPlayerGameMode.tick()`（`:269-276`）← `Minecraft.tick():1805`
- `ReceivingLevelScreen.tick()` ← `Screen.tick` ← `Minecraft.tick():1828`

> **`isLevelChunkLoaded` 和 `hasReceivedServerData` 在 1.21.1 不存在**（全树 grep 零命中）。`isLevelChunkLoaded` 是 1.20.x 的名字，1.21+ 被 `LevelLoadStatusManager` 取代。`ClientChunkCache` 里也没有这两个方法。

---

## 9. 阶段八：「完全加载」在代码上到底是什么

这是整件事里最容易被误解的地方。

### 9.1 命名对照：很多旧名字在 1.21.1 已不存在

| 旧写法 | 1.21.1 实际名字 | 位置 |
|---|---|---|
| `ChunkRenderDispatcher` | **`SectionRenderDispatcher`** | `chunk/SectionRenderDispatcher.java:54` |
| `CompiledChunk` | **`SectionRenderDispatcher.CompiledSection`** | 同文件 `:256` |
| `RenderChunk`（per-section 可重编译对象） | **`SectionRenderDispatcher.RenderSection`**（内部类） | 同文件 `:293` |
| `RenderSectionRegion` | **`RenderChunkRegion`** | `chunk/RenderChunkRegion.java:18` |
| `ViewArea.sectionsInView` | `ViewArea.sections`（public 数组） | `ViewArea.java:21` |
| `ViewArea.getRenderChunkAt` | `ViewArea.getRenderSectionAt` | `ViewArea.java:108` |
| `SectionOcclusionGraph.State` | `GraphState` | `SectionOcclusionGraph.java:368` |
| `addInitialSections` | `initializeQueueForFullUpdate` | `SectionOcclusionGraph.java:189` |
| `schedulePropagationFrom` | `runPartialUpdate` + `events.sectionsToPropagateFrom` | `:139` / `:361` |

全树 grep 零命中：`ChunkRenderDispatcher`、`CompiledChunk`、`RenderSectionRegion`、`uploadNextChunkBuffers`、`lockCompileTask`、`createRenderChunk`、`rebuildSection(`、`sectionsInView`、`addInitialSections`、`schedulePropagationFrom`、`scheduleTasks`、`COMPILE_TASKS`、`UPLOAD_TASKS`。

> 注意：1.21.1 里**确实存在**一个叫 `RenderChunk` 的独立类（`chunk/RenderChunk.java:24-45`），但它**与区块编译无关** —— 它只是 `LevelChunk` 的线程安全快照（`PalettedContainer` 拷贝 + blockEntities 拷贝）。

编译任务的线程池也不是专用的：`LevelRenderer.allChanged` 直接把 `Util.backgroundExecutor()` 交给 dispatcher（`LevelRenderer.java:714-721`），即 `ForkJoinPool`，线程数 `clamp(availableProcessors()-1, 1, getMaxThreads())`（`Util.java:149-176`）。

### 9.2 `LevelRenderer.setLevel` / `allChanged`

`LevelRenderer.setLevel(ClientLevel)`（`:676-699`）：

```java
676  public void setLevel(@Nullable ClientLevel p_109702_) {
677      this.lastCameraSectionX = Integer.MIN_VALUE;   // 让下一帧 setupRender 必然 repositionCamera
678/679  ... Y / ... Z = Integer.MIN_VALUE;
680      this.entityRenderDispatcher.setLevel(p_109702_);
681      this.level = p_109702_;
682      if (p_109702_ != null) {
683          this.allChanged();                          // ★ 唯一的"全量重建"入口
684      } else {
685          if (this.viewArea != null) { this.viewArea.releaseAllBuffers(); this.viewArea = null; }   // 686-687
690          if (this.sectionRenderDispatcher != null) { this.sectionRenderDispatcher.dispose(); }     // 691
694          this.sectionRenderDispatcher = null;
695          this.globalBlockEntities.clear();
696          this.sectionOcclusionGraph.waitAndReset(null);
697          this.visibleSections.clear();
698      }
699  }
```

`LevelRenderer.allChanged()`（`:709-746`）逐条：

1. `graphicsChanged()`（`:711`）—— fancy/fabulous 变化时重建 transparency chain
2. `level.clearTintCaches()`（`:712`）
3. dispatcher 不存在就 new（`:713-721`，executor = `Util.backgroundExecutor()`），存在则只 `setLevel`（`:723` → `SectionRenderDispatcher.setLevel` `:89-91`）
4. `generateClouds = true`、`ItemBlockRenderTypes.setFancy(...)`、`lastViewDistance = 当前渲染距离`（`:726-728`）
5. `viewArea.releaseAllBuffers()`（`:729-731`）→ `ViewArea.java:48-52` → 每个 `RenderSection.releaseBuffers()`（`:377-380`）→ `reset()`（`:371-375`）：

   ```java
   private void reset() {
       this.cancelTasks();
       this.compiled.set(CompiledSection.UNCOMPILED);
       this.dirty = true;
   }
   ```

   并且 `VertexBuffer::close` 释放旧 GPU buffer（`:379`）
6. `sectionRenderDispatcher.blockUntilClear()`（`:733`）→ `clearBatchQueue()`（`:227-243`），取消所有排队中的 `CompileTask`
7. `globalBlockEntities.clear()`（`:734-736`）
8. **`this.viewArea = new ViewArea(...)`（`:738`）** —— 整个 ViewArea 被丢弃重建。新建的每个 `RenderSection` 字段初值就是：

   ```java
   296  public final AtomicReference<CompiledSection> compiled = new AtomicReference<>(CompiledSection.UNCOMPILED);
   309  private boolean dirty = true;
   ```
9. `sectionOcclusionGraph.waitAndReset(viewArea)`（`:739`）→ `SectionOcclusionGraph.java:55-72`：等待未完成的 full update 线程结束，`currentGraph.set(new GraphState(sections.length))`，`invalidate()`（`:74-76`）→ `needsFullUpdate = true`
10. `visibleSections.clear()`（`:740`）
11. `viewArea.repositionCamera(entity.getX(), entity.getZ())`（`:741-744`）

**清空**：`globalBlockEntities`、`visibleSections`、整个旧 `ViewArea`（含全部 `VertexBuffer`）、batch 队列、occlusion graph。
**标脏**：**没有逐个 `setDirty`**，而是靠「新建整个 ViewArea ⇒ 所有 `RenderSection` 的 `dirty` 初值 `true`、`compiled` 初值 `UNCOMPILED`」。语义上等于「全部 section 都脏且未编译」。

`allChanged` 的其他调用点：`setupRender` 里渲染距离变化（`:810-812`）、资源包重载成功（`Minecraft.java:928`）。

### 9.3 从「被标脏」到「GPU 上传完成」

#### 阶段 A —— 标脏（Render thread）

- `ClientPacketListener.enableChunkLight` 里 `this.level.setSectionDirtyWithNeighbors(x, sectionY, z)`（`:770-776`）
- 生物群系包直接调 `levelRenderer.setSectionDirty(...)`（`:744-755`）
- `ClientChunkCache.onLightUpdate`（`:177-180`）→ `levelRenderer.setSectionDirty(...)`
- 方块变化：`ClientLevel.setBlocksDirty`（`:662-664`）→ `LevelRenderer.setBlockDirty`（`:2513-2517`）→ `setBlocksDirty`（`:2503-2511`）→ `setSectionDirty`（`:2533-2535`）→ `ViewArea.setDirty`（`:100-106`）→ `RenderSection.setDirty`（`:386-390`）

`ViewArea.setDirty` 用 `floorMod` 把世界 section 坐标映射到环形数组下标：

```java
100  public void setDirty(int x, int y, int z, boolean flag) {
101      int i = Math.floorMod(x, this.sectionGridSizeX);
102      int j = Math.floorMod(y - this.level.getMinSection(), this.sectionGridSizeY);
103      int k = Math.floorMod(z, this.sectionGridSizeZ);
104      this.sections[this.getSectionIndex(i, j, k)].setDirty(flag);
```

#### 阶段 B —— 遍历可见 section 并决定同步/异步（Render thread）

`LevelRenderer.renderLevel`（`:914`）→ `setupRender`（`:808-865`）→ `compileSections`（`:1961-2000`），后者是调度中枢：

```java
1968: for (RenderSection s : this.visibleSections) {                       // ★ 只处理可见集
1970:     if (s.isDirty() && levellightengine.lightOnInSection(sectionpos)) {   // ★ 光照未就绪不编译
1972:         if (options.prioritizeChunkUpdates() == NEARBY) {
1974:             flag = distSqr < 768.0 || s.isDirtyFromPlayer();
1975:         } else if (... == PLAYER_AFFECTED) {
1976:             flag = s.isDirtyFromPlayer();
1979:         if (flag) {
1981:             this.sectionRenderDispatcher.rebuildSectionSync(s, renderregioncache);   // 同步！
1982:             s.setNotDirty();
1984:         } else {
1985:             list.add(s);
1990: profiler.popPush("upload");
1991: this.sectionRenderDispatcher.uploadAllPendingUploads();     // ★ 上传在这里被真正执行（Render thread）
1992: profiler.popPush("schedule_async_compile");
1994: for (RenderSection s : list) {
1995:     s.rebuildSectionAsync(this.sectionRenderDispatcher, renderregioncache);
1996:     s.setNotDirty();
```

三点很重要：

- 只处理 `visibleSections` —— 由**视锥**决定（§9.5）。
- 光照未就绪（`lightOnInSection`）就不编译，所以「世界加载完」还依赖光照引擎。
- `uploadAllPendingUploads()` 在**同步重建之后、异步调度之前**（`:1991`），即同步路径的上传同帧完成，异步路径的上传下一帧起被 drain。

#### 阶段 C —— `createCompileTask`：生成区块数据快照（Render thread）

`SectionRenderDispatcher.java:442-455`：

```java
442  public CompileTask createCompileTask(RenderRegionCache cache) {
443      boolean flag = this.cancelTasks();                       // 取消上一个未完成的同类任务
444      var additionalRenderers = ClientHooks.gatherAdditionalRenderers(this.origin, SectionRenderDispatcher.this.level);  // ★ Neo 钩子，主线程
445      RenderChunkRegion region = cache.createRegion(level, SectionPos.of(this.origin), additionalRenderers.isEmpty());
446      boolean flag1 = this.compiled.get() == CompiledSection.UNCOMPILED;
447      if (flag1 && flag) this.initialCompilationCancelCount.incrementAndGet();
451      this.lastRebuildTask = new RebuildTask(this.getDistToPlayerSqr(), region, !flag1 || count > 2, additionalRenderers);
```

`RenderRegionCache.createRegion`（`RenderRegionCache.java:23-49`）在这里把 3×3 个 `RenderChunk`（`LevelChunk` 的 paletted data 拷贝）冻结成快照 —— **这一步在主线程**，所以后续 worker 线程读的是不可变拷贝。若整个 section 为空则返回 `null`（`:25-26`）。

#### 阶段 D —— 几何编译（`Util.backgroundExecutor()` worker 线程）

`SectionRenderDispatcher.schedule`（`:186-201`）只把任务投进 mailbox：

```java
186  public void schedule(CompileTask task) {
187      if (!this.closed) {
188          this.mailbox.tell(() -> {                       // mailbox 跑在 backgroundExecutor 上
190              if (task.isHighPriority) this.toBatchHighPriority.offer(task);
193              else                     this.toBatchLowPriority.offer(task);
196              this.toBatchCount = high.size() + low.size();
197              this.runTask();
```

`runTask`（`:93-125`）：

```java
 94  if (!this.closed && !this.bufferPool.isEmpty()) {
 95      CompileTask task = this.pollTask();               // 高优先级配额 2（:55、:127-145）
 97      SectionBufferBuilderPack pack = requireNonNull(this.bufferPool.acquire());
 98      this.toBatchCount = high.size() + low.size();     // ← 注意：不含正在执行的任务
 99      CompletableFuture.supplyAsync(wrapThreadWithTaskName(task.name(), () -> task.doTask(pack)), this.executor)
107          .whenComplete((result, err) -> { ... this.mailbox.tell(() -> { ... pack.clearAll(); this.bufferPool.release(pack); this.runTask(); }); });
```

任务名 `"rend_chk_rebuild"`（`:542`）。`RebuildTask.doTask`（`:546-595`）在 worker 线程：

- `:549` `if (!RenderSection.this.hasAllNeighbors())` → 取消（`hasAllNeighbors` 见 `:329-337`，玩家附近 24 格内不要求）
- `:557-561` region 为 null → `updateGlobalBlockEntities(Set.of())` + `setCompiled(CompiledSection.EMPTY)`，成功返回
- `:564-565` `SectionCompiler.compile(sectionPos, region, vertexSorting, pack, additionalRenderers)` —— 真正生成顶点数据
- `:571-579` 组装 `CompiledSection`，对每个渲染层调 `uploadSectionLayer(meshData, buffer)`
- `:580-590` `Util.sequenceFailFast(uploadFutures).handle(...)` → **上传全部完成后**才 `setCompiled(compiledSection)`

`SectionCompiler.compile`（`SectionCompiler.java:47-116`）：逐块 `renderBatched`/`renderLiquid`（`:74` / `:90`）、Neo 附加几何（`:95-100`）、`meshdata.sortQuads(...)`（`:106`）、`visgraph.resolve()`（`:114`）。

#### 阶段 E —— 上传 GPU（**Render thread**，不是 worker）

这是最容易看错的一处：

```java
203  public CompletableFuture<Void> uploadSectionLayer(MeshData data, VertexBuffer buffer) {
204      return this.closed ? completedFuture(null) : CompletableFuture.runAsync(() -> {
208          buffer.bind();
209          buffer.upload(data);
210          VertexBuffer.unbind();
212      }, this.toUpload::add);          // ← executor 就是队列的 add()！
213  }
```

`CompletableFuture.runAsync(r, exec)` 这里的 `exec` 是 `Queue::add`，所以 runnable **只是被塞进 `toUpload` 队列，并不在 worker 上执行**；返回的 future 保持未完成，直到：

```java
171  public void uploadAllPendingUploads() {
172      Runnable r;
173      while ((r = this.toUpload.poll()) != null) r.run();      // ← 在调用者线程 = Render thread
174  }
```

唯一常规调用点是 `LevelRenderer.compileSections:1991`（Render thread）。所以：

- 顶点构建（CPU 侧 `MeshData`）：worker 线程
- `VertexBuffer.upload(...)`：**Render thread**（通过 drain 队列）
- `CompiledSection` 被写入 `RenderSection.compiled`（`:488-492` 的 `setCompiled`）发生在上传 future 全部完成之后（`:580-590`），因此正常情况下也在 Render thread 上执行，并顺带 `renderer.addRecentlyCompiledSection`（`LevelRenderer.java:882-884`）→ `SectionOcclusionGraph.onSectionCompiled`（`:102-112`）

两个诚实的边界情况：

- `:560` 的空 section 分支在 **worker 线程**直接 `setCompiled(EMPTY)`（没有上传）。
- 若 region 非空但 `renderedLayers` 为空，`list` 为空 → `sequenceFailFast` 立即完成 → `handle` 在 **worker 线程**执行 `setCompiled`（`:588`）。`onSectionCompiled` 里用的是 `AtomicReference` + `LinkedBlockingQueue`（`:361-365`），所以不会炸，但确实不保证在 Render thread。

#### 阶段 F —— 透明层重排序（独立路径）

`LevelRenderer.renderSectionLayer`（`:1269-1334`）在渲染半透明层时，对最多 15 个 section 调 `resortTransparency`（`:1292`）→ `SectionRenderDispatcher.java:409-424` → `ResortTransparencyTask`（`:607-668`，任务名 `"rend_chk_sort"`，`isHighPriority = true`）→ `uploadSectionIndexBuffer`（`:215-225`，同样 drain 到 `toUpload`）。

#### 并发上限

- 编译并发 = `SectionBufferBuilderPool` 的空闲 pack 数：`RenderBuffers` 构造（`RenderBuffers.java:19-20`）→ `SectionBufferBuilderPool.allocate(Runtime.availableProcessors())`（`Minecraft.java:534`）→ `SectionBufferBuilderPool.java:24-26`：`max(1, 0.3*maxMemory/TOTAL_BUFFERS_SIZE)` 再与 CPU 核数取 min。`runTask:94` 只在池非空时启动新任务。
- 每帧同步编译（`rebuildSectionSync` → `compileSync`，`:178-180` / `:476-479`）用的是 `RenderBuffers.fixedBufferPack()`（**单份**，`RenderBuffers.java:13`），并且直接在 Render thread 上 `doTask` —— 这就是「优先区块更新」会掉帧的原因。

### 9.4 `isSectionCompiled` 查的是什么

```java
3578  public boolean isSectionCompiled(BlockPos pos) {
3579      RenderSection s = this.viewArea.getRenderSectionAt(pos);
3580      return s != null && s.compiled.get() != CompiledSection.UNCOMPILED;
3581  }
```

`ViewArea.getRenderSectionAt`（`:108-118`）用 `floorMod` 定位环形槽位；`CompiledSection.UNCOMPILED` 定义在 `SectionRenderDispatcher.java:257-262`。

由于 `compiled` 只在 `RebuildTask.doTask` 的上传 future 全部完成后才被写（`:580-590` → `:488-492`），所以 `isSectionCompiled() == true` 意味着：**该 section 的 `renderableLayers` 已 `VertexBuffer.upload` 完毕**（或它是空 section / region == null 的快速路径）。

**不是**「排进队列了」，也**不是**「顶点构建完了」。但它也**不**代表这个 section 会在画面上被绘制——还要 `visibleSections` 里有它。

### 9.5 视锥、`ViewArea` 与 `SectionOcclusionGraph`

`ViewArea.repositionCamera`（`:74-98`）：相机跨 section 时（`setupRender:821-826` 比较 `lastCameraSectionX/Y/Z`），把整个 section 网格**平移滑动**到以相机为中心：

```java
 74  public void repositionCamera(double cx, double cz) {
 78    for (k...) for (k1...) for (k2...) {
 91        BlockPos origin = section.getOrigin();
 92        if (j1 != origin.getX() || l2 != origin.getY() || j2 != origin.getZ())
 93            section.setOrigin(j1, l2, j2);     // → reset()：cancel + compiled=UNCOMPILED + dirty=true
```

`setOrigin`（`SectionRenderDispatcher.java:347-357`）开头就 `reset()`。**所以滑动本身就会把被移动过的 section 重新标脏。**

`SectionOcclusionGraph` 的两条更新路径：

- `LevelRenderer.setupRender:836` 相机跨过 8 格栅格 → `invalidate()`（`SectionOcclusionGraph.java:74-76`，`needsFullUpdate = true`）
- `update(boolean smartCull, Camera, Frustum, visibleSections)`（`:114-121`）在 Render thread 被调用（`LevelRenderer.java:853`）：
  - `needsFullUpdate && (fullUpdateTask == null || done)` → `scheduleFullUpdate`（`:123-137`）：**把整张图的 BFS 丢到 `Util.backgroundExecutor()`**（`:125`），从相机所在 section（或相机在建筑高度外时整条水平环）出发（`initializeQueueForFullUpdate:189-235`），`runUpdates`（`:237-334`）里按 `facesCanSeeEachother`（`:265-278`，读 `CompiledSection.visibilitySet`）做可见性传播，完成后 `currentGraph.set(...)`、`needsFrustumUpdate = true`
  - `runPartialUpdate`（`:139-165`）：只从「新编译完成 / 邻居到齐」的 section 出发（`queueSectionsWithNewNeighbors:167-180`，`events.sectionsToPropagateFrom`）。**这是主线程的增量传播**，代价小
- 之后 `LevelRenderer.setupRender:857` 若 `consumeFrustumUpdate()` 或相机 yaw/pitch 变化 → `applyFrustum(offsetFrustum(frustum))`（`:871-880`）→ `visibleSections.clear()` + `addSectionsInFrustum`（`SectionOcclusionGraph.java:78-84`，遍历 `currentGraph.storage().renderSections` 做 `frustum.isVisible(AABB)`）

**结论：`visibleSections` = 「遮挡图认为可达」∩「视锥内」，是 `compileSections` 的唯一输入。**

### 9.6 为什么 `levelReady` ≠ 全世界加载完

1. 判据只有一个 `BlockPos` → **一个 section**。`allChanged()` 把所有 `RenderSection.compiled` 重置为 `UNCOMPILED`，而 `isSectionCompiled(player.blockPosition())` 只等**玩家所在那一个**变回已编译。
2. 而且玩家脚下那个 section 通常是**最先**被编译的：`compileSections` 只处理 `visibleSections`，而 occlusion graph 的 BFS 从相机所在 section 出发（`:189-235`），任务按 `getDistToPlayerSqr()` 排序（`CompileTask.compareTo` `SectionRenderDispatcher.java:518-520`）。
3. 其余 section 还受限于：遮挡图是否已传播到它（`facesCanSeeEachother`，`:265-278`）、它是否在**当前视锥**里（`addSectionsInFrustum`）、`hasAllNeighbors()`（`:329-337`）、`lightOnInSection`（`LevelRenderer.java:1970`）、以及 buffer pool 的空闲量（`runTask:94`）。
4. 另外还有 `isOutsideBuildHeight || isSpectator || !isAlive` 三个短路（`:26-27`）—— 旁观者/死亡/超界时**立即** LEVEL_READY，跟渲染完全无关。

所以 `levelReady()` 的真正语义是「**玩家自己脚下的地形已经可以看了**」，它是「最小可玩」判据，不是「完全加载」判据。

### 9.7 帧循环里「还有多少 section 待编译」

`SectionRenderDispatcher` 提供的公开状态：

| 方法 | 行 | 语义 |
|---|---|---|
| `getStats()` | `:147-149` | `"pC: %03d, pU: %02d, aB: %02d"` = toBatchCount / toUpload.size / 空闲 buffer 数 |
| `getToBatchCount()` | `:151-153` | 待批处理（待编译）任务数（`volatile int toBatchCount`，`:62`） |
| `getToUpload()` | `:155-157` | 待上传 runnable 数 |
| `getFreeBufferCount()` | `:159-161` | 空闲 `SectionBufferBuilderPack` 数 |
| `isQueueEmpty()` | `:245-247` | `toBatchCount == 0 && toUpload.isEmpty()` |

`LevelRenderer` 侧的聚合：

- `hasRenderedAllSections()`（`:3539-3541`）= `sectionRenderDispatcher.isQueueEmpty()`
- `getSectionStatistics()`（`:759-771`）→ `"C: %d/%d %sD: %d, %s"`，第一个数是 `countRenderedSections()`（`:785-795`：遍历 `visibleSections`，`!getCompiled().hasNoRenderableLayers()` 才计数），第二个是 `viewArea.sections.length`
- `getTotalSections()`（`:777-779`）、`getLastViewDistance()`（`:781-783`）

消费者（全树 grep，只有这些）：

- `GameRenderer.java:1171`：`takeAutoScreenshot` 里 `countRenderedSections() > 10 && hasRenderedAllSections()` → 自动截取世界缩略图
- `DebugScreenOverlay.java:278` 和 `:315`：F3 的 `getSectionStatistics()`
- `ClientMetricsSamplersProvider.java:34-49`：`totalChunks` / `lastViewDistance` / `toUpload` / `freeBufferCount` / `toBatchCount` 指标采样

**谁用它来决定不关 `ReceivingLevelScreen`？—— 谁都没有。** 关屏的唯一判据是 `LevelLoadStatusManager::levelReady`。

> **`isQueueEmpty()` 的一个重要坑**：`runTask` 在 `pollTask()` **之后**才重算 `toBatchCount`（`:95` 先 poll，`:98` 再赋 `size()` 之和），而且 `:98` 只统计队列里剩下的。也就是说：
>
> - **正在 worker 线程上跑的那个任务不计入 `toBatchCount`**
> - 它产生的上传要等它 `doTask` 返回后才进 `toUpload`
>
> 因此 `isQueueEmpty()` 可能在「仍有一个 section 正在编译、上传尚未入队」的瞬间返回 `true`。**`hasRenderedAllSections()` 是瞬时快照，会假阳性** —— 这也是自动截图偶尔拍到半加载画面的代码层原因。

### 9.8 「画面完全加载」的可操作判据

先明确一个事实：**引擎里没有任何一处定义了「完全加载」**。现有判据只有三个层级：单 section（`isSectionCompiled`）、队列（`isQueueEmpty`）、计数（`countRenderedSections`）。所以下面是可操作的**组合**判据，每条都给出依据。

| # | 判据 | 依据 |
|---|---|---|
| 1 | 该 section 编译 + 上传完成：`s.compiled.get() != CompiledSection.UNCOMPILED` | `LevelRenderer.java:3578-3582`；写入点 `SectionRenderDispatcher.java:588` → `:488-492` |
| 2 | 该 section 不再脏：`s.isDirty() == false` | `RenderSection.isDirty()` `:397-399`；同步路径在 `LevelRenderer.java:1982` 清、异步路径在 `:1996` 清。**清脏 ≠ 编译完**，两者必须同时成立 |
| 3 | 邻居到齐：`s.hasAllNeighbors()` | `SectionRenderDispatcher.java:329-337`（24 格内不要求；否则要求四个水平邻居以 `ChunkStatus.FULL` 存在）。`RebuildTask.doTask:549-551` 会因邻居缺失直接取消 |
| 4 | 光照就绪：`levelLightEngine.lightOnInSection(...)` | `LevelRenderer.java:1970` |
| 5 | 已进入可见集：`s ∈ levelRenderer.visibleSections` | `:1968`；上游是遮挡图 BFS 可达集合 + `applyFrustum`（`:871-880`） |
| 6 | 待编译/待上传队列清空：`srd.isQueueEmpty()` | `SectionRenderDispatcher.java:245-247`；注意 §9.7 的假阳性，建议**连续若干帧**成立 |
| 7 | 要遍历 `viewArea.sections`，而不是只看 `visibleSections` | `viewArea` 与 `visibleSections` 都是 private 且**没有 getter**（`LevelRenderer.java:174` / `:171`） |

**判据 5 必须诚实说明**：由于视锥的存在，「全世界所有 section 都被编译」在代码上**根本不会自然发生** —— 你必须在所有方向上都停留过一段时间才能让每个 section 进过 `visibleSections`。所以现实中的「完全加载」只能是「**当前视野内完全加载**」。

外部可用的 API 只有：`getSectionRenderDispatcher()`（`:773-775`）、`getTotalSections()`、`countRenderedSections()`、`isSectionCompiled(BlockPos)`。`ViewArea.sections` 本身是 public 字段（`ViewArea.java:21`）、`RenderSection.getCompiled()`/`isDirty()`/`hasAllNeighbors()` 也都是 public，但**从 `LevelRenderer` 拿不到 `ViewArea`**。

实践上最可用的组合（只用公开 API）：

1. 从 `ClientChunkCache` 确认区块数据到齐 —— `getLoadedChunksCount()`（`:170-172`）应达到 ≈ `(2*实际视距+1)²`，且 `getViewDistance()` 不再变化（`updateViewRadius` 换 storage 会丢/搬 chunk，`:134-154`）
2. 等 `ReceivingLevelScreen` 关闭（只保证玩家脚下）
3. 让相机环绕 360°（或渲染距离内各方向停留数帧），使 `applyFrustum` 遍历过所有方向；每帧检查 `hasRenderedAllSections()` 与 `getToUpload() == 0 && getToBatchCount() == 0`
4. 判据 3、4 由引擎自己把关，不需要外部检查 —— 只要第 3 步在**连续 ≥60 帧**都成立，就可以认为「当前视野内完全加载」
5. 若需要「全世界」：只能逐点轮询 `isSectionCompiled(pos)`，遍历以玩家为中心、半径 = `options.getEffectiveRenderDistance()` 的所有 section 坐标（Y 取 `level.getMinSection()..getMaxSection()`），且必须让相机在所有 4 个水平方向、上下都扫过。**这是从代码行为推出的必然结论，不是引擎提供的功能。**

两个会让上述判据「看起来成立但其实没加载完」的漏洞：

- `SectionOcclusionGraph.onSectionCompiled` / `queueSectionsWithNewNeighbors`（`:102-112`、`:167-180`）是异步/排队传播的：一个 section 编译完成后，它**邻居**的可见性要等下一帧 `runPartialUpdate` 才补上，逐帧计数会有 1~2 帧滞后。
- `LevelRenderer.renderSectionLayer:1289-1295` 每帧只对**最多 15 个** section 做半透明重排序（常量 `TRANSPARENT_SORT_COUNT = 15`，`:155`），所以透明层正确排序收敛比「编译完成」晚得多。

### 9.9 NeoForge 渲染侧钩子

**`AddSectionGeometryEvent`**

- 定义：`net/neoforged/neoforge/client/event/AddSectionGeometryEvent.java:57-165`。1.21.1 形态是「事件里收集 `AdditionalSectionRenderer` 回调，回调在**编译线程**上执行」：`addRenderer(:72)` / `getAdditionalRenderers(:79)` / `getSectionOrigin(:87)` / `getLevel(:94)`。
- 类注释 `:27-33` 明确写了：「事件本身在主客户端线程 fire；注册进来的 renderer 在编译线程执行，通常不是主线程」。注意 `:95` 有 `Preconditions.checkState(Minecraft.getInstance().isSameThread())` —— **只有在事件处理器里才能调 `getLevel()`，在 renderer 回调里调会抛异常**。
- **fire 点**：`ClientHooks.java:990-995`：

  ```java
  990  public static List<AdditionalSectionRenderer> gatherAdditionalRenderers(BlockPos sectionOrigin, Level level) {
  992      final var event = new AddSectionGeometryEvent(sectionOrigin, level);
  993      NeoForge.EVENT_BUS.post(event);
  ```

  唯一调用者：`SectionRenderDispatcher.java:444`（在 `createCompileTask` 里）⇒ **Render thread，且每次调度重编译都 fire 一次**。
- renderer 回调执行点：`ClientHooks.addAdditionalGeometry`（`:997-1009`）← `SectionCompiler.java:95-100` ⇒ **worker 线程**。
- 对编译路径的实质影响：`createRegion` 的 `nullForEmpty` 参数被改成 `additionalRenderers.isEmpty()`（`SectionRenderDispatcher.java:445`）—— **一旦有 mod 注册了 renderer，空的 section 也强制生成 region 并编译**，编译量显著上升。

**`RenderLevelStageEvent`**

- fire 实现：`ClientHooks.dispatchRenderStage(Stage, ...)`（`:288-294`）与 `dispatchRenderStage(RenderType, ...)`（`:296-300`）。
- Stage 枚举：`RenderLevelStageEvent.java:158-207`。自定义 stage 注册事件 `RegisterStageEvent` 在 `Minecraft.java:569` post。
- fire 点（全在 `LevelRenderer.renderLevel` / `renderSectionLayer`）：

  | Stage | 位置 |
  |---|---|
  | `AFTER_SKY` | `LevelRenderer.java:957` |
  | `AFTER_SOLID_BLOCKS` / `AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS` / `AFTER_CUTOUT_BLOCKS` / `AFTER_TRANSLUCENT_BLOCKS` / `AFTER_TRIPWIRE_BLOCKS` | 由 `renderSectionLayer` 的 `dispatchRenderStage(RenderType, ...)`（`:1337`）按层触发 → `:965` / `:967` / `:969` / `:1177` 或 `:1196` / `:1179` 或 `:1200` |
  | `AFTER_ENTITIES` | `:1045` |
  | `AFTER_BLOCK_ENTITIES` | `:1117` |
  | `AFTER_PARTICLES` | `:1185`（fabulous 路径）/ `:1203`（普通路径） |
  | `AFTER_WEATHER` | `:1219` / `:1228` |
  | `AFTER_LEVEL` | `GameRenderer.java:1273` |

- 对区块编译路径：**无直接干预**，只包在渲染阶段外层。

**`TextureAtlasStitchedEvent`**：`ClientHooks.java:310-312` ← `TextureAtlas.java:91`。间接影响——资源重载会让 `Minecraft.java:928` 调 `levelRenderer.allChanged()`，触发全量重建。

**`RegisterClientReloadListenersEvent`**：`ClientHooks.initClientHooks`（`:1026`），在 `Minecraft` 构造期一次性触发（`initializedClientHooks` 保证只跑一次，`:1021-1023`）。同批还有 `RegisterRenderBuffersEvent`（`RenderBuffers.java:40`）。

**`ScreenshotEvent`**：`ClientHooks.java:567-570` ← `Screenshot.java:57`。与「完全加载」直接相关的是 `GameRenderer.takeAutoScreenshot`（`:1169-1174`）。

**`ClientTickEvent`**：`ClientHooks.fireClientTickPre()`（`:1067-1071`）+ `fireClientTickPost()`（`:1073-1078`），调用点 `Minecraft.java:1786-1787` / `:1915`。帧循环定位：`Minecraft.runTick(boolean)`（`:1135`）→ tick（`:1158-1166`，最多 10 次）→ Render（`:1177` 起）→ `fireRenderFramePre`（`:1193`）→ `gameRenderer.render`（`:1195`）→ `fireRenderFramePost`（`:1197`）。

`LevelRenderer.tick()` 在 `Minecraft.java:1851` 被调（只做 `ticks++` 和破坏进度过期，`LevelRenderer.java:1516-1533`，**与编译无关**）。渲染距离改变时 `Minecraft.java:1934` 调 `levelRenderer.needsUpdate()`（`:3547-3550`，只 `sectionOcclusionGraph.invalidate()` + `generateClouds = true`）。

---

## 10. NeoForge 注入点总清单（按时间顺序）

### 10.1 进程启动期（早于点击存档）

| 顺序 | 位置 | 事件 / 扩展点 |
|---|---|---|
| A1 | `CommonModLoader.java:52` → `RegistryManager.java:82-105` | `NewRegistryEvent` → `DataPackRegistryEvent.NewRegistry` → `ModifyRegistriesEvent` |
| A2 | `RegistrationEvents.java:21` → `RegistryManager.java:110-115` | `RegisterDataMapTypesEvent` |
| A3 | `RegistryDataLoader.java:103-115` | 静态初始化时调 `DataPackRegistriesHooks.grabNetworkableRegistries(...)` |
| A4 | `CommonModLoader.java:83` | `NetworkRegistry.setup()` 锁定 payload 注册 |
| A5 | `GameData.java:59` / `:72` | `RegistryManager.takeVanillaSnapshot()` / `takeFrozenSnapshot()` |

### 10.2 主流程

| 阶段 | 事件 / 钩子 | fire 位置 | 可取消 |
|---|---|---|---|
| 读 level.dat | `ModMismatchEvent` | `CommonHooks.java:1226` ← `WorldOpenFlows.java:273` | ⚠ mod bus 事件，靠 `markResolved()` |
| | `CommonHooks.parseLifecycle` | `LevelSettings.java:42` | — |
| | `CommonHooks.readAdditionalLevelSaveData` | `LevelStorageSource.java:473` | — |
| WorldStem | `AddPackFindersEvent` | `ResourcePackLoader.java:75` ← `ServerPacksSource.java:76` | — |
| | `AddReloadListenerEvent` | `EventHooks.java:818` ← `ReloadableServerResources.java:114` | — |
| | **`RegisterCommandsEvent`** | `Commands.java:251` ← `ReloadableServerResources.java:49` | — |
| | **`TagsUpdatedEvent(reg, false, false)`** | `ReloadableServerResources.java:143` | — |
| 启动服务器 | `ServerAboutToStartEvent` | `ServerLifecycleHooks.java:98` ← `IntegratedServer.java:76` | — |
| | `ServerStartingEvent` | `ServerLifecycleHooks.java:109` ← `IntegratedServer.java:81` | — |
| | `ServerStartedEvent` | `ServerLifecycleHooks.java:113` ← `MinecraftServer.java:674` | — |
| 建维度 | `ModifyCustomSpawnersEvent` | `EventHooks.java:1152` ← `ServerLevel.java:304` | — |
| | **`LevelEvent.Load(overworld)`** | `MinecraftServer.java:375` | — |
| | **`LevelEvent.CreateSpawnPosition`** | `EventHooks.java:632` ← `MinecraftServer.java:437` | ✅ **可取消** |
| | `LevelEvent.Load(其它维度)` | `MinecraftServer.java:425` | — |
| 预加载 | `ChunkDataEvent.Load` | `ChunkSerializer.java:248` / `:272` | — |
| | `ChunkTicketLevelUpdated` | `ChunkMap.java:398` | — |
| 配置阶段 | `RegisterConfigurationTasksEvent` | `ServerConfigurationPacketListenerImpl.java:110` | — |
| | `NetworkRegistry` 协商系列 payload | 见 §6.1 | — |
| 进服 | `PlayerEvent.LoadFromFile` | `PlayerList.java:338` / `PlayerDataStorage.java:84` | — |
| | `OnDatapackSyncEvent` | `PlayerList.java:205` | — |
| | **`EntityJoinLevelEvent(ServerPlayer)`** | `ServerLevel.java:918` | ✅ **可取消** |
| | `PlayerEvent.PlayerLoggedInEvent` | `EventHooks.java:893` ← `PlayerList.java:272` | — |
| 客户端建世界 | **`LevelEvent.Load(ClientLevel)`** | `ClientLevel.java:203`（构造器内） | — |
| | **`LevelEvent.Unload(旧 level)`** | `Minecraft.java:2114` | — |
| | `ClientPlayerNetworkEvent.LoggingIn` | `ClientHooks.java:722` ← `ClientPacketListener.java:432` | — |
| | `EntityJoinLevelEvent(LocalPlayer)` | `ClientLevel.java:346` | ✅ **可取消** |
| | `RegisterClientCommandsEvent` | `ClientCommandHandler.java:63`（可能 fire 两次） | — |
| | `RecipesUpdatedEvent` | `ClientHooks.java:662` ← `ClientPacketListener.java:1506` | — |
| | `TagsUpdatedEvent(reg, true, isMemoryConnection)` | `TagCollector.java:47` | — |
| 区块收发 | **`ChunkWatchEvent.Watch`** | `ChunkMap.java:826`（标记待发时） | — |
| | **`ChunkWatchEvent.Sent`** | `PlayerChunkSender.java:82`（真正发包后） | — |
| | `ChunkWatchEvent.UnWatch` | `ChunkMap.java:830` | — |
| | **`ChunkEvent.Load`**（客户端） | `ClientChunkCache.java:127` | — |
| | `ChunkEvent.Unload` | `ClientChunkCache.java:66` / `ChunkMap.java:514` | — |
| | `ChunkDataEvent.Save` | `ChunkMap.java:764` | — |
| 渲染 | `AddSectionGeometryEvent` | `ClientHooks.java:990` ← `SectionRenderDispatcher.java:444` | — |
| | `ClientTickEvent.Pre/Post` | `Minecraft.java:1787` / `:1915` | — |
| | `LevelTickEvent.Pre/Post` | `Minecraft.java:1880` / `:1894`、`MinecraftServer.java:1034` / `:1043` | — |
| | `RenderFrameEvent.Pre/Post` | `Minecraft.java:1193` / `:1197` | — |
| | `ServerTickEvent.Pre/Post` | `MinecraftServer.java:915` / `:943` | — |
| | `PlayerTickEvent.Pre/Post` | `Player.java:254` / `:332` | — |
| | `EntityTickEvent.Pre/Post` | `ServerLevel.java:773` / `:775`、`ClientLevel.java:295` / `:297`、`Entity.java:1959` / `:1961` | `Pre` 可取消 |
| | `RenderLevelStageEvent` | 见 §9.9 | — |

### 10.3 整条流程里的可取消事件（只有 3 个）

| 事件 | 类声明 | fire 点 | 取消后行为 |
|---|---|---|---|
| **`LevelEvent.CreateSpawnPosition`** | `event/level/LevelEvent.java:99`（`extends LevelEvent implements ICancellableEvent`） | `MinecraftServer.java:437` → `EventHooks.java:632` | `setInitialSpawn` 直接 `return`，不做任何 vanilla 出生点搜索；spawn 完全由 mod 通过 `event.getSettings().setSpawn(pos, angle)` 设定。**只对首次生成的维度（`!serverleveldata.isInitialized()`）触发** |
| **`EntityJoinLevelEvent`** | `event/entity/EntityJoinLevelEvent.java:30` | 服务端 `ServerLevel.java:918`（`addPlayer`）、`PersistentEntitySectionManager.java:79`（磁盘加载实体，带 `loadedFromDisk`）；客户端 `ClientLevel.java:346` | 取消 ⇒ 实体**不被加入世界** |
| **`EntityTravelToDimensionEvent`** | `event/entity/EntityTravelToDimensionEvent.java:26` | `CommonHooks.java:777-781` ← `ServerPlayer.java:867` / `Entity.java:2576` | 取消 ⇒ `changeDimension` 立即 `return null`，传送不发生 |

树内共 55 个顶层 + 66 个嵌套 `ICancellableEvent` 实现。**这条进世界流程里只有上面 3 个。**

「看起来能取消但实际不行」的（树内实测）：`LevelEvent.Load/Unload/Save`、`ChunkEvent.Load/Unload`、`ChunkWatchEvent.*`、`ChunkDataEvent.*`、`TagsUpdatedEvent`、`OnDatapackSyncEvent`、`RegisterCommandsEvent`、`PlayerEvent.{Clone, LoadFromFile, SaveToFile, PlayerLoggedInEvent, PlayerLoggedOutEvent, PlayerRespawnEvent, PlayerChangedDimensionEvent}`、`PlayerRespawnPositionEvent`、`ClientPlayerNetworkEvent.*`、`ClientTickEvent`、`ServerTickEvent`、`LevelTickEvent`、`EntityLeaveLevelEvent`、`AddSectionGeometryEvent`、`RecipesUpdatedEvent`、`RegisterClientCommandsEvent`、`ModMismatchEvent`、`RenderLevelStageEvent`。

（`PlayerEvent.PlayerChangeGameModeEvent`（`PlayerEvent.java:475`）和 `PlayerEvent.BreakSpeed` 是本类里仅有的可取消子类。）

### 10.4 NeoForge 对 level.dat 的读写扩展

**根节点 `fml`（mod 列表 / 版本冲突检测）**

- **写**：`CommonHooks.writeAdditionalLevelSaveData`（`CommonHooks.java:1160-1180`）—— 把 `ModList.get().getMods()` 压成 `ListTag`，每项 `{ModId, ModVersion}`，放进 `levelTag.put("fml", {LoadingModList: [...]})`。调用点 `LevelStorageSource.java:508-513`，在 `compoundtag1.put("Data", compoundtag)` **之后** ⇒ **`fml` 在 `level.dat` 根层，与 `Data` 平级**。
- **读**：`LevelStorageAccess.readAdditionalLevelSaveData(boolean)`（`LevelStorageSource.java:466-476`，用 `readLightweightData`，**不过 DataFixer**）→ `CommonHooks.readAdditionalLevelSaveData`（`CommonHooks.java:1185-1252`）：逐条比对版本，不一致进 `mismatchedVersions`、缺失进 `missingVersions`，然后 `ModLoader.postEvent(new ModMismatchEvent(levelDirectory, ...))`（`:1226-1227`）。

**`PrimaryLevelData` 的 Neo 字段**

| 键 | 写 | 读 | 说明 |
|---|---|---|---|
| `forgeLifecycle` | `PrimaryLevelData.java:281`（`CommonHooks.encodeLifecycle`，`CommonHooks.java:1255-1262`） | `LevelSettings.java:42`（`CommonHooks.parseLifecycle`，`:1265-1272`） | 记 `stable` / `experimental` / `deprecated=N`；在 `WorldLoader.load` 的 `WorldDataSupplier.get` 阶段解析 |
| `neoDayTimeFraction` | `PrimaryLevelData.java:284` | `:206`（`asFloat(0f)`） | 支撑亚 tick 昼夜平滑 |
| `neoDayTimePerTick` | `PrimaryLevelData.java:285` | `:207`（`asFloat(-1f)`） | 同上 |

字段本体 `PrimaryLevelData.java:603-604`，访问器 `:608-623`。这两个字段最终被塞进 `ClientboundCustomSetTimePayload`（`MinecraftServer.java:1076`）发给客户端。

**`ServerLevel` 的 Neo 字段 / 附加状态**

- `dragonParts`：`ServerLevel.java:202`（`Int2ObjectMap<PartEntity<?>>`），配套 `getPartEntities()` `:1805`；卸载实体时单独处理末影龙部件（`:1761`、`:1788`）。
- `customSpawners`：`:199` 声明，`:304` 由 `ModifyCustomSpawnersEvent` 填充（**刻意移到构造器末尾**）。
- `capListenerHolder`：`:1814`，配合 `registerCapabilityListener`（`:1830`）——能力失效通知，不持久化。
- **`LevelAttachmentsSavedData`**：`ServerLevel.java:301` 调 `init(this)`，注册名为 `neoforge_data_attachments` 的 `SavedData`（`LevelAttachmentsSavedData.java:16-30`）。注意它存在**维度数据目录**里，**不是 `level.dat`**。
- `syncData(AttachmentType)`：`:1810-1811` → `AttachmentSync.syncLevelUpdate`。

### 10.5 NeoForge 对注册表 / 数据包加载的扩展

**`DataPackRegistriesHooks`**（`net/neoforged/neoforge/registries/DataPackRegistriesHooks.java`）

- `DATA_PACK_REGISTRIES = new ArrayList<>(RegistryDataLoader.WORLDGEN_REGISTRIES)`（`:25`），mod 通过 `DataPackRegistryEvent.NewRegistry` 追加。
- `grabNetworkableRegistries(List)`（`:31-52`，有 `StackWalker` 校验只允许 `RegistryDataLoader` 调用）被 `RegistryDataLoader.SYNCHRONIZED_REGISTRIES` 的静态初始化调用（`RegistryDataLoader.java:103-115`）—— **任何被 `DataPackRegistryEvent` 注册且带 `networkCodec` 的注册表都自动进入「登录时同步给客户端」的名单**。
- **在 `WorldLoader.load` 中的确切位置**：`WorldLoader.java:35-37`，用于 **WORLDGEN 层**，即「pack 打开之后（`:28`）、DIMENSIONS 层（`:39`）之前」。DIMENSIONS 层仍是 vanilla 的 `RegistryDataLoader.DIMENSION_REGISTRIES`（只有 `LEVEL_STEM`）。

**`RegistryManager`**

- `postNewRegistryEvent()`（`:82-106`）：`NewRegistryEvent` → `DataPackRegistryEvent.NewRegistry` → `ModifyRegistriesEvent`；末尾校验 `pendingModdedRegistries`。
- `initDataMaps()`（`:109-115`）：`RegisterDataMapTypesEvent`。
- `takeSnapshot` / `applySnapshot` / `revertToVanilla` / `revertToFrozen`（`:118-215`）。
- `generateRegistryPackets(boolean isLocal)`（`:245-251`）：**`isLocal == true` 时返回空列表** → 单人根本不发注册表同步包。
- `getRegistryNamesForSyncToClient()`（`:253-262`）：只挑 `registry.doesSync()` 的。

**`TagsUpdatedEvent`**（`TagsUpdatedEvent.java:14-19`，签名 `(RegistryAccess, boolean fromClientPacket, boolean isIntegratedServerConnection)`）

| 侧 | fire 点 | 参数 | 在 `WorldLoader.load` 中的步骤 |
|---|---|---|---|
| 服务端 | `ReloadableServerResources.java:143` | `(fullRegistryHolder.get(), false, false)` | `WorldLoader.java:52` 的 `.thenApplyAsync(... updateRegistryTags() ...)` —— **`loadResources` 完成之后、构造 `WorldStem` 之前** |
| 客户端 | `TagCollector.java:47` | `(registryAccess, true, isMemoryConnection)` | 收到 `ClientboundUpdateTagsPacket` 后，在 `handleConfigurationFinished` → `RegistryDataCollector.collectGameRegistries` 里 |

`NeoForgeEventHandler.tagsUpdated`（`NeoForgeEventHandler.java:104-109`）只在 `UpdateCause.SERVER_DATA_LOAD` 时 `DATA_MAPS.apply()`。

### 10.6 `ClientboundCustomPayloadPacket` 在这条流程里的全部出现点

**配置阶段（服务端发出）**

| 位置 | payload |
|---|---|
| `ServerConfigurationPacketListenerImpl.java:76` | `MinecraftUnregisterPayload` |
| `:77` | `MinecraftRegisterPayload` |
| `:78` | `ModdedNetworkQueryPayload` |
| `:84` | `BrandPayload`（vanilla） |
| `NetworkRegistry.java:365` | `ModdedNetworkSetupFailedPayload`（失败分支） |
| `NetworkRegistry.java:380` | `ModdedNetworkPayload` |
| `NetworkRegistry.java:383` | `MinecraftRegisterPayload` |
| `SyncRegistries.java:31/32/33` | `FrozenRegistrySyncStartPayload` / `FrozenRegistryPayload` ×N / `FrozenRegistrySyncCompletedPayload`（单人跳过） |
| `CommonVersionTask.java:28` | `CommonVersionPayload` |
| `CommonRegisterTask` | `CommonRegisterPayload` |
| `SyncConfig.java:30` | `ConfigFilePayload` ×N |
| `RegistryDataMapNegotiation` | `KnownRegistryDataMapsPayload` |
| `CheckExtensibleEnums.java:71` | `ExtensibleEnumDataPayload` |
| `CheckFeatureFlags.java:50` | `FeatureFlagDataPayload` |
| `NetworkRegistry.java:798/808` | 配置收尾的 `MinecraftUnregisterPayload` / `MinecraftRegisterPayload` |

**play 阶段（进世界相关）**

| 位置 | payload |
|---|---|
| `PlayerList.java:704-705` | `ClientboundCustomSetTimePayload`（有 channel 时；否则 vanilla `ClientboundSetTimePacket`） |
| `PlayerList.java:719` → `AttachmentSync.java:235` | `SyncAttachmentsPayload`（level 附件） |
| `PlayerList.java:205` `OnDatapackSyncEvent` → `NeoForgeEventHandler.java:141` | `RegistryDataMapSyncPayload`（memory connection 下跳过非同步动态注册表，`:123`） |
| `PlayerList.java:271` → `AttachmentSync.java:225` | `SyncAttachmentsPayload`（玩家自身附件） |
| `PlayerChunkSender.java:82` → `AttachmentSync.java:189-207` | `SyncAttachmentsPayload`（chunk + block entity），包在 `ClientboundBundlePacket` |
| `LevelChunkAuxiliaryLightManager.java:86-89` | `AuxiliaryLightDataPayload`（与区块包同 bundle） |
| `ServerEntity.java:302` → `AttachmentSync.java:212-217` | `SyncAttachmentsPayload`（实体） |
| `IEntityExtension.java:398` | `AdvancedAddEntityPayload` |
| `ServerPlayer.java:1152` | `AdvancedOpenScreenPayload` |
| `MinecraftServer.java:1076-1082` | `ClientboundCustomSetTimePayload`（周期同步） |

**客户端接收**

| 位置 | 处理内容 |
|---|---|
| `ClientConfigurationPacketListenerImpl.java:175-204` | `ModdedNetworkQueryPayload`→`:179`；`ModdedNetworkPayload`→`:186`；`ModdedNetworkSetupFailedPayload`→记 `failureReasons`（后续用 `ModMismatchDisconnectedScreen`，`:207-215`）；`BrandPayload` + `connectionType.isOther()`→`:199` |
| `ClientCommonPacketListenerImpl.java:157-193` | `MinecraftRegisterPayload`→`:159`；`MinecraftUnregisterPayload`→`:164`；`CommonVersionPayload`→`:169`；`CommonRegisterPayload`→`:174`；`isModdedPayload`→`NetworkRegistry.handleModdedPayload`（`:299`） |
| `ClientPacketListener.java:1942-2013` | play 阶段只处理 vanilla debug payload；其他 → `:2015 handleUnknownCustomPayload` |
| `NetworkRegistry.java:299-350` | `handleModdedPayload(...)` → 查注册表 → 调 `IPayloadHandler` |
| `ClientPayloadHandler.java` | `:59`/`:64`/`:69` 注册表快照三件套、`:92` `ConfigFilePayload`、`:98` `AdvancedAddEntityPayload`、`:115` `AdvancedOpenScreenPayload`、`:138` `AuxiliaryLightDataPayload`、`:157` `ClientboundCustomSetTimePayload`、`:167` `SyncAttachmentsPayload` |
| `ClientRegistryManager.java` | `:54 handleKnownDataMaps`、`:40 handleDataMapSync`（里面 post `DataMapsUpdatedEvent`，`:41`） |
| `Connection.java:273` | `NetworkFilters.injectIfNecessary(this)`（pipeline 插入 `DynamicChannelHandler` / `GenericPacketSplitter` / `VanillaConnectionNetworkFilter`） |

> **大包分片**：`GenericPacketSplitter.java:78`（识别 `SplitPacketPayload`）、`:165`（切包时重新包成 `ClientboundCustomPayloadPacket`）——payload 超过阈值时，进服期间的大 payload 会被自动拆成多个包。

### 10.7 不存在的名字 / 包路径对照

| 常见假设 | 1.21.1 实况 |
|---|---|
| `event/level/LevelChunkEvent.java` | **不存在**（全树 0 命中）。区块生命周期只有 `ChunkEvent.Load/Unload`；区块「tick」用 `LevelTickEvent` 代替 |
| `event/level/AddSectionGeometryEvent` | 存在，但包是 **`net.neoforged.neoforge.client.event`**，不可取消 |
| `event/world/ChunkWatchEvent` | 包是 **`net.neoforged.neoforge.event.level`**（`Watch` / `Sent` / `UnWatch`），三者都不可取消 |
| `event/tick/TickEvent`（`ServerTick`） | **没有 `TickEvent` 类**。实际是 `ServerTickEvent` / `LevelTickEvent` / `EntityTickEvent` / `PlayerTickEvent`（都在 `event/tick/`） |
| `fml/event/server/ServerAboutToStartEvent` 等 | 包是 **`net.neoforged.neoforge.event.server.*`**；`ServerLifecycleHooks` 在 `net.neoforged.neoforge.server` |
| `event/server/RegisterCommandsEvent` | 包是 **`net.neoforged.neoforge.event.RegisterCommandsEvent`**（不在 `event/server/`） |
| `OnDatapackSyncEvent` | 包是 `net.neoforged.neoforge.event.OnDatapackSyncEvent`（不在 `event/server/`） |
| `network/registry/NetworkRegistry` | 实际是 `net.neoforged.neoforge.network.registration.NetworkRegistry` |
| `AttachmentSync` | 实际在 `net.neoforged.neoforge.attachment.AttachmentSync`（不是 `network` 下） |
| `RegisterServerCommandsEvent` | **不存在** |
| `isLevelChunkLoaded` / `hasReceivedServerData` | **不存在** |
| `common/CommonHooks.onCreateWorldSpawn` | 实际在 `EventHooks.java:632`；`CommonHooks` 里只有 `onTravelToDimension:777` / `onChangeGameType:840` / `onDifficultyChange:260` / `readAdditionalLevelSaveData:1185` / `writeAdditionalLevelSaveData:1160` |
| `PlayerNegotiationEvent` | 类存在（`PlayerNegotiationEvent.java:24`），但**本树中找不到任何 fire 点**（`net/neoforged/fml` 未反编译）。**存疑，无法核对** |

---

## 11. 线程与阻塞全景

### 11.1 线程分工

| 线程 | 谁在跑 | 覆盖哪几步 |
|---|---|---|
| **主渲染线程（`gameThread` / Render thread）** | `runTick` 循环；`WorldOpenFlows` 全部方法；`Minecraft.doWorldLoad` 的创建部分；包应用；渲染；`compileSections` 调度 + 上传 GPU | 点击 → `openWorld` → 读 level.dat + DataFixer → `loadWorldStem` 起始 → `managedBlock` 等待 → `doWorldLoad` 建服 → `LevelLoadingScreen` 泵帧 → 建连接 → 收包建 `ClientLevel` → 渲染 |
| **"Server thread"** | `MinecraftServer.spin` 起（`MinecraftServer.java:267`）→ `runServer` → `initServer` → `loadLevel` → `createLevels` / `setInitialSpawn` / `prepareLevels` → `tickServer` → 末尾 `sendNextChunks` | 世界生成、区块加载、预加载、发包 |
| **`Util.backgroundExecutor()`**（`makeExecutor("Main")`，最多 255 线程，`Util.java:89`/`:195-197`） | ① 存档列表读取；② `ReloadableServerResources.loadResources`；③ `MinecraftServer.executor`；④ `ChunkMap` 的 `worldgen` / `light` mailbox；⑤ section 顶点编译 | 各阶段后台工作 |
| **`Util.ioPool()`** | `IOWorker`（`IOWorker.java:43-45`），region 文件读写 | 区块/实体/POI 存取 |
| **Netty / LocalChannel** | 解码 → `ensureRunningOnSameThread` 入队 → 抛异常结束本次读 | 网络 |
| **主线程执行器 = `Minecraft` 自己** | `WorldLoader.load` 的 `thenApplyAsync(p_214367_)`；`ReloadableServerResources` 的 `p_249601_` | 标签绑定 + 构造 `WorldStem`，保证落在主线程 |

### 11.2 异步 vs 阻塞（精确清单）

**异步（不阻塞主线程）**

- `LevelStorageSource.loadLevelSummaries`（`:175`）：`supplyAsync(..., Util.backgroundExecutor())`（`:179`/`:210`）
- `WorldLoader.load` 内部的数据包/资源重载（`WorldLoader.java:49-62`）
- `ReloadableServerResources.updateRegistryTags()` + `WorldStem` 构造（`WorldLoader.java:63-66`）
- `openWorldLoadBundledResourcePack` 的 `resources.zip` 加载链（`WorldOpenFlows.java:408-423`），`.thenAcceptAsync(..., this.minecraft)` 回到主线程
- 服务端侧的全部世界生成（Server thread 上的 `managedBlock` 循环推进）

**阻塞**

| 位置 | 性质 |
|---|---|
| `WorldOpenFlows.java:201` `managedBlock(future::isDone)` | 名义阻塞，**仍会泵主线程任务队列** |
| `Minecraft.java:2083-2085` `while (progressListener.get() == null) Thread.yield();` | **真忙等**，不泵任务、不渲染 |
| `Minecraft.java:2091-2099` `for (; !isReady; ) { tick; runTick(false); sleep(16); }` | 阻塞但会渲染（伪阻塞） |
| `ServerChunkCache.java:159` `mainThreadProcessor.managedBlock(...)` | 在 Server thread 上**泵任务式阻塞** |
| `MinecraftServer.java:503` `waitUntilNextTick()` | 同上，`prepareLevels` 靠它压住 Server thread |
| `LevelStorageSource.java:417` `tryLock` | 同步文件 I/O |
| `LevelStorageSource.java:221` `NbtIo.readCompressed` | 主线程同步读盘（可能几十 MB） |
| `LevelStorageSource.java:521` `NbtIo.writeCompressed` | 主线程同步写盘（`saveDataTag`） |
| `Minecraft.java:2162-2170` `disconnect()` 等服务器关闭 | `while (!integratedserver.isShutdown()) this.runTick(false);` |

**跨线程通信的关键机制**：`Minecraft.progressTasks`（`Minecraft.java:377`，`Queues.newConcurrentLinkedQueue()`）。服务器/工作线程通过 `ProcessorChunkProgressListener`（`:19-23`、`:31-35`）的 mailbox 把状态变更 push 进去，`ProcessorMailbox.tell`（`:107-124`）用 `dispatcher.execute(this)` 调度，`dispatcher` 就是 `this.progressTasks::add`。主线程在 `runTick` 开头排空（`Minecraft.java:1147-1150`）。

### 11.3 主线程 tick / 渲染的先后

`Minecraft.runTick(boolean)`（`:1135`）：

```
:1155  this.runAllTasks()          ← 成批应用本帧到达的所有网络包（在 tick 之前）
:1158-1166  tick 循环（最多 10 次）
:1177  起  Render 阶段
:1193  fireRenderFramePre
:1195  gameRenderer.render
:1197  fireRenderFramePost
```

`LevelRenderer.renderLevel`（`:914`）：

```
:924  this.level.pollLightUpdates()            ← 每帧最多抽 10 个光照任务
:926  getLightEngine().runLightUpdates()
...   setupRender → compileSections（调度 + 上传）
```

### 11.4 一次完整加载里「谁在等谁」

1. 主线程写回 level.dat 后启动 Server thread，然后 `Thread.yield()` 忙等 progress listener、再 `runTick(false)` 泵帧等 `isReady`。
2. Server thread 在 `loadLevel` 里同步压住，靠 `managedBlock` / `waitUntilNextTick` 泵自己的任务队列推进区块生成；区块 I/O 与噪声在 `ioPool` / `backgroundExecutor` 上，回调经 `queueSorter` 回到 Server thread。
3. `isReady` 置位后主线程建本地连接，登录 → 配置 → play。
4. Server thread 在 `placeNewPlayer` 发完初始化包，然后在每个 tick 末尾 `sendNextChunks`。
5. 主线程在 `runAllTasks` 里成批应用区块包，光照任务排进每帧队列。
6. 渲染帧里 `compileSections` 调度 section 编译 → worker 生成顶点 → Render thread 上传 → `compiled` 写回。
7. `LevelLoadStatusManager.tick()` 发现玩家脚下 section 已编译 → `LEVEL_READY` → 关屏。**此刻离「完全加载」还很远。**

---

## 12. 易误解点与坑（汇总）

| # | 结论 |
|---|---|
| 1 | **单人加载存档 = 完整的服务器启动 + 完整的网络登录**，走 `LocalChannel` 内存通道，不是捷径 |
| 2 | **`session.lock` 在 `openWorld` 第三行就拿了**（`LevelStorageSource.java:417`），不是进世界时才拿 |
| 3 | **level.dat 完整读取 + 三重 DataFixer（LEVEL / PLAYER / WORLD_GEN_SETTINGS）在主线程同步执行**；升级后的 NBT 在 `Minecraft.doWorldLoad:2060` 才写回。加上存档列表那次，DataFixer 共跑 3 次 |
| 4 | **`RULE_SPAWN_CHUNK_RADIUS` 默认值被 NeoForge 从 10 改成 2**，所以 `prepareLevels` 默认只预加载 25 个区块，且是**同步阻塞**等它们到 BLOCK_TICKING |
| 5 | **1.21.1 首次进世界不发 `ClientboundRespawnPacket`**，`ClientLevel` 只建一次。感觉「重建」是因为 `handleLogin` 内部设了两次屏（第二次的 supplier 是 `levelReady`） |
| 6 | **服务端「你可以收区块了」的信号是 `ClientboundGameEventPacket(LEVEL_CHUNKS_LOAD_START)`**（`PlayerList.java:717`），客户端据此从 `WAITING_FOR_SERVER` 进入 `WAITING_FOR_PLAYER_CHUNK` |
| 7 | **单人第一次 `sendNextChunks` 会一次性发完视距内所有已就绪区块**（`memoryConnection` 分支，`PlayerChunkSender.java:85-111`），因为本地通道不需要限流 |
| 8 | **`ReceivingLevelScreen` 关闭的判据是「玩家脚下那一个 section 编译并上传完成」**，跟「区块包收齐」没关系；30 秒是硬超时兜底 |
| 9 | **`LevelEvent.Load` 在 `ClientLevel` 构造函数里就 fire 了**（`ClientLevel.java:203`），比 `Minecraft.setLevel` 还早 |
| 10 | **`LevelEvent.Load(overworld)` 在 `setInitialSpawn` 之前 fire**（`MinecraftServer.java:375` vs `:378`）。要改出生点请用可取消的 `LevelEvent.CreateSpawnPosition` |
| 11 | **`RegisterCommandsEvent` 在 `WorldLoader.load` 阶段就 fire 了**，早于 `IntegratedServer` 的创建 |
| 12 | **`ClientboundLevelChunkWithLightPacket` 的光照 mask 传 `null`**（表示全部光照段，不做增量）；NeoForge 会把它换成 `ClientboundBundlePacket`，内含一个 `ClientboundCustomPayloadPacket(AuxiliaryLightDataPayload)` |
| 13 | **section 顶点构建在 worker 线程，但 `VertexBuffer.upload` 在 Render thread**（`uploadSectionLayer` 的 executor 是 `Queue::add`，真正执行在 `uploadAllPendingUploads`） |
| 14 | **`isSectionCompiled` 查的是「编译 + 上传完成」**，不是「排进队列」也不是「顶点构建完」 |
| 15 | **`compileSections` 只遍历 `visibleSections`（视锥 ∩ 遮挡图可达）**，所以视锥外的区块永远不会被编译 —— 「全世界完全加载」不会自然发生 |
| 16 | **`hasRenderedAllSections()` / `isQueueEmpty()` 会假阳性**（正在 worker 上跑的任务不计入 `toBatchCount`），不能当作可靠的完成信号 |
| 17 | **没有任何代码用队列长度决定关不关 `ReceivingLevelScreen`**；`hasRenderedAllSections()` 全树只有一个业务消费者，是自动世界缩略图（`GameRenderer.java:1171`） |
| 18 | **`LevelLoadingScreen` 不是 pause screen**，所以加载期间集成服务器不会暂停，`prepareLevels` 能正常推进 |
| 19 | **`LevelLoadingScreen` 显示的是 `ChunkStatus` 网格**，真正的「进度阶段」就是那 12 个 `ChunkStatus` |
| 20 | **`AddSectionGeometryEvent` 每次 section 重编译都 fire**，且一旦有 mod 注册 renderer，空 section 也会被强制编译（`SectionRenderDispatcher.java:445` 的 `additionalRenderers.isEmpty()`） |
| 21 | **`net/neoforged/fml` 未包含在源码树里**，所以 `ModLoader.postEvent` 系列事件的 fire 点实现不可见，只能看到调用方 |
| 22 | **memory connection 下注册表同步被完全跳过**（`ConfigurationInitialization.java:38`），既不发 NeoForge 的 `FrozenRegistry*` 系列，也不发 vanilla 的 `ClientboundRegistryDataPacket` |

---

## 附录 A：关键文件索引

| 主题 | 文件 |
|---|---|
| 客户端入口 / 世界切换 | `net/minecraft/client/Minecraft.java`（`doWorldLoad:2054`、`setLevel:2113`、`setScreen:1013`、`clearLevel:2200+`） |
| 存档打开流程 | `net/minecraft/client/gui/screens/worldselection/WorldOpenFlows.java` |
| 存档列表 | `net/minecraft/client/gui/screens/worldselection/WorldSelectionList.java`、`SelectWorldScreen.java` |
| 存档目录 / level.dat / 锁 | `net/minecraft/world/level/storage/LevelStorageSource.java`、`DirectoryLock.java` |
| 数据包 + 注册表组装 | `net/minecraft/server/WorldLoader.java`、`WorldStem.java` |
| 加载界面 | `net/minecraft/client/gui/screens/LevelLoadingScreen.java`、`ReceivingLevelScreen.java`、`GenericMessageScreen.java` |
| 集成服务器 | `net/minecraft/client/server/IntegratedServer.java` |
| 服务器主循环 / 建维度 / 出生点 / 预加载 | `net/minecraft/server/MinecraftServer.java`（`loadLevel:328`、`createLevels:356`、`setInitialSpawn:432`、`prepareLevels:490`） |
| 维度实现 | `net/minecraft/server/level/ServerLevel.java` |
| 区块调度 / region 读写 | `net/minecraft/server/level/ServerChunkCache.java`、`ChunkMap.java`、`ChunkHolder.java`、`ChunkLevel.java`、`ChunkGenerationTask.java`、`storage/ChunkSerializer.java`、`storage/ChunkStorage.java`、`storage/RegionFileStorage.java` |
| 玩家进服 / 发包 | `net/minecraft/server/players/PlayerList.java`、`network/ServerGamePacketListenerImpl.java`、`network/PlayerChunkSender.java` |
| 客户端收包 / 建世界 | `net/minecraft/client/multiplayer/ClientPacketListener.java`、`ClientLevel.java`、`ClientChunkCache.java`、`LevelLoadStatusManager.java` |
| 渲染 / section 编译 | `net/minecraft/client/renderer/LevelRenderer.java`、`chunk/SectionRenderDispatcher.java`、`chunk/RenderChunkRegion.java`、`chunk/RenderChunk.java`、`ViewArea.java`、`SectionOcclusionGraph.java`、`chunk/SectionCompiler.java`、`chunk/RenderRegionCache.java` |
| 网络底层 | `net/minecraft/network/Connection.java`、`protocol/PacketUtils.java` |
| NeoForge 生命周期 | `net/neoforged/neoforge/server/ServerLifecycleHooks.java` |
| NeoForge 通用钩子 | `net/neoforged/neoforge/common/CommonHooks.java`、`event/EventHooks.java` |
| NeoForge 客户端钩子 | `net/neoforged/neoforge/client/ClientHooks.java` |
| NeoForge 注册表 / 数据包 | `net/neoforged/neoforge/registries/DataPackRegistriesHooks.java`、`RegistryManager.java` |
| NeoForge 网络 | `net/neoforged/neoforge/network/registration/NetworkRegistry.java`、`network/ConfigurationInitialization.java` |
| NeoForge 附件同步 | `net/neoforged/neoforge/attachment/AttachmentSync.java` |
| 区块加载事件 | `net/neoforged/neoforge/event/level/LevelEvent.java`、`ChunkWatchEvent.java`、`ChunkEvent.java`、`client/event/AddSectionGeometryEvent.java` |

## 附录 B：未验证 / 存疑事项

以下条目是**从代码推导出的结论，未做实机验证**，或原调研中存在不确定性，列出以备核对：

1. **`ClientboundLightUpdatePacketData` 的 `null` mask 语义**：我从 `sendChunk` 传 `null, null` 的实参推断为「全部段」，未逐行读该类的位图逻辑。
2. **单人第一次 `sendNextChunks` 是否真的一次发完**：从 `collectChunksToSend` 的 `memoryConnection` 分支 + `Connection.connectToLocalServer` 建 `LocalChannel`（`Minecraft.java:2104`）推断 `isMemoryConnection() == true`，两点自洽但未实测发包数量。
3. **「Loading terrain 期间玩家会不会真掉下去」**：从 `Player.isAlwaysTicking() == true` + `ClientLevel.hasChunk` 恒 true + 缺区块返回 `emptyChunk` 推出；未实机验证。实践上被 `ClientboundPlayerPositionPacket` 覆盖。
4. **`MinecraftServer.java:425` 的 `levels.get(resourcekey)` 跨泛型查找**：能正常工作的依据是 `Registries.DIMENSION` 与 `Registries.LEVEL_STEM` 共用 registry key `"minecraft:dimension"`（`Registries.java:231-232`）+ `ResourceKey` 的 interning（`ResourceKey.java:33-37`、`:76-77`），未实际运行验证。
5. **`ServerLevel.java:265` 的 `ensureStructuresGenerated()`**：`ChunkGeneratorStructureState.generatePositions()`（`:81-106`）返回的 `CompletableFuture` 被丢弃，是 fire-and-forget；但 `possibleStructureSets()` 遍历本身是同步的。若关心「建维度时是否会发生同步结构计算阻塞」，需要再看 `NoiseBasedChunkGenerator.createState` 的开销，本次未深入。
6. **`LevelRenderer.isSectionCompiled` 对 `viewArea` 的 NPE 风险**：正常路径下 `viewArea` 由 `LevelRenderer.setLevel → allChanged`（`:738`）建立、`Minecraft.setLevel:2117` 已调用，所以非空；绕过 `setLevel` 的路径未验证。
7. **`SectionRenderDispatcher` 的 batching 线程归属**：`mailbox = ProcessorMailbox.create(executor, "Section Renderer")`（`:84`）跑在 `backgroundExecutor` 上，不是独立线程也不是 Render thread。这与某些旧版本「有专门 Render thread 排队」的印象不同。
8. **`PlayerNegotiationEvent` 的触发时机**：类存在但本树找不到 fire 点（`net/neoforged/fml` 未反编译），无法核对。
9. **1.18–1.20 各版本 `AddSectionGeometryEvent` 的形态差异**：本树只有 1.21.1 的源码，未做跨版本对比。
10. **`VertexBuffer` / `ByteBufferBuilder` 的 1.21.1 实现**：「上传在 Render thread 执行」的推导依据是 `runAsync(runnable, this.toUpload::add)` 这个 Executor 只做入队 + `uploadAllPendingUploads` 在 `LevelRenderer.java:1991` 被 Render thread 调用；底层实现未逐一核对。
