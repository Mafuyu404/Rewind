# Rewind 开发与迁移规范

本仓库使用 `common + targets/<loader>-<minecraft-version>` 结构维护多个 Minecraft 加载器和版本。任何开发者或自动化代理在修改前都必须遵守本文件；更完整的协作、评审和 CI 规则见 [docs/MAINTENANCE_WORKFLOW.md](docs/MAINTENANCE_WORKFLOW.md)。

## 架构边界

```text
common/                         纯 Java 的共享逻辑与共享资源
targets/<loader>-<mc-version>/  一个独立的加载器 + Minecraft 版本工程
gradle/target-conventions/      所有 target 共用的构建约定
```

- `common` 只放 Java 8 兼容、无 Minecraft/loader 依赖的业务逻辑、DTO、算法和测试。
- `targets/*` 只放该 target 的入口、注册、事件、Minecraft API、Mixin、网络、渲染和 metadata。
- 不要在 `common` 引用 `net.minecraft.*`、Forge、NeoForge、Fabric、Mixin 或渲染/网络 API。
- 不要用运行时版本判断、反射或同名 class 覆盖来兼容不同 target；不同 API 应由各 target 的适配器实现。
- 一个发布 jar 只对应一个 loader 与一个 Minecraft 版本，禁止 universal jar。

## 文件与配置约定

| 内容 | 位置 | 规则 |
| --- | --- | --- |
| 共享 Java 代码 | `common/src/main/java/` | 必须保持 Java 8 与无平台依赖。 |
| 共享资源 | `common/src/main/resources/` | 会自动合并到所有 target 的最终 jar。 |
| 目标专属资源 | `targets/<name>/src/main/resources/` | 仅放该版本/loader 专属资源。 |
| Loader metadata | target 的 `src/main/resources/` | 保留在 target；Fabric、Forge、NeoForge 格式不可共用。 |
| 版本/loader 参数 | `targets/<name>/gradle.properties` | 不放在仓库根 `gradle.properties`。 |
| 共享模组信息 | 根 `gradle.properties` | 仅 `mod_*` 和 Gradle 运行参数。 |
| 本地 jar 依赖 | `targets/<name>/libs/` | 自动作为 `implementation` 依赖读取；不需要逐条声明。 |
| 测试装置 | `targets/<name>/testdata/` | 仅供开发/自测的数据包与夹具；手工拷进 `run/saves/<世界>/datapacks/`，不进发行 jar。 |
| 发布配置 | `gradle/target-conventions/publish.gradle` | 统一管理，target 不复制发布逻辑。 |

`libs/` 中的普通 jar 不会自动带来传递依赖；依赖的其他 jar 也必须放入同一个 `libs/`，或改用正常的 Maven 依赖声明。不要把 `*-sources.jar`、`*-javadoc.jar` 或构建产物误放入此目录。

共享资源与 target 资源若有同路径文件，必须明确选择唯一归属；不要依赖覆盖顺序。加载器 metadata、Mixin 配置、access widener/access transformer 和版本专属语言文件一律归 target。

## 日常开发

1. 先判断改动是 `common`、单个 target、多个 target，还是构建/发布配置。
2. 单 target 改动只修改对应 `targets/<name>/`；不因方便而改动其他版本。
3. 修改 `common` 前先定义不含 Minecraft 类型的语义与接口，再为所有受影响 target 实现桥接。
4. 改动资源、metadata、Mixin、注册、事件、网络或渲染时，除构建外必须做相应的运行验证。
5. 不提交 token、账号、密码、私有仓库凭据、IDE 运行缓存或 `build/` 输出。

### 构建命令

每个 target 是独立 Gradle 根工程，应在它自己的目录中构建：

```powershell
cd targets\forge-1.20.1
.\gradlew.bat clean build
```

| Target | Gradle JVM |
| --- | --- |
| `forge-1.20.1` | JDK 21 |
| `fabric-1.20.1` | JDK 21 |
| `neoforge-1.21.1` | JDK 21 |
| `neoforge-26.1` | JDK 25 |

根项目的 `-PallTargets=true build` 只覆盖前三个 JDK 21 target，不能替代 NeoForge 26.1 的独立构建。

### 本地端到端自测（neoforge-1.21.1）

```powershell
cd targets\neoforge-1.21.1
# 先准备测试世界：把要用的存档复制成 run\saves\rewind_test，
# 再把 testdata\rewind_test 放进它的 datapacks\ 目录
.\gradlew.bat runClient -PrwQuickPlay=rewind_test -PrwSelfTest=true
```

`-PrwQuickPlay=<存档目录名>` 追加 `--quickPlaySingleplayer`，`-PrwSelfTest=true` 打开模组内置自测（建存档点 → 改世界 → 回溯 → 校验世界状态）。结论看 `run/logs/latest.log` 里的 `REWIND_SELFTEST PASS` / `REWIND_SELFTEST FAIL:`，自测跑完会自行退出客户端。两个参数都不传时 `runClient` 行为不变。

改 target 构建时容易踩的两个坑，只在 dev 运行下暴露：

- **common 的类进不了 MOD_CLASSES**：ModDevGradle 只把 `neoForge { mods { ... } }` 里声明的 sourceSet 输出交给 FML，`implementation project(':common')` 不会进入游戏真正使用的 legacy classpath。target 需要额外声明 `additionalRuntimeClasspath project(':common')`（见 `targets/neoforge-1.21.1/build.gradle`），否则 dev 下会 `NoClassDefFoundError`。
- **包名不能与 target 重叠**：dev 下 common 的 jar 是独立自动模块，若它的包与 target 模组模块的包同名（例如都在 `cc.sighs`），ModLauncher 会以 `ResolutionException: Modules ... export package ...` 启动失败。

### 回滚窗口与快速重启（neoforge-1.21.1）

F8 回溯分三步：关世界 → 用快照覆盖存档文件 → 重新开世界。前两步之间那段「世界马上要被整体覆盖」的时间叫**回滚窗口**，由 `Rewind.beginDiscard()` / `endDiscard()` 标记（`CheckpointController` 在按下 F8 时打开、快照写完后关闭，失败路径也会关）：

- 窗口内所有世界落盘由 `cc.sighs.mixin.RollbackDiscardMixins` 跳过：区块 / 实体 / 玩家数据 / 维度数据 / region 写入 / level.dat，同时跳过 `stopServer` 那个「排空 chunkMap」的循环；关句柄与释放 `session.lock` 不受影响。**改动任何保存路径时要回来对照这些注入点**——漏掉的写入虽然随后会被快照覆盖，但会把文件 mtime 改脏，让反向增量还原误判成「变了」而白拷一遍。
- 重新开世界走**快速重启**：复用上一轮的 `LayeredRegistryAccess` 与 `ReloadableServerResources`（重建世界时它们不会被关闭），只重读 level.dat（`LevelStorageSource.getLevelDataAndDimensions` + 重新 bake DIMENSIONS 层），再自己驱动 `Minecraft.doWorldLoad`，从而跳过 `WorldLoader.load` 的数据包 / 注册表 / 配方 / 战利品 / 标签 / 函数重载。任何一步失败都会自动退回 `WorldOpenFlows.openWorld`（见 `CheckpointController.tryFastRestart`）。
- 回溯时的文件回拷是**反向增量**：用建点时记录的清单判断活动存档里哪些文件还是原样，只回拷真正被改写过的，并把回拷文件的 mtime 拨回建点时的值（`SnapshotMirror.Direction.TO_WORLD`）。

### 原地回滚（neoforge-1.21.1）

F8 默认走**原地回滚**（`cc.sighs.rewind.server.InPlaceRollback`）：世界不关、客户端不重登，在活着的集成服务器里把世界倒回存档点。整段跑在服务端线程上，客户端只轮询 `CheckpointController` 的几个标记（`Phase.ROLLING_BACK`）。失败会自动退回上面那条「关世界 → 覆盖 → 重开」的老路。

顺序（每一步都有非它不可的理由，改之前先读 `InPlaceRollback` 的类注释）：

0. **先排空排队中的写入**：`Rewind.endDiscard()` → 三条存储链 `synchronize(false)` → `beginDiscard()`。已经排队但还没落盘的写入如果被跳过，这些区块在内存里已经不是 `isUnsaved()`、磁盘上又还没变，下面的文件判据都会漏掉它。这段时间世界不会 tick（我们就在服务端线程上），所以不会有新的写入插进来。
1. **找受影响区块**：遍历已加载的 `ChunkHolder`，四条判据任一成立就算受影响——`isUnsaved()`；**区块 / 实体 / POI** 三个 region 文件的 per-chunk 偏移/时间戳与快照不一致（读 .mca 头部那 8 KiB）；内存里还有会被保存的实体；快照里这个区块本来有实体。所以代价与「真正改了多少区块」成正比，与视距无关；已在下沉路上的区块（ticket level > `ChunkLevel.MAX_LEVEL`）直接跳过。
   > 实体那两条判据是必须的：`EntityStorage` 是**另一套**文件（`<维度>/entities/r.x.z.mca`），而且实体存储**没有 per-chunk 脏标记**、只在自动保存时才整体重写，所以「建点之后新放的实体」在落盘之前磁盘上完全看不出来，`isUnsaved()` 也不会被置位（`setUnsaved(true)` 的来源只有方块 / 方块实体 / 光照 / 计划刻 / 结构）。
2. **强制卸载**：把 ticket level 顶到 `ChunkLevel.MAX_LEVEL + 1`，挂进 `DistanceManager.chunksToUpdateFutures`，再反复推进 `ChunkMap.processUnloads` / `ServerChunkCache.runDistanceManagerUpdates`。回滚窗口开着，卸载触发的落盘被跳过。
   > 必须同时挡掉**重建**：玩家 ticket 一直认为这些区块该加载，ticket 图的邻居传播会把 `ChunkMap.updateChunkScheduling` 又叫回来把 holder 重建出来，重建的 holder 会走一遍晋升流水线、拉起 worldgen 任务，把 `generationRefCount` 钉在正被卸载的区块上——`processUnloads` 遇到 refCount 非 0 的 holder 直接跳过，卸载就永远跑不完（现象是 `stuck=` 一大堆、`unloadMs` 上千毫秒）。`InPlaceGuardMixins` 只在 `Rewind.beginUnloadGuard` 登记过的位置、且是「重建」这一种情形下拦掉，降级 / 卸载不受影响；名单在重建 holder 之前 `Rewind.clearUnloadGuards()` 清掉。卸载流水线连续 `STALL_ROUNDS` 轮没进展就放弃并记日志，不再空转。
3. **实体卸载排空**：由区块状态驱动、靠 `PersistentEntitySectionManager.tick()` 推进，而且实体还没读回来（status 不是 LOADED）时 `storeChunkSections` 会直接放弃、留到下一 tick。所以必须在这一段里泵到 `chunksToUnload` 空——否则那些被推迟的卸载会落到回滚窗口之外，把「改世界之后」的实体列表写回刚还原的文件。
4. **作废按区块缓存**：`EntityStorage.emptyChunks`（否则快照里本来有实体的区块会被当成空区块）与 POI 的分段缓存（否则留下幻影兴趣点）。必须在第 3 步之后做。
5. **释放 region 句柄**：Windows 上打开着的 .mca 会锁住文件。关之前先 `IOWorker.synchronize(false)` 排空排队中的写入，然后关掉 `RegionFile` 并**清空 `RegionFileStorage.regionCache`**——原版 `close()` 只关不清，不清的话下一次访问会拿到已关闭的句柄。
6. **回拷文件**：`SnapshotMirror` 反向增量，逻辑与老路共用。
7. **玩家 / 时间天气**：`playerdata` NBT → `player.load` + 手动补齐客户端同步；level.dat 的值写进活着的 `WorldData`（不动磁盘文件）。位置很讲究——**必须排在 holder 重建之后、区块成批重发之前**：
   - 不能更晚：恢复位置会走 `ServerGamePacketListenerImpl.teleport`，它设的 `awaitingPositionFromClient` 会让服务端**整段跳过右键方块交互**（`handleUseItemOn` 里那道 `awaitingPositionFromClient == null` 的门），而清掉它的客户端 ack 要排在几百个区块包后面；先发位置包，ack 就只要一个客户端 tick。
   - 不能更早：`player.load` 会走 `Entity.setPosRaw`，NeoForge 在那里加了 `level.getChunk(...)`（"ensure target chunk is loaded"），holder 不在就会在 `ChunkMap.acquireGeneration` 上 NPE，或者抛 `Chunk not there when requested`。
8. **重载**：给卸载掉的区块重建 holder，`ServerChunkCache.getChunk(..., FULL, true)` 同步等它读回来；原版的发送流水线会把新数据推给客户端（客户端 `replaceWithPacketData` 对已存在的区块是原地替换，不需要重连）。
9. **被强引用的 SavedData**：记分板、袭击——必须换掉实例 / 重灌内容，否则回滚不了。

回滚窗口**一直开到整段结束**（`run` 的 `finally` 才 `endDiscard()`）：这期间任何落盘都是要被丢弃的，第 3 步那些卸载收尾写入尤其危险。

需要的原版内部入口全部集中在 `cc.sighs.mixin.RollbackAccessMixins`（`@Accessor` / `@Invoker`），业务代码不直接碰反射。`RollbackDiscardMixins` 另外挡掉了 `ChunkMap.save(ChunkAccess)`——卸载路径本身会调它，回滚窗口内整段跳过（省掉 `ChunkSerializer.write`）。

**已知边界**：只还原记分板与袭击这两类 SavedData，其它 `<维度>/data/*.dat`（地图、自定义 boss 条等）只还原了磁盘文件，内存里的实例保持不变，会在下一次自动保存时把旧内容写回去；卸载时还有生成任务在飞的少数区块会被跳过（日志里以 `stuck=` 计数出现）。记分板不能走 `DimensionDataStorage.computeIfAbsent`：服务端建服时另建了一个只指向同一目录的存储实例，`ServerLevel` 手里拿不到那个；而且 `Scoreboard.addObjective` 对重名会抛异常，所以要先清掉现有 objective / team 再用 `dataFactory().deserializer()` 把快照内容灌回同一个记分板对象。另外，只要区块内存里还有会被保存的实体，它就会被判成受影响并重载——实体回滚的代价与「视野内有多少带实体的区块」相关，这是实体存储没有脏标记的直接后果。

自测里的耗时口径是「过渡淡入完成、真正开始动世界」到回溯结束，不含前面的淡入，两条路径用同一把尺子（`Rewind: rewind body finished in N ms`）。

### 过渡与「无界面」（neoforge-1.21.1）

F7/F8 全程不允许出现任何界面，也不往聊天框发任何提示（只写日志；`/rewind status` 是显式查询命令，保留输出）。观感由两种过渡承担：

- **热键在世界内任何地方都生效，包括 GUI 里**：`RewindClient` 监听 NeoForge 的 `InputEvent.Key`（原始按键），不用 `KeyMapping.consumeClick()`——原版只在 `screen == null` 时才给 KeyMapping 累计点击（`KeyboardHandler` 里 `flag4 = screen == null`），界面开着永远收不到。判定用 `KeyMapping.matches(key, scanCode)`，所以玩家在按键设置里改绑依然有效；F7/F8 仍然注册成 KeyMapping，只是不再靠它的点击计数。按下的时机如果撞上过渡或上一次没结束的操作，就记进 `pendingRequest` 等空下来再执行（等价于原版点击计数「攒着」的语义，而不是丢掉按键）。
- **按下 F7/F8 会先把当前界面摘掉**（`CheckpointController.closeScreen`）：过渡是整帧后处理（重采样整幅画面），界面开着既会挡在效果上面，也违反上面那条「过渡期间不得出现界面」。摘屏用的是 `setScreen(null)`，容器界面走原版 `removed()` 的正常关闭路径，不会丢东西。
- **「没有存档点」不是失败路径**：F8 在没建过存档点时只写一条日志，不动玩家当前的界面（原来会走 `fail()` 把界面关掉）。

- **所有过渡参数都在客户端配置里**：`RewindClientConfig`（`run/config/rewind-client.toml`，`ModConfig.Type.CLIENT`）管着淡入时长、存档/读档各自的淡出时长、饱和度倍数、模糊半径、读档的 settle tick。默认值与改造前写死的常量一致。值只在配置加载/重载时抄进 static 字段（后处理每帧都读，不能每帧查表），配置没加载成功时保持默认值。**加新的过渡可调项就往这里加**，别在 `RewindTransition` / 渲染器里再写死常量；着色器里的浓度/半径是 uniform（`SaturationBoost` / `BlurRadius`），由渲染器每帧从配置灌进去。
- **过渡包络**：`RewindTransition` 用真实时间推进 0 → 1 → 0（存档 = 饱和度提高，读档 = 高斯模糊）。`isFadeInDone()` 用来卡「等效果满强度之后再动手」，这样真正危险的动作玩家看不到。时长全部来自 `RewindClientConfig`；`HOLD_LIMIT_SECONDS`（25s）不是可调项，是「任何异常路径都不该让效果一直挂着」的兜底。另外读档「世界回来之后、开始淡出之前」那段等待是 `restoreSettleTicks`，和淡出时长是两笔账：读档完成后总共还糊多久 = `restoreSettleTicks / 20 + blurFadeOutSeconds`。
- **两种效果都是后处理**：`RewindTransitionRenderer` 在 `RenderFrameEvent.Post` 把画面重画一遍，`EffectMode` uniform 选效果（`1` = 饱和度，其它 = 高斯模糊），强度从 `RewindTransition.strength()` 来。三个必须记住的点：那一刻这一帧**还没** blit 到屏幕，所以要画进**主渲染目标**而不是帧缓冲 0；必须「读副本、写主目标」，所以每帧先把当前帧拷进自己的 `TextureTarget`——世界被拆掉之后，这张副本同时就是唯一还能显示的遮罩内容；强度为 0 时着色器要逐位还原原画面（饱和度那条靠 `mix(vec3(luma), rgb, 1.0 + 0.0)`）。着色器在 `assets/rewind/shaders/core/rewind_transition.{json,vsh,fsh}`，**JSON 里的 `vertex`/`fragment` 必须带命名空间**（`rewind:rewind_transition`，否则会去 `minecraft:` 找）。存档的浓度由 fsh 里的 `SATURATION_BOOST` 定（当前 1.5 = 满强度 2.5 倍饱和度）；它是线性的 `mix(luma, rgb, 1 + boost)`，再往上调低亮度通道会先被 clamp 掉、开始出现色块，那时该换成保亮度/保色相的写法而不是继续加常数。
- **存档不再动摄像机 FOV**：原来的广角是靠 `ViewportEvent.ComputeFov` 把 FOV 拉到 120°，已经整段删掉（连带 `RewindScreens.onComputeFov` / `FOV_MAX`）；现在改的是像素，玩家设置一概不动。
- **世界还在时不挂屏**（F7）：改成在服务器线程上 `await` 一个 latch 冻结世界（落盘完成后摁住，快照写完放行，服务端侧 20s 超时兜底），等价于原来的「暂停世界」但不产生任何界面。
- **世界不在时必须挂一个「逻辑屏」**（F8 重载期间）：`RewindBlankScreen` 不画任何东西，但必须存在——原版假设「没有世界就一定有界面」（`Minecraft.tick()` 里 `handleKeybinds` 直接访问 `player`），不挂会 NPE 崩客户端。世界回来后立刻摘掉。
- **拦掉原版加载屏**：`RewindScreens` 只在过渡进行期间取消 `GenericMessageScreen` / `LevelLoadingScreen` / `ProgressScreen` / `ReceivingLevelScreen` 的打开。过渡期间不碰 `Options.hideGui`（HUD/手持物保持原样，只是在「世界已拆、新世界未到」的那一小段里画面来自冻结帧，本身就没有活的 HUD 可画）。后处理不可用时（着色器加载失败）不再拦截，让原版加载屏兜底，避免黑屏空档。
- **验证**：`-PrwSelfTest=true` 会断言「过渡期间除逻辑屏外不得出现任何界面」，并在满强度存一张 `run/screenshots/rewind-<效果>.png`（`rewind-SATURATION.png` / `rewind-GAUSSIAN_BLUR.png`）——过渡是视觉效果，日志看不出对错，需要人眼（或视觉模型）确认。

### 发布

在对应 target 内运行 `publishMods` 可手动发布该 target 的 jar 到 CurseForge 和 Modrinth。根 `gradle.properties` 中配置非敏感项目 ID；token 只通过环境变量提供：

```powershell
$env:CURSEFORGE_TOKEN = '...'
$env:MODRINTH_TOKEN = '...'
.\gradlew.bat publishMods
```

发布前必须执行该 target 的 `clean build`，检查 jar 内的 metadata、共享 class、共享资源和版本范围。不要从根项目或错误 target 发布。

## 将既有项目迁入本框架

迁移应以“先可构建、再抽取共享代码、最后验证行为”为顺序，禁止先删除旧工程再尝试恢复。

1. **盘点原项目**：记录 Minecraft 版本、loader、JDK、Gradle、mappings、入口、Mixin、资源、数据生成、依赖与运行配置。
2. **建立 target**：为每个 `(loader, Minecraft 版本)` 建立 `targets/<loader>-<mc-version>/` 独立工程，包含 wrapper、`settings.gradle`、本地 `gradle.properties` 与 `../../common` 映射。
3. **复制专属层**：将入口、注册、事件、Mixin、渲染、网络、metadata 和版本专属资源放入对应 target；不要在一个 target 放多版本分支。
4. **抽取 common**：仅将不使用平台类型的状态、规则、计算、DTO 和接口迁到 `common`。把 Minecraft 对象转换为 primitive、字符串、UUID 或自定义 DTO 后再跨边界传递。
5. **迁移资源**：所有 target 共用的 assets/data/lang 放到 `common/src/main/resources/`；将 Fabric/Forge/NeoForge metadata 与版本专属 Mixin 配置保留在 target。
6. **迁移依赖**：可从公开仓库解析的依赖写入对应 target 的 `build.gradle`；仅本地提供的 jar 放入该 target 的 `libs/`。不要把 loader 依赖放入 `common`。
7. **迁移配置**：模组名称、ID、许可证、作者、描述等共享值放根 `gradle.properties`；Minecraft、loader、mappings、版本范围和 JDK 相关值放 target 本地属性。
8. **逐 target 验证**：使用要求的 JDK 运行 `clean build`，检查 jar 内容，并做最小 client 与 dedicated server 启动验证；涉及数据或资源时额外运行 data generation/reload 验证。
9. **记录差异**：不能立即统一的 API 或行为差异写入 `docs/version-differences/`，由 target 适配实现，不能以 common 中的版本判断掩盖。
10. **清理旧结构**：只有所有迁入 target 均可构建且已验证后，才删除旧代码、旧资源与旧构建入口。

## 提交前检查

- `common` 不含平台 import，且所有受影响 target 均已适配。
- 每个新增/修改的 target 使用正确 JDK 独立构建。
- 最终 jar 含正确 metadata、目标专属资源与共享 class/resources。
- 本地 `libs/` 内容明确且没有误提交的旧 jar。
- 发布相关改动不包含 token；项目 ID、版本类型、依赖关系和 changelog 已确认。
- README、版本差异文档和支持矩阵与实际 target 一致。
