# Rewind 开发与迁移规范

本仓库使用 `common + targets/<loader>-<minecraft-version>` 结构维护多个 Minecraft 加载器和版本。任何开发者或自动化代理在修改前都必须遵守本文件；更完整的协作、评审和 CI 规则见 [docs/MAINTENANCE_WORKFLOW.md](docs/MAINTENANCE_WORKFLOW.md)。

## 架构边界

```text
common/                         纯 Java 的共享逻辑与共享资源
targets/<loader>-<mc-version>/  一个独立的加载器 + Minecraft 版本工程
gradle/target-conventions/      所有 target 共用的构建约定
```

- `common` 只放 Java 8 兼容、无 Minecraft/loader 依赖的业务逻辑、DTO、算法、SPI 接口和测试。
- `targets/*` 只放该 target 的入口、注册、事件、Minecraft API、Mixin、网络、渲染、metadata，以及 SPI 的平台实现。
- 不要在 `common` 引用 `net.minecraft.*`、Forge、NeoForge、Fabric、Mixin 或渲染/网络 API。需要平台能力时，通过 `cc.sighs.rewind.common.spi.RewindPlatform` 暴露，由 target 实现并在模组构造阶段 `RewindPlatforms.install(...)` 装进来。
- 共享逻辑一律放 common（例如存档点引擎 `cc.sighs.rewind.common.core.CheckpointWriter`、槽位记录 `cc.sighs.rewind.common.store.SnapshotStore`、对外门面 `cc.sighs.rewind.api.RewindApi`）；target 不要复制一份。
- 不要用运行时版本判断、反射或同名 class 覆盖来兼容不同 target；不同 API 应由各 target 的适配器实现。
- 一个发布 jar 只对应一个 loader 与一个 Minecraft 版本，禁止 universal jar。
- `cc.sighs.rewind.api`、`cc.sighs.rewind.snapshot`、`cc.sighs.rewind.common.*` 三个包整体归 common，target 里不要再出现同名包——dev 下 common 的 jar 是独立自动模块，两边导出同一个包会以 `ResolutionException: Modules ... export package ...` 启动失败。

### SPI 边界

common 需要平台能力时只经 `cc.sighs.rewind.common.spi.RewindPlatform`（一个 target 一份实现，构造阶段用 `RewindPlatforms.install(...)` 装一次）：

| 接口方法 | 用途 | 实现要点 |
| --- | --- | --- |
| `modVersion()` | 元数据展示 | 读加载器的 mod 容器版本，失败退化成 `unknown` |
| `worldRoot(server)` | 活动存档目录 | 取 `level.dat` 所在目录 |
| `isSameThread(server)` | 同步入口的线程校验 | 服务器线程判定 |
| `isDedicatedServer / isPublished / hasPlayers` | 自动建点的跳过条件 | 服务器状态 |
| `flushForCheckpoint(server, slot, source)` | 强制落盘 + 采集展示元数据与背包快照 | 版本相关，最容易随 MC 版本变 |
| `supportsInPlaceRollback()` | 有没有原地回滚 | 没有就返回 false；`RewindApi.rollbackInPlace` 直接失败，客户端回退到「关世界 → 覆盖 → 重开」 |
| `rollbackInPlace(server, worldRoot, slot)` | 原地回滚引擎 | 依赖原版内部结构，按版本各写一份 |

参数里的服务器对象对 common 是不透明的 `Object`，实现方自己转型。`RewindApi` / `CheckpointWriter` / `AutoCheckpoint` 全靠这个接口拿世界根、线程判定、落盘与回滚能力。

新增一个 `(loader, MC 版本)` target 时，除了独立 Gradle 工程与 metadata，还要提供：① 一份 `RewindPlatform` 实现；② `cc.sighs.mixin` 下挂到该版本原版方法上的 Mixin（回滚窗口跳过落盘、区块重建守卫、自动保存挂点）；③ 客户端入口与事件注册、过渡渲染、时间树界面；④ 版本专属资源（mixins 配置、shader、loader metadata）。共享的存档点引擎、槽位格式、索引与时间线逻辑不需要复制。

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

配置项注释一律**两行：第一行中文、第二行英文**，一句话说清即可，不要展开解释（加载器自己追加的 `# Default:` / `# Range:` 不算）。四个 target 的措辞保持一致；Fabric 那份手写 properties 的注释集中在 `HEADER_LINES`，同样按「一中文一英文」成对写。

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

### target 支持状态

四个 target 现在都有完整实现：共享逻辑在 common，各 target 提供平台实现（`RewindPlatform`）、Mixin 与客户端。

| Target | MC / 加载器 | 客户端 AUI | 原地回滚 | 客户端钩子（NeoForge 事件的替代物） |
| --- | --- | --- | --- | --- |
| `neoforge-1.21.1` | 1.21.1 / NeoForge | 1.2.6 | 有 | NeoForge 原生事件（`RenderFrameEvent` 等） |
| `neoforge-26.1` | 26.1 / NeoForge | 1.2.6 | 有 | NeoForge 原生事件；`GuiGraphicsExtractor` / `Identifier` / `SavedDataStorage` 等 26.1 改名已消化 |
| `forge-1.20.1` | 1.20.1 / Forge | 1.2.6 | 有 | `TickEvent.RenderTickEvent` 代替帧 Pre/Post；`MinecraftForge.EVENT_BUS`；mixin 走 jar manifest 的 `MixinConfigs` |
| `fabric-1.20.1` | 1.20.1 / Fabric | 1.2.6 | 有 | 自写客户端 mixin：帧 Pre/Post 挂 `Minecraft.runTick`、原始按键挂 `KeyboardHandler.keyPress`、拦加载屏挂 `Minecraft.setScreen`；其余走 Fabric API 事件 |

四条已知的跨 target 差异（改代码前先看）：

- **配置文件**：NeoForge 两个 target 用 `ModConfigSpec`（`run/config/rewind-{common,client}.toml`）；Fabric 没有配置系统，`RewindServerConfig` / `RewindClientConfig` 是手写的 properties 实现（`config/rewind-{common,client}.properties`），**没有「外部改文件自动重载」**，只有界面上的 setter 会立刻落盘。
- **自动保存间隔**：1.20.1 原版没有 `computeNextAutosaveInterval` / `ticksUntilAutosave`，两个 1.20.1 target 改用 `@ModifyConstant` 替换 `tickServer` 里写死的 `6000`（`require = 0` 软挂点，挂不上只是「间隔设置不生效」）。
- **AUI 版本**：四个 target 统一 `1.2.6`（坐标分别是 `ApricityUI-{neoforge-1.21.1,neoforge-26.1,forge-1.20.1,fabric-1.20.1}`）。时间树用到的 DOM / 容器 API 在 1.2.4 → 1.2.6 之间保持同构（编译期已核），而 1.2.5.2 之前的「flat document 里画不出 `<img>`」的老问题在 1.2.6 上已不存在——四个 target 的时间树封面都应该能画出来（见「曾经的问题」一节）。**metadata 里也声明了这条依赖**（mod id `apricityui`，要求 `>= 1.2.6`）：NeoForge / Forge 各是一段 `[[dependencies.<mod_id>]]`（`side = "CLIENT"`，因为只有客户端的时间树用它），Fabric 写在 `fabric.mod.json` 的 `depends` 里（Fabric 没有分端的依赖声明）。
- **原地回滚引擎按版本各一份**：四个 target 各有一份 `InPlaceRollback`，依赖的原版内部结构不同（1.20.1 没有 `SimpleRegionStorage` / `generationRefCount`，26.1 的 `ChunkMap` 改继承 `SimpleRegionStorage`、`DimensionDataStorage` 改名 `SavedDataStorage`、`SavedData.save` 消失）。三份新引擎与 1.21.1 那份**步骤顺序与保护条件一致**，差异逐条写在各自的类注释里。

### 手动跑客户端 / 专用服务器

```powershell
cd targets\neoforge-1.21.1
.\gradlew.bat runClient
.\gradlew.bat runServer      # server run 自带 --nogui
```

**仓库里已经没有「自动跑游戏」的自测机制了**：`-PrwQuickPlay` / `-PrwSelfTest` 两个运行参数、
`RewindSelfTest` / `RewindServerSelfTest` 两个类、以及各 target 的 `testdata/` 都已删除。
要验行为就手动起客户端（F7 存档 / F8 回溯 / F9 时间树），看 `run/logs/latest.log`。

### 跑起来之后踩过的坑（各 target）

这些坑都是真起过游戏之后才暴露的，改这些地方前先看：

- **fabric 的 loom run 配置是 `programArgs`，不是 `programArgument`**（后者是 ModDevGradle 的 DSL）。写错不会让 `build` 失败，只会在 `runClient` / `runServer` 时直接 `BUILD FAILED in 1s`。
- **AUI fabric 的 SPI provider 是坏的**：`FabricAnnotationScanner` 只有 private 无参构造器，AUI 却用 `ServiceLoader.findFirst()` 取它 → `NoSuchMethodException`，客户端与专用服务器都起不来。`targets/fabric-1.20.1` 用 `cc.sighs.mixin.AuiFabricAnnotationScanMixins` 把那处调用重定向成反射构造绕开；AUI 上游修好后可删。neoforge / forge 的同名 provider 都是 public，不受影响。
- **forge 的 mod 依赖必须用 `modImplementation`**，不能是 `implementation`：1.20.1 Forge 的发行 jar 是 SRG、dev 是 named，`implementation` 不做 remap → 既有 `NoSuchMethodError: MenuScreens.m_96206_` 这类崩溃，也会让 `RewindTreeScreen` 里覆盖 `ApricityScreen` 的方法**一个都不生效**。
- **forge 需要 `pack.mcmeta`**（`pack_format` = 15）：没有它 Forge 会弹 `LoadingErrorScreen`（`failed to load a valid ResourcePackInfo`）并跳过 `setInitialScreen`，quickPlay 永远进不了世界。
- **1.20.1 的着色器名字**：`ShaderInstance(ResourceProvider, String, VertexFormat)` 把名字拼成 `shaders/core/<name>.json` 且固定去 `minecraft` 命名空间找，传 `rewind:rewind_transition` 会直接抛 `ResourceLocationException`。1.20.1 的 `RewindTransitionRenderer` 传裸名 + 一个重定向到 `rewind` 命名空间的 `ResourceProvider`，json 里的 `vertex` / `fragment` 也是裸名。
- **level.dat 的时间键是 `Time`**（游戏刻）/ `DayTime`，不是 `GameTime`——后者只是 getter 名，磁盘上从来不存在这个键；`InPlaceRollback.restoreWorldData` 读错键就静默不还原世界时间（四个 target 现在都用 `Time`）。
- **26.1 的维度目录是 `dimensions/<ns>/<path>/`**（主世界 `dimensions/minecraft/overworld`），玩家数据在 `players/data/`；凡是用 `region/`、`playerdata` 拼路径的地方都要按 `LevelResource` / `DimensionType.getStorageFolder` 算，别写死（common 的 `SnapshotLayout.isExcluded` 因此同时认 `playerdata/` 与 `players/data/` 两个位置下的玩家临时文件）。
- **26.1 的 `openWorld` 是异步的**（读 level.dat 走 `thenAcceptAsync`）：「关世界重开」的耗时必须在 `REVEALING` 阶段等到世界真的回来才停表，否则会算出几十毫秒的假数字。
- **26.1 的 `ChunkMap.saveChunksEagerly` 先清 `unsaved` 再异步落盘**：回滚窗口开着的几毫秒里若这次异步写被丢掉，就会出现「内存不脏 + 磁盘没变」→ 回滚什么都不做还报成功。`InPlaceRollback.run` 把 `endDiscard()` 提到最前面，把窗口从毫秒级压到几纳秒。
- **手动跑客户端时窗口要有焦点**：26.1 的 `pauseIfInactive()` 会在 `closeScreen` 摘屏那一帧立刻挂回 `PauseScreen`；`RewindScreens` 在过渡期间会把它一并拦掉，但自己手测时最好让窗口保持前台，否则看到的「界面没摘干净」可能是失焦造成的假象。

改 target 构建时容易踩的两个坑，只在 dev 运行下暴露：

- **common 的类进不了 MOD_CLASSES**：ModDevGradle 只把 `neoForge { mods { ... } }` 里声明的 sourceSet 输出交给 FML，`implementation project(':common')` 不会进入游戏真正使用的 legacy classpath。使用 legacy classpath 的 target 需要额外声明 `additionalRuntimeClasspath project(':common')`（见 `targets/neoforge-1.21.1/build.gradle`），否则 dev 下会 `NoClassDefFoundError`。**NeoForge 26.1 起 ModDevGradle 已经取消这条 classpath**（`VersionCapabilities.legacyClasspath()` 为 false，声明它会直接报 `there is no additional classpath anymore for Minecraft 26.1.2`）；那边 dev 运行直接用标准 `runtimeClasspath`，`implementation project(':common')` 就够了。
- **包名不能与 target 重叠**：dev 下 common 的 jar 是独立自动模块，若它的包与 target 模组模块的包同名（例如都在 `cc.sighs`），ModLauncher 会以 `ResolutionException: Modules ... export package ...` 启动失败。

### 存档 / 读档 API

**下面这一组行为契约对四个 target 都成立**（描述取自 1.21.1 的实现；各 target 的加载器/版本差异见「target 支持状态」）。对外入口是 `cc.sighs.rewind.api.RewindApi`，结果类型是 `cc.sighs.rewind.api.RewindResult`。分层：

```
common/api        RewindApi / RewindResult        对外门面；不引用任何 net.minecraft.* 类
common/core       CheckpointWriter                纯机制：索引、清单、块映射、镜像编排、时间线边
                  AutoCheckpoint                  「跟着原版自动保存建点」的策略与跳过条件
common/store      SnapshotStore / SnapshotBlockIo 槽位记录的删除改名、块存储会话
common/spi        RewindPlatform / FlushOutcome    平台能力接口（世界根、线程判定、落盘、原地回滚）
                  RollbackOutcome / RewindPlatforms  结果类型与平台实现的持有者
common/snapshot   Snapshot*                       槽位文件格式、索引、镜像、块存储、背包快照
target/server     WorldFlush / NeoForgeRewindPlatform  强制落盘 + 玩家展示信息；SPI 的 NeoForge 1.21.1 实现
                  InPlaceRollback                 原地回滚引擎（吃原版内部结构，按版本各写一份）
                  RewindServerConfig / RewindVersion  配置落盘与 mod 版本号（值存 common）
client            CheckpointController            客户端策略：过渡、界面、失败回退、状态机
                  RewindTreeScreen                「时间树」管理界面（Java 侧驱动 AUI 的 HTML）
                  RewindClient / RewindCommands   热键与命令，只做转交
```

`server` 之外的共享部分都在 common；target 侧只留「平台怎么接」和「原版内部长什么样」。`RewindPlatform` 的参数是各平台自己的服务器对象（不透明的 `Object`），target 在模组构造阶段 `RewindPlatforms.install(new NeoForgeRewindPlatform())` 装好，`RewindApi` 才拿得到世界根、线程判定与落盘/回滚能力。

- **同步入口**（直接干活，不带过渡、不碰界面）：`createCheckpoint(server, slot, source)` 与 `rollbackInPlace(server, slot)` **必须在服务端线程上调用**（进去会 `isSameThread()` 校验并报错）。没有界面就没法靠「暂停世界」保证拷贝期间没人写盘，让服务端线程忙在落盘和拷贝上等价于把它冻结——这也是要求服务端线程的原因。`restoreFiles(worldRoot, slot)` 是纯文件操作，任意线程可调。
- **带过渡的异步入口**（等价于按 F7 / F8）：`requestCheckpoint(source)` / `requestRollback(source)`。它们经 `ClientBridge` 转到 `CheckpointController`；专用服务器上没有客户端，调用返回 false 并写一条日志。这个桥做成接口就是为了让 `RewindApi` 本身不引用客户端类——主类是按 `FMLEnvironment.dist` 判定后才加载 `RewindClient` 的，`RewindApi` 必须能在专用服务器上被加载。带槽位的重载 `requestCheckpoint(slot, source)` / `requestRollback(slot, source)` 指向任意槽位；时间树界面上的「读取」走的就是它们（「覆盖」不走——它要界面一直开着，见「时间树」一节）。
- **读档冷却**：一次成功回溯之后要等一段时间才能再回溯，时长是 COMMON 配置 `rollback.cooldownSeconds`（`RollbackSettings.cooldownSeconds()`，默认 `0` = 关闭，上限 3600 秒）。起点 `lastRollbackAt` 记在存档点索引里，所以退出游戏重进也绕不过去、换存档目录各算各的；只挡回溯（冷却中 `rollbackInPlace` / `restoreFiles` 直接返回失败结果），建点不受影响。要问还剩多久用 `rollbackCooldownRemainingMillis(worldRoot)`（0 = 可以读档）。**客户端 `CheckpointController.requestRestore` 必须在开过渡之前就用它拦下**（F8 / 界面上的「读取」），被拦时只写一条日志、不动界面——不然被拒的原地回滚会误触发「关世界 → 覆盖 → 重开」那条回退路径。
- **查询**：`worldRoot(server)` / `hasCheckpoint(worldRoot, slot)` / `describe(...)` / `describeAll(worldRoot)`（一次读完整张索引，界面渲染整页槽位用它）/ `listCheckpoints(worldRoot)` / `readInventory(worldRoot, slot)` / `slots()`（界面固定展示的槽位顺序）/ `currentSlot(worldRoot)`（时间线的「头」：世界当前站在哪个存档点上，头所在的槽位被删了就返回空串），只读存档目录。命令层的 `/rewind status` 读的就是它们。
- **槽位管理**（纯文件操作，任意线程可调，但不要和存档点操作并发——两边都写同一张索引）：`deleteCheckpoint(worldRoot, slot)` 删掉槽位目录 / 清单 / 背包快照 / 索引条目（先摘索引条目再删文件，中途崩了留下的是没人引用的目录）；`renameCheckpoint(worldRoot, slot, displayName)` 只改索引里的显示名，槽位 id 与目录都不动，所以改名不会触发重拷，而且覆盖这个槽位时会保留（`CheckpointWriter.create` 从旧索引里抄回来）。
- **槽位清单**：`SnapshotLayout.uiSlots()` = `auto`、`quick`，然后是 8 个手动槽位 `s1`..`s8`。`quick` 就是 F7/F8 用的 `DEFAULT_SLOT`；`auto` 是「跟着原版自动保存建点」用的槽位（见「自动存档点」一节），也可以手动覆盖。槽位名会当目录名用，所以 `isValidSlotName` 限定 `[A-Za-z0-9_-]`；玩家起的名字由 `isValidDisplayName` 限定 1-20 字符。
- **结果**：`RewindResult` 是只读的（final 字段 + 静态工厂），`success` / `failure` / `millis` / `copiedFiles` / `skippedFiles` / `totalFiles` / `inPlace` / `summary`；`RewindApi.lastResult()` 给最近一次结果。`CheckpointController.lastRestore*()` 那几个给自测看的统计现在是它的转发。
- **按 4 KiB 扇区共享数据块**：`region` / `entities` / `poi` 的 `.mca` 不再整份拷贝，而是切成 4 KiB 块、以内容哈希命名存进 `rewind_snapshots/blocks/`（`SnapshotBlockStore`），每个槽位只多一份 `<槽位>.blocks` 映射（`SnapshotBlocks`，格式 `<大小>\t<相对路径>\t<块哈希…>`）。于是**换一个槽位建点只为真正变了的扇区付字节**。同一份 30 MB 测试世界的实测：整文件级只能省 8.3%（只有「从来没被写过的文件」跳得过），按扇区切块省 **79.9%**（一个 8.9 MB 的 region 文件实际只动了 19.7% 的扇区，整文件级却要整份拷）。块是不可变的（临时文件 + 原子替换），所以多个槽位引用同一个块永远安全。映射和清单一样，必须在把槽位标成 `complete` 之前落盘。
  > 两条必须记住的约定：**槽位目录里没有这些 `.mca` 的实体文件**（建点时会顺手删掉老格式留下的整文件），所以回溯方向的文件列表要把「目录 ∪ 清单 ∪ 块映射」并起来（`SnapshotMirror.sourceFiles`）——少一个就会漏还原，甚至反过来把世界里的 `.mca` 当多余文件删掉；`InPlaceRollback` 拿快照 `.mca` 头部做逐区块比对时要走 `snapshotHeader` 从块存储拼出前 8 KiB。删槽位时 `SnapshotBlockIo.collectGarbage` 按「所有槽位映射的并集」做标记-清除。老格式的槽位（槽位里是整文件、没有映射）照旧能还原——映射里有没有这个文件就是判断依据；`SnapshotMirrorTest` / `SnapshotBlockStoreTest` 盯着块编码、部分扇区复用、老格式兼容和「块缺失时宁可直接失败也不写半个文件」这几条。

`CheckpointController` 只保留「怎么让玩家看到这件事发生」：过渡包络、界面收放、原地回滚失败后退回「关世界 → 覆盖 → 重开」、以及把结果记下来给命令和自测看。状态机因此只剩 `WORKING`（服务端线程上跑 API 同步入口，客户端轮询）+ 回退路径的三个阶段 + `REVEALING`。

### 回滚窗口与快速重启（neoforge-1.21.1）

F8 回溯分三步：关世界 → 用快照覆盖存档文件 → 重新开世界。前两步之间那段「世界马上要被整体覆盖」的时间叫**回滚窗口**，由 `RewindState.beginDiscard()` / `endDiscard()`（common；target 的 `Rewind` 只是转发门面）标记（`CheckpointController` 在按下 F8 时打开、快照写完后关闭，失败路径也会关）：

- 窗口内所有世界落盘由 `cc.sighs.mixin.RollbackDiscardMixins` 跳过：区块 / 实体 / 玩家数据 / 维度数据 / region 写入 / level.dat，同时跳过 `stopServer` 那个「排空 chunkMap」的循环；关句柄与释放 `session.lock` 不受影响。**改动任何保存路径时要回来对照这些注入点**——漏掉的写入虽然随后会被快照覆盖，但会把文件 mtime 改脏，让反向增量还原误判成「变了」而白拷一遍。
  > 26.1 上的对应物：`SavedData.save(File, HolderLookup$Provider)` 已删除，存档数据的写盘汇点变成 `SavedDataStorage.scheduleSave()`；level.dat 那条从 `saveDataTag(RegistryAccess, WorldData, CompoundTag)` 变成了私有的 `saveLevelData(CompoundTag)`（两条公开入口都汇到它）。其余目标签名不变。
- 重新开世界走**快速重启**：复用上一轮的 `LayeredRegistryAccess` 与 `ReloadableServerResources`（重建世界时它们不会被关闭），只重读 level.dat（`LevelStorageSource.getLevelDataAndDimensions` + 重新 bake DIMENSIONS 层），再自己驱动 `Minecraft.doWorldLoad`，从而跳过 `WorldLoader.load` 的数据包 / 注册表 / 配方 / 战利品 / 标签 / 函数重载。26.1 起游戏规则也搬进了 SavedData，`doWorldLoad` 的 `Optional<GameRules>` 必须传**空**（与原版 `openWorld` 一致），规则才会从快照覆盖过的磁盘读回来；传回溯前捕获的那份会把旧规则灌回去、还会在下一次自动保存写回磁盘。任何一步失败都会自动退回 `WorldOpenFlows.openWorld`（见 `CheckpointController.tryFastRestart`）。
- 回溯时的文件回拷是**反向增量**：用建点时记录的清单判断活动存档里哪些文件还是原样，只回拷真正被改写过的，并把回拷文件的 mtime 拨回建点时的值（`SnapshotMirror.Direction.TO_WORLD`）。

### 原地回滚

F8 默认走**原地回滚**（`cc.sighs.rewind.server.InPlaceRollback`）：世界不关、客户端不重登，在活着的集成服务器里把世界倒回存档点。整段跑在服务端线程上，客户端只轮询 `CheckpointController` 的几个标记（`Phase.ROLLING_BACK`）。失败会自动退回上面那条「关世界 → 覆盖 → 重开」的老路。

`neoforge-26.1` 也接了同一份引擎：`NeoForge261RewindPlatform.supportsInPlaceRollback()` 返回 true，`rollbackInPlace` 走 26.1 版的 `InPlaceRollback`。步骤顺序、保护条件、`Result` 口径与 1.21.1 完全一致，换掉的只是原版内部入口——`DimensionDataStorage` → `SavedDataStorage`（缓存键变成 `SavedDataType<?>`）、`Raids.getFileId()/factory()` → `Raids.TYPE`、记分板改走 `ServerScoreboard.load(Packed)`、`player.load(CompoundTag)` → `player.load(ValueInput)`（维度读 `ServerPlayer.SavedPosition`）、天气从 level.dat 挪进服务器级 `WeatherData` SavedData、时间字段 `GameTime/DayTime` 变成单个 `Time`、出生点变成 `LevelData.RespawnData`。存档数据一律「作废缓存 → 从已被快照覆盖的磁盘重新读」，所以不硬编码 `data/*.dat` 的路径（26.1 的 SavedData 落盘是 `<dataFolder>/<namespace>/<path>.dat`）。逐条对照写在 `targets/neoforge-26.1/.../InPlaceRollback.java` 与 `RollbackAccessMixins.java` 的类注释里。

顺序（每一步都有非它不可的理由，改之前先读 `InPlaceRollback` 的类注释）：

0. **先排空排队中的写入**：`Rewind.endDiscard()` → 三条存储链 `synchronize(false)` → `beginDiscard()`。已经排队但还没落盘的写入如果被跳过，这些区块在内存里已经不是 `isUnsaved()`、磁盘上又还没变，下面的文件判据都会漏掉它。这段时间世界不会 tick（我们就在服务端线程上），所以不会有新的写入插进来。
1. **找受影响区块**：遍历已加载的 `ChunkHolder`，四条判据任一成立就算受影响——`isUnsaved()`；**区块 / 实体 / POI** 三个 region 文件的 per-chunk 偏移/时间戳与快照不一致（读 .mca 头部那 8 KiB）；内存里还有会被保存的实体；快照里这个区块本来有实体。所以代价与「真正改了多少区块」成正比，与视距无关；已在下沉路上的区块（ticket level > `ChunkLevel.MAX_LEVEL`）直接跳过。
   > 实体那两条判据是必须的：`EntityStorage` 是**另一套**文件（`<维度>/entities/r.x.z.mca`），而且实体存储**没有 per-chunk 脏标记**、只在自动保存时才整体重写，所以「建点之后新放的实体」在落盘之前磁盘上完全看不出来，`isUnsaved()` 也不会被置位（`setUnsaved(true)` 的来源只有方块 / 方块实体 / 光照 / 计划刻 / 结构）。
2. **强制卸载**：把 ticket level 顶到 `ChunkLevel.MAX_LEVEL + 1`，挂进 `DistanceManager.chunksToUpdateFutures`，再反复推进 `ChunkMap.processUnloads` / `ServerChunkCache.runDistanceManagerUpdates`。回滚窗口开着，卸载触发的落盘被跳过。
   > 必须同时挡掉**重建**：玩家 ticket 一直认为这些区块该加载，ticket 图的邻居传播会把 `ChunkMap.updateChunkScheduling` 又叫回来把 holder 重建出来，重建的 holder 会走一遍晋升流水线、拉起 worldgen 任务，把 `generationRefCount` 钉在正被卸载的区块上——`processUnloads` 遇到 refCount 非 0 的 holder 直接跳过，卸载就永远跑不完（现象是 `stuck=` 一大堆、`unloadMs` 上千毫秒）。`InPlaceGuardMixins` 只在 `Rewind.beginUnloadGuard` 登记过的位置、且是「重建」这一种情形下拦掉，降级 / 卸载不受影响；名单在重建 holder 之前 `Rewind.clearUnloadGuards()` 清掉。卸载流水线连续 `STALL_ROUNDS` 轮没进展就放弃并记日志，不再空转。
3. **实体卸载排空**：由区块状态驱动、靠 `PersistentEntitySectionManager.tick()` 推进，而且实体还没读回来（status 不是 LOADED）时 `storeChunkSections` 会直接放弃、留到下一 tick。所以必须在这一段里泵到 `chunksToUnload` 空——否则那些被推迟的卸载会落到回滚窗口之外，把「改世界之后」的实体列表写回刚还原的文件。
4. **作废按区块缓存**：`EntityStorage.emptyChunks`（否则快照里本来有实体的区块会被当成空区块）与 POI 的分段缓存（否则留下幻影兴趣点）。POI 的 `storage` 与 `dirty` 两个键**都要删**——只删 `storage` 的话键还留在 `dirty` 里，下一 tick `tick()` 会把空 section 写回去，盖掉刚还原的 poi 文件。必须在第 3 步之后做。
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

- **热键在世界内任何地方都生效，包括 GUI 里**：`RewindClient` 监听 NeoForge 的 `InputEvent.Key`（原始按键），不用 `KeyMapping.consumeClick()`——原版只在 `screen == null` 时才给 KeyMapping 累计点击（`KeyboardHandler` 里 `flag4 = screen == null`），界面开着永远收不到。判定用 `KeyMapping.matches(key, scanCode)`，所以玩家在按键设置里改绑依然有效；F7/F8/F9 仍然注册成 KeyMapping，只是不再靠它的点击计数。按下的时机如果撞上过渡或上一次没结束的操作，就记进 `pendingRequest` 等空下来再执行（等价于原版点击计数「攒着」的语义，而不是丢掉按键）。
- **按下 F7/F8 会先把当前界面摘掉**（`CheckpointController.closeScreen`）：过渡是整帧后处理（重采样整幅画面），界面开着既会挡在效果上面，也违反上面那条「过渡期间不得出现界面」。摘屏用的是 `setScreen(null)`，容器界面走原版 `removed()` 的正常关闭路径，不会丢东西。
- **「没有存档点」不是失败路径**：F8 在没建过存档点时只写一条日志，不动玩家当前的界面（原来会走 `fail()` 把界面关掉）。

- **所有过渡参数都在客户端配置里**：`RewindClientConfig`（`run/config/rewind-client.toml`，`ModConfig.Type.CLIENT`）管着淡入时长、存档/读档各自的淡出时长、饱和度倍数、模糊半径、读档的 settle tick。默认值与改造前写死的常量一致。值只在配置加载/重载时抄进 static 字段（后处理每帧都读，不能每帧查表），配置没加载成功时保持默认值。**加新的过渡可调项就往这里加**，别在 `RewindTransition` / 渲染器里再写死常量；着色器里的浓度/半径是 uniform（`SaturationBoost` / `BlurRadius`），由渲染器每帧从配置灌进去。其中「存档过渡强度」（`saturationBoost`）与「读档过渡强度」（`blurRadius`）在界面上有滚动条（「快速存档」卡的「设置」里），可调范围就是这里的 `MIN_/MAX_SATURATION_BOOST` 与 `MIN_/MAX_BLUR_RADIUS`——**范围只在配置类里写一份**，界面照着读；界面拖动时走 `previewXxx`（只改 static 字段，当场见效、不落盘），松手走 `commitXxx`（写进 spec 并 `save()`）。
- **过渡包络**：`RewindTransition` 用真实时间推进 0 → 1 → 0（存档 = 饱和度提高，读档 = 高斯模糊）。`isFadeInDone()` 用来卡「等效果满强度之后再动手」，这样真正危险的动作玩家看不到。时长全部来自 `RewindClientConfig`；`HOLD_LIMIT_SECONDS`（25s）不是可调项，是「任何异常路径都不该让效果一直挂着」的兜底。另外读档「世界回来之后、开始淡出之前」那段等待不再是一个固定 tick 数：判据是**客户端已加载的区块数连续两 tick 不再增长**（`CheckpointController.REVEALING`，常量 `REVEAL_STABLE_TICKS`），配置里的 `restoreSettleTicks` 退化成**上限**。原来固定等 10 tick（0.5 秒），而这段时间玩家其实已经能看见世界、手里的东西也回来了——多糊的每一 tick 都是白等；现在 in-place 回滚那条路只等 3-4 tick（0.15-0.2 秒），读档完成后总共还糊 ≈ 0.2-0.25 秒。日志里有一行 `Rewind: reveal settled after N ticks (chunks=..., stable=..., cap=...)` 可以核对。

注意那个上限是从**进入 REVEALING 阶段**算起的（含世界还没回来的 tick）：关世界重开那条回退路径上，客户端拿到世界时 `phaseTicks` 往往已经超过上限，于是「世界一出现就收」——这和改动前一样（改动前那 10 tick 也是被这些空 tick 吃掉的），所以那条路的行为没变。
- **两种效果都是后处理**：`RewindTransitionRenderer` 在 `RenderFrameEvent.Post` 把画面重画一遍，`EffectMode` uniform 选效果（`1` = 饱和度，其它 = 高斯模糊），强度从 `RewindTransition.strength()` 来。三个必须记住的点：那一刻这一帧**还没** blit 到屏幕，所以要画进**主渲染目标**而不是帧缓冲 0；必须「读副本、写主目标」，所以每帧先把当前帧拷进自己的 `TextureTarget`——世界被拆掉之后，这张副本同时就是唯一还能显示的遮罩内容；强度为 0 时着色器要逐位还原原画面（饱和度那条靠 `mix(vec3(luma), rgb, 1.0 + 0.0)`）。着色器在 `assets/rewind/shaders/core/rewind_transition.{json,vsh,fsh}`，**JSON 里的 `vertex`/`fragment` 必须带命名空间**（`rewind:rewind_transition`，否则会去 `minecraft:` 找）。存档的浓度由 fsh 里的 `SATURATION_BOOST` 定（当前 1.5 = 满强度 2.5 倍饱和度）；它是线性的 `mix(luma, rgb, 1 + boost)`，再往上调低亮度通道会先被 clamp 掉、开始出现色块，那时该换成保亮度/保色相的写法而不是继续加常数。
- **存档不再动摄像机 FOV**：原来的广角是靠 `ViewportEvent.ComputeFov` 把 FOV 拉到 120°，已经整段删掉（连带 `RewindScreens.onComputeFov` / `FOV_MAX`）；现在改的是像素，玩家设置一概不动。
- **世界还在时不挂屏**（F7）：改成在服务器线程上 `await` 一个 latch 冻结世界（落盘完成后摁住，快照写完放行，服务端侧 20s 超时兜底），等价于原来的「暂停世界」但不产生任何界面。
- **世界不在时必须挂一个「逻辑屏」**（F8 重载期间）：`RewindBlankScreen` 不画任何东西，但必须存在——原版假设「没有世界就一定有界面」（`Minecraft.tick()` 里 `handleKeybinds` 直接访问 `player`），不挂会 NPE 崩客户端。世界回来后立刻摘掉。
- **拦掉原版加载屏**：`RewindScreens` 只在过渡进行期间取消 `GenericMessageScreen` / `LevelLoadingScreen` / `ProgressScreen` / `ReceivingLevelScreen` 的打开。过渡期间不碰 `Options.hideGui`（HUD/手持物保持原样，只是在「世界已拆、新世界未到」的那一小段里画面来自冻结帧，本身就没有活的 HUD 可画）——唯一例外是抓封面那一帧，见「时间树」一节。后处理不可用时（着色器加载失败）不再拦截，让原版加载屏兜底，避免黑屏空档。
- **验证**：`-PrwSelfTest=true` 会断言「过渡期间除逻辑屏外不得出现任何界面」，并在满强度存一张 `run/screenshots/rewind-<效果>.png`（`rewind-SATURATION.png` / `rewind-GAUSSIAN_BLUR.png`）——过渡是视觉效果，日志看不出对错，需要人眼（或视觉模型）确认。

### 时间树（存档槽位管理界面，neoforge-1.21.1）

F9 / `/rewind ui` 打开 `RewindTreeScreen`——「时间树」，一页管所有槽位：自动、快速两个特殊槽位 + 8 个手动槽位。手动槽位每张卡有读取 / 覆盖 / 重命名，**自动 / 快速这两个固定角色没有名字可改，第三个按钮是「设置」**；详情面板另有删除，还有搜索、排序、确认弹窗、设置弹窗、重命名弹窗。

页面有两套布局，标题旁边的开关切换：**档案布局**（下面讲的就是它）与**节点树布局**（时间线，见「时间线」一节）。

**界面上不放任何提示**：所有反馈（写完了 / 没存档点 / 局域网开着 / 名字不合法……）只写日志，文案走 `rewind.ui.log.*`——和模组别处一致（F7/F8 也是只写日志，不往聊天框发东西）。

- **排序默认按槽位序号**（`SortMode.INDEX`，模板里 `#sort` 的第一个选项也标了 `selected`），所以卡片默认就是固定的 `s1..s8` 顺序；「最新」那个角标只是标出哪个槽位最新，不会把顺序挪走。详情面板里的字段是「保存时间 / 游玩时长 / 生物群系 / 坐标 / 文件大小 / 存档大小」（「世界」和「状态」不再显示：设置弹窗里只放设置项，不重复槽位详情）。**「文件大小」是这个槽位自己的文件**（`SnapshotUsage.occupiedBytes`：槽位目录 + 清单 + 块映射 + 背包快照），**只有几十 KB**，因为 region / entities / poi 的内容已经搬进跨槽位共享的 `blocks/` 目录了；**「存档大小」是这份存档点内容的合计**（`SnapshotMeta.totalBytes`），才是整个世界的量级。两个数差得远不是 bug——「删掉这个槽位能腾出多少」又是第三个量（要减掉共享块的分摊），别拿「文件大小」当它。「保存时间」**只写绝对时间**（`2026-09-30 00:18`），不要再加上「（15 分钟前）」那种相对时间——相对时间是卡片上的写法（`.slot-line` / `.tree-meta`）。

- **「自动存档」是「跟着原版自动保存建点」的 Rewind 槽位**：原版每次自动保存完，就往 `auto` 槽位写一个存档点（见下面「自动存档点」一节）。所以那张卡不用手动覆盖也会自己长出内容。
- **设置弹窗**：自动 / 快速这两个固定角色槽位的第三个按钮。手动槽位不走这里，仍然是「重命名」。
  - **配置项一律用主题自带的控件**（`ore.css` 里 `.form-group` / `.form-label` / `.form-help` / `.form-input` / `.input-group` / `.slider` / `.button`），**不要在页面里另写一套控件样式**。弹窗内容由 Java 侧铺进 `#settingsInfo`，**里面只有设置项本身**——不要再往里塞「槽位详情」那几行（世界 / 保存时间 / 游玩时长 / 生物群系 / 大小 / 状态）：那些是详情面板的事，设置弹窗只负责改设置。
  - **自动存档**那张卡：状态 + **两个可调项**——「跟着原版自动保存建点」的开关（`data-act="toggle-auto"`）与「自动保存间隔（分钟）」（`#autoInterval` 输入框 + `data-act="apply-interval"`，两者包在主题的 `.input-group` 里，输入框自适应、按钮定宽）。两个都是改完立刻落盘、然后重开一次弹窗刷新。
  - **快速存档**那张卡：状态 + **两条过渡强度滑块**（`#saturationBoost` / `#blurRadius`）+ 一个「改键」按钮（`data-act="open-keys"`）。滑块就是**主题那个 `.slider`**（`div.slider` > `div.slider-process` + `span.slider-thumb`，宽度 / 位置按值写成行内百分比），当前值写在它下面的 `.form-help` 里。
    > **主题的滑块只是视觉**：它是 div，没有行为，拖动得自己驱动（`onSliderMouseDown/Move/Up`，按下 + 拖动按光标位置算比例，松手落盘）。**拖动期间只改内存里的值**（`previewXxx`，后处理每帧读的就是那两个 static 字段，所以当场能看到效果），**松手才落盘**（`commitXxx`）——拖一次会来一串事件，每个都写配置会连带触发配置文件监听重载、刷一屏日志。它改的是 `RewindClientConfig` 的饱和度倍数与模糊半径。
  - 「改键」打开的是 `RewindKeyBindsScreen`——原版按键绑定页，**列表被筛成只有 Rewind 那两个热键**（加上它们那条分类标题，一共 3 行）。筛的地方是 `cc.sighs.mixin.KeyBindsListMixins`（挂在 mixins.json 的 `client` 段）：`KeyBindsList` 的构造函数是照着 `Options.keyMappings` 这一整份数组铺列表的，所以那里 `@Redirect` 掉这次 `getfield`，只在自己那一页返回 `{snapshotKey, restoreKey}` 这个新数组——别的按键连条目都不会建，分类标题也只出现一次，`maxNameWidth` 同样只按这两条算。从原版「选项 → 按键」进去走的是同一个构造函数，但那时 `keyBindsScreen` 不是 `RewindKeyBindsScreen`，原样放行。
    > 为什么不是「铺完再删」：`addEntry` / `clearEntries` / `removeEntry` 都是 `AbstractSelectionList` 的 **protected** 方法，跨包调不到；`@WrapOperation` 又要求处理器参数类型与原方法**完全一致**，那个 `AbstractSelectionList$Entry` 同样是 protected、够不着（`@WrapOperation` 不接受 `Object` 这样的父类型，实测会报 invalid signature）。换数据源这条路绕开了这两件事。
    > **只按身份（`==`）挑那两个热键**：按分类挑会把 F9「打开时间树」也带进来。`RewindClient` 还没注册按键映射时返回 `options.keyMappings` 兜底。
  > 「重开弹窗」= 给 `#settingsInfo` 重设 `innerHTML`，里面所有节点都换成新的：**之前抓住的那个按钮引用当场作废**（点它没有任何反应）。自测里改完一项再点另一项时，必须重新 `querySelector`。滑块那三个鼠标监听也是每次现挂的（它们不是模板里的静态元素）。

- **页面完全由 Java 侧驱动**。模板是 `common/src/main/resources/assets/apricityui/apricity/screens/rewind_screen.html`（AUI 的基准目录就是 `assets/apricityui/apricity/`，别的模组放同路径也能被扫到，namespace 必须是 `apricityui`）。页面里的 `<script>` **已经删掉**：AUI 的页面脚本与 `global.js` 都要求 KubeJS 在场（`ScriptService` 里被 `KubeJSSupport.loaded()` 挡着），没有 KubeJS 时静默不执行——所以交互只能靠 Java 侧：`Document.addEventListener("click", ...)`（实际挂在 `body` 上，点击会冒泡上来）、`Element.closest("[data-act]")`、`getDataset()`、`classList.add("open")`、`setInnerHTML(...)`。模板里那些静态卡片只是「没接上数据时的样子」，每次渲染都被整体替换。
- **整页字体是微软雅黑**：原来的 `OreRegular` / `OreDisplay` 是远程 webfont，游戏里一直加载不到（AUI 日志里 `font family unavailable`），页面走的本来就是 fallback；现在把两个 `@font-face` 去掉、所有 `font-family` 改成 `"Microsoft YaHei","微软雅黑",...`。
- **别用 `Document.refresh()` 当「刷新样式」**：它是从模板文件整页重新解析，会把动态加的节点全丢掉。`setInnerHTML` 插入的节点会走完整的 HTML 解析管线（自定义标签 `<item>` 因此能解析成 `Item` 元素）并自己排进样式重算队列，不需要手动刷新。
- **尖括号要摘掉，不能用实体转义**：AUI 的 HTML 解析器不做实体解码（`&lt;` 会原样显示），所以动态文本（世界名、玩家起的槽位名、物品 SNBT）统一过 `RewindTreeScreen.text()` 去掉 `<` 与 `>`。
- **读取会退出界面，覆盖不会**。「读取」转交 `RewindApi.requestRollback(slot, "ui")`，也就是 F8 那条带过渡的管线——过渡是整帧后处理，界面开着会挡在效果上面，所以 `CheckpointController` 动手前会先 `setScreen(null)`，玩家看到的是「点一下 → 界面收起 → 世界糊住 → 换回来」。「覆盖」走 `RewindTreeScreen.startBackgroundSave`：在服务端线程上跑 `RewindApi.createCheckpoint(slot, "ui")`，**不放过渡、不动界面**，做完由 `tick()` 把结果收回来刷界面（只写日志 + 更新卡片上的时间/大小）。重命名和删除是就地做的（纯文件操作），做完直接重画。
  > 一致性保证不变：任务跑在服务端线程上，那段时间世界不 tick，等于把「拷贝期间没人写盘」这条要求换了个实现方式（F7 是关界面 + 过渡，这里是占住服务端线程）。
- **界面里覆盖那一版照样有封面，而且封面里没有界面**：抓帧那一帧 `CoverCapture` 会把当前界面（直接换 `Minecraft.screen` 字段，不走 `setScreen`，免得触发 `removed()/init()` 把 AUI 文档重建、把界面状态清掉）和 HUD 一起临时摘掉，抓完立刻还原——只影响这一帧，玩家看不见。所以「界面一直开着」和「封面里没有界面」不冲突。
- **封面文件比索引晚几十毫秒落地，所以界面要等它再重画一次**（`RewindTreeScreen.flushWaitingCover`，最多等 `COVER_WAIT_TICKS` = 20 tick）：覆盖完成那一刻索引已经好了、封面还没写完，卡片只能先退回纯色块；没有这一步，玩家得重开页面才看得到图。自测里有一条断言盯着这个（封面落地后卡片上必须出现 `img.cover-shot`）。
- **平时世界是暂停的，写盘期间放行**：`isPauseScreen()` 返回 `savingSlot == null`——管理界面开着时世界停住（不然站在危险的地方翻存档点会挨打），但后台写盘必须让服务端线程能跑，所以那几百毫秒放行。删除 / 改名仍然走客户端线程的文件操作（不占服务端线程），并且在写盘期间会被 `usable()` 挡掉：那会儿世界是活的，自动建点随时可能落盘，客户端线程再去改同一张索引就会打架。**别把它们改成 `server.execute(...)`**，没必要，也会让删除/改名变成异步的。
- **卡片封面是建点时抓的截图**：`CheckpointWriter.create` 往 `CoverRequest` 里留一条待办（中立的小盒子，服务端线程写、客户端渲染线程取，两边不用互相引用），客户端 `CoverCapture` 在下一帧的 `RenderFrameEvent` 上兑现——那会儿服务端正忙在落盘和拷贝上、世界不 tick，所以抓到的就是存档点那一刻的画面。抓帧那一帧把 `options.hideGui` 和当前界面都临时摘掉，封面里因此既没有 HUD / 手持物、也没有界面。抓帧注册在 `EventPriority.HIGHEST`，必须抢在过渡后处理之前读那一帧，否则封面会带上存档过渡的饱和度。
- **封面存在 `<gameDir>/apricity/rewind/covers/<存档目录名>/<槽位>-<毫秒>.png`**，页面用 `/rewind/covers/...`（AUI 的根相对路径，`/` 开头 = 相对 `apricity` 根）引用它。放这儿而不是存档目录里，是因为 AUI 只认资源包和 `<gameDir>/apricity/`，任意绝对路径解析不了。文件名带建点时刻（就是索引里的 `savedAtMillis`），是因为 **AUI 按路径缓存贴图、同一个路径换内容不会重读**——每建一次点就换一个名字，页面按索引里的时刻拼出当前那一个；写新的时把旧的一起删掉，删槽位时也删（封面不在存档目录里，`SnapshotStore` 带不上它，是界面上那条删除路径单独清的）。没有截图时退回纯色块（`COVER_COLORS`），封面框也不盖黑色渐变阴影（原来那个 `.cover-shade` 已经删掉）。
- **封面用 `<img class="cover-shot">`**（`object-fit:cover`，比封面框大的部分由封面框的 `overflow:hidden` 裁掉）。页面这一侧的接线是好的：自测会断言 `<img>` 在、`src` 指向当前那一版封面、`ImageDrawer.isTextureReady` 返回 true。（这个 bug 只在 AUI ≤ 1.2.5.1 上出现，1.2.5.2 起正常，四个 target 现在都用 1.2.6——见「曾经的问题：AUI 屏幕文档里画不出图片」。）
- **背包快照**：建点时 `WorldFlush` 把非空栏位抓成「栏位序号 → 原版 SNBT」（`ItemStack.saveOptional`），存到与槽位目录同级的 `<槽位>.inventory`（不参与镜像；`SnapshotInventory` 负责读写，一行一条、TAB 分隔）。详情面板把每格喂给 AUI 的 `<item>` 元素——它认 SNBT，数量也由它自己画，所以不要另外加数量角标。
- **背包快照默认折叠，只显示一行快捷栏**（9 格）。「背包快照」四个字左边是折叠/展开箭头（`▶` / `▼`，点整行切换，`data-act="toggle-inventory"`，状态是 `RewindTreeScreen.inventoryExpanded`，每次开界面都是折叠的）。展开后是「背包三行 + 快捷栏一行」——**快捷栏按原版习惯放在第四行**，中间用 `.inv-gap`（12px）把快捷栏和背包槽位分开；护甲与副手有东西才在快捷栏下面再起一行。
- **HUD 的「已游玩」**读的是集成服务端玩家的 `play_time` 统计（客户端的 `LocalPlayer` 身上没有这个统计）；界面开着时世界暂停，读一个 int 不会打架。
- **自测覆盖**：`-PrwSelfTest=true` 的第 1 轮会在存档点写完后打开时间树，断言「2 张特殊卡 + 8 张手动卡 + 4 个 HUD chip」、世界名出现在特殊卡上、空槽位的读取按钮是禁用的、背包快照默认折叠成一行快捷栏（9 格）且点标题能展开成完整背包（≥36 格）、箭头跟着翻、点卡片能切换选中、特殊卡上有「设置」而没有「重命名」且设置弹窗能开能关，并存一张 `run/screenshots/rewind-tree.png` 供人眼确认版面。同一轮接着点一下布局开关切到节点树布局，等布局算完再验时间线：`body.tree-mode` 挂上了、两套布局都在文档里、当前那套的标签高亮、节点数 = 有存档点的槽位数 **+ 1**（那个「在此存档」节点）、整棵树只有一个根且根就是快速存档、自动存档的 `parentSlot` 是 `quick`、`RewindApi.currentSlot` 是 `auto`（这一轮最后建的那个）且「在此存档」节点就挂在它的节点下面、默认方向是从上到下且转一下变成从左到右（按钮文字跟着换）、`#flowWorld` 上有 `translate(...) scale(...)`、点节点能把详情面板切过去，并存一张 `run/screenshots/rewind-timeline.png`。存完这张图之后**再点一下那个「在此存档」节点**：断言界面还在、没起过渡，然后等后台写盘落地，断言 `s1`（序号最小的空手动槽位）真的被写成了一个完整存档点、而且它成了时间线的头。最后一轮结束后还有一次 `VERIFY_TREE_MANAGE`：往探针槽位 `s1` 建点（断言它的 `parentSlot` 是 `quick`——回溯到快速存档之后 `head` 就在那儿）→ 在该卡上走一遍重命名（断言真的落进索引）→ 点删除确认 → 断言索引条目与槽位目录都没了、卡片回到空状态 → **最后从界面里覆盖一次**（槽位刚被删掉，所以这一下是新建）：确认后立刻断言「界面还在、没有过渡」，然后等后台写盘落地再断言槽位又完整了——这条守着「在 GUI 里覆盖不退出界面」这个要求。注意这两个阶段都必须等 `RewindTransition.isActive()` 变 false 才能开界面，否则会被「过渡期间不得出现任何界面」当场抓住。

  > **截图前要留足 settle tick**（`TREE_SCREEN_SETTLE_TICKS` = 50、`TREE_TIMELINE_SETTLE_TICKS` = 90）：AUI 从 1.2.5.2 起把整页文字的光栅化丢到工作线程，**首绘时还没光栅完的行是留白的**（日志里 `[AUI Font] blank text draw`），一帧只上传 16 条，整页两百来条文字要十几帧才铺满；贴图（封面）也是异步就绪的。DOM 断言不受影响（结构早就是对的），但截图是给人看的——太早截会得到一张**一个字的没有**的图，那种图看不出对错，会误导后面来改这个界面的人。

### 时间线（节点树布局，neoforge-1.21.1）

「时间树」页面有两套布局，由标题旁边的开关切换（`body.tree-mode`）：**档案布局**是槽位卡片，
**节点树布局**把同一批存档点按派生关系拼成一棵流程图，画在可拖拽平移 / 滚轮缩放的画布上
（`#flowCanvas` > `#flowWorld` > `#treeChart`），方向还能整体旋转 90°（`#treeView[data-dir]` =
`down` / `right` / `up` / `left`，连线全是 CSS 的 `::before` / `::after` 拼出来的，没有额外的图形层）。
两套布局共用同一个「选中槽位」，右侧详情面板始终跟着它；节点就是**有存档点的槽位**，空槽位不在时间线上。

数据侧只有一条边和一支「笔」：

- `SnapshotMeta.parentSlot`：这个存档点是站在哪个存档点上建出来的（槽位名；空 = 这条时间线的根）。
- `SnapshotIndex` 里的 `head`：世界**当前站在**哪个存档点上。建点时它就是新节点的父节点，建完点移到新节点；回溯成功后移到被回溯到的那个槽位（`RewindApi.moveTimelineHead`，「原地回滚」与 `restoreFiles` 两条路都走）。
- `CheckpointWriter.parentFor`：`head` 就是要挂的父节点；**覆盖 `head` 自己所在的槽位时，新节点顶替旧节点的位置**（沿用旧节点的 `parentSlot`）——否则时间线上会凭空多出一个自己指向自己的节点、或者把这条线的根弄丢。旧节点的父节点如果反过来挂在这个槽位下面（`isDescendant`，`TIMELINE_WALK_LIMIT` 兜底）就退回成根：断一条边好过成环。`head` 指向的槽位已经被删掉时同样退回成根。

**覆盖 / 删除会把边弄断，这是有意的**：槽位被覆盖之后里面已经是另一份世界状态，原先指向它的节点就找不到爹了——界面把这种节点画成根（`parentOf` 返回 null），而不是指着一个不存在的东西。删除槽位时如果 `head` 正好在它上面，`SnapshotStore` 会把 `head` 退回它的父节点（父节点也没了就退回「不知道」）。

界面侧（`RewindTreeScreen`）：

- **装树**：`fitFlow` 在「切到节点树布局 / 转了方向 / 点了重置视图 / 节点数变了」时把整棵树装进画布——装得下就居中，装不下就按最小 0.5 倍、贴着根节点那一端，剩下的靠拖拽。树的范围是把 `#treeChart .tree-card` 的盒子**并起来**算的（`#flowWorld` 会被拉满画布宽，量它只会得到画布宽度），再除以当前 `scale` 换算成没缩放的尺寸与偏移——`getBoundingClientRect()` 按 CSSOM 语义给的是**带 transform 的视觉盒**。视图刚显示时盒子可能还是 0（布局没算完），那就下一 tick 再量，最多 `FLOW_FIT_TRIES` 次。画布高度按视口算（`document.getViewportSize().height() - FLOW_CHROME_HEIGHT`，写进行内样式；CSS 里那个 `height:420px` 只是兜底）。**节点数没变就不重新装**，免得每次重画都把玩家的平移缩放抹掉。
- **平移与缩放**：`mousedown` / `mousemove` / `mouseup` / `wheel` 四个监听都挂在 `#flowCanvas` 上——按下之后 AUI 会把 `mousemove` / `mouseup` **重派发给「按下的那个元素」**，挂在画布上光标拖到画布外面也不会丢这两个事件。位移写进 `#flowWorld` 的行内 `transform: translate(...) scale(...)`，滚轮以光标为锚。拖过 `FLOW_DRAG_SLOP` 像素之后那一下 `click` 会被吃掉（`flowMoved`），否则拖完画布会顺手把卡片选中。右上角那颗旋转按钮不参与拖拽。
- **节点卡片**是 `.slot-card.tree-card`：角色 / 序号徽章 + 名字 + 一行摘要（多久之前 · 大小 · 群系）+ 游戏内时间，跟档案布局共用同一套交互（`data-act="select"` + `data-slot`）。横向方向下卡片收窄成三行（`order` 控制时间落在标题右边还是最后一行）。
- **数据坏了也要画得出来**：父链成环、父节点不存在、指向自己，都在 `renderTree` 里兜底（`parentOf` 判根 + `emitted` 集合防重复递归 + 没被画出来的节点自己当根），保证每个存档点恰好出现一次。
- **树上永远多一个「在此存档」节点**（内部标记 `NOW_NODE = "@now"`，以 `@` 开头所以永远撞不上真槽位）：它挂在时间线的**头**（`RewindApi.currentSlot`）下面、排在那一支的最后，所以一眼就能看出当前这一局是从哪个分叉岔出来的；头还没立起来（新世界、或头所在的槽位被删了）时它自己当根，一个存档点都没有时它就是树上唯一那个节点。卡片里显示当前世界的群系、坐标与游戏内时间。
  - **点它就直接存一个档**：写进**序号最小的空手动槽位**（`s1` 起，`firstFreeSlot`），走的是和「界面里覆盖」同一条后台写盘路径（`startBackgroundSave`——界面不退、不放过渡，做完由 `tick()` 收回来重画）。存完头就落在那上面，这个节点跟着挪过去，一眼能看出「刚存在哪儿」。
  - 8 个手动槽位全满时它变成一句 **「无空槽位」**（`rewind.ui.tree.no_slot`）并且不可点——卡片上没有 `data-act`，另外挂一个 `is-full` 类把光标改回箭头。
  - 它不是槽位，所以**不进详情面板**（点了不会选中它）。绿框 + 不响应悬停，和真正的存档点区分开；区分用**颜色**而不是虚线，因为 AUI 只画实线边框（`Style` 里根本没有 `border-style` 字段，`border:3px dashed …` 里的 `dashed` 会被直接丢掉）。

### 自动存档点（跟着原版自动保存建点，neoforge-1.21.1）

`AutoCheckpointMixins$MinecraftServerAutosave` 注入 `MinecraftServer.saveEverything(ZZZ)Z` 的返回处，**只认 `(true, false, false)` 这一组参数**——原版自动保存是 `tickServer` 里那一句 `saveEverything(true, false, false)`，整个原版只有它这么传参（`stopServer` 走 `saveAllChunks(false, true, false)`，`WorldFlush` 走 `saveEverything(true, true, true)`，所以不会互相触发）。

- 挑「`saveEverything` 的返回处」而不是 `tickServer` 里那一句的调用位置，是为了**能测**：自测直接调一次 `saveEverything(true, false, false)` 就走完整条路，不用等五分钟一次的真自动保存。代价是别的模组要是也用这三个参数调 `saveEverything`，也会跟着建一次点——那种调用本身就是「按自动保存的方式存一遍」，跟着建点不算错。
- `AutoCheckpoint.onVanillaAutosave` 已经在服务端线程上，会跳过四种情形：开关关着、正在回溯（`Rewind.isDiscarding()`，那会儿的世界状态马上要被丢掉）、局域网开放或专用服务器、世界里没有玩家。
- **自动存档和快速存档是两个独立槽位**，谁都不碰谁：自动建点只写 `SnapshotLayout.SLOT_AUTO`（`auto`），F7 / F8 与界面只写 `DEFAULT_SLOT`（`quick`）或玩家在界面上选的那个槽位。改这条链时注意别让任何一边落到另一个槽位名上——自测里有断言钉着它（自动建点前后快速槽位的 `savedAtMillis` 必须不变，且两个槽位的 `savedAtMillis` 不相同）。
- **代价是一次卡顿**：建点 = 强制落盘 + 增量拷贝，跑在服务端线程上，几十到几百毫秒，存档越大越明显。所以它可关：`RewindServerConfig`（COMMON 配置，`run/config/rewind-common.toml` 的 `autoCheckpoint.enabled`，默认开），界面上「自动存档」卡的「设置」里是同一个开关。它在模组构造阶段注册（不是客户端 setup），因为 COMMON 配置两边都要有。
- **改开关要落盘**：FML 4 的 `ModConfig` 上没有 `save()`，入口是 `modConfig.getLoadedConfig().save()`（两个可调项共用一个私有 `save()`）。
- **自动保存间隔（`autoCheckpoint.intervalMinutes`，默认 5 = 原版）直接改原版的自动保存间隔**：`AutoCheckpointMixins$MinecraftServerAutosave` 在 `MinecraftServer.computeNextAutosaveInterval` 的返回处按「配置值 / 5 分钟」**等比缩放**原版算出来的 tick 数。等比而不是直接返回固定值，是为了留下原版的**冲刺**行为——`tickrate` 被拉高时原版会算出一个更短的间隔来防止内存涨爆，缩放之后这个行为跟着一起保留（原版那句是 `max(100, (int)(tickrate * 300))`，那个 `300` 是**秒**，也就是 5 分钟）。
  > 同一个 mixin 还在 `tickServer` 的开头每 tick 把倒计时（`@Shadow ticksUntilAutosave`）压到配置值以内：世界刚开、或者间隔刚被改小的时候，倒计时还停在原版那个 5 分钟上，不压一下设置就不生效。**只压不抬**——原版冲刺时算出来的更短间隔要留着。代价是「把间隔改大」不会立刻拉长已经在跑的倒计时，那一次自动保存会来得比配置值早一点。
  > 间隔**只在「跟着原版自动保存建点」开着时才作用到原版上**（`RewindServerConfig.autoSaveIntervalTicks()` 关着时返回 0，两个注入都直接放行）——关着的时候改它没有意义，原版该多久存一次还是多久。
  > 自测里测这个间隔**只用比默认值大的数**（30 分钟、以及超出上限的 99）：改成比 5 分钟小会把倒计时压下来，后面那一轮就可能凭空插一次真自动保存，把回溯的耗时断言搅黄。「分钟 → tick」的换算按 `autoSaveIntervalMinutes() * TICKS_PER_MINUTE` 直接查，不靠真等。
- **自测**（第 1 轮 `VERIFY_AUTO`）：关掉开关 → 删掉 auto 槽位 → 跑一次 `saveEverything(true, false, false)` → 断言槽位仍然空；打开开关 → 再跑一次 → 断言槽位写出来了、`source=autosave`。随后 `VERIFY_TREE` 里还会在界面上点一次那个开关，断言配置真的翻了、再翻回来。

### 曾经的问题：AUI 屏幕文档里画不出图片（已修复）

> 四个 target 现在统一用 AUI **1.2.6**（≥ 1.2.5.2），下面这段只是历史记录。

**已经随 AUI 1.2.5.2 消失，这里留个记录。** 在 AUI 1.2.5.1 上，时间树卡片与详情面板的封面（`<img class="cover-shot" src="/rewind/covers/...">`）抓图、存盘、页面接线都是好的，但画面上看不到、只剩纯色块：`<img>` 在 DOM 里且 `src` 正确，`Style.backgroundImage` 有值、`ImageDrawer.isTextureReady(...)` 返回 true（贴图确实解码并上传了），给显式宽高、关深度测试、确认 `commitDraws()` 都无效，而同一个屏幕里的形状、渐变、文字、AUI 的 `<item>` 都画得出来。当时的结论是「AUI 的 flat document 里贴图 blit 会丢，`<img>` / `background-image` / `<sprite>` / `border-image` 都走这条路」。

换成 1.2.5.2 之后封面正常显示了（自测的 `rewind-tree.png` / `rewind-timeline.png` 上两张特殊卡与详情面板都是真实截图）。**当时那条结论还漏了一半原因**：AUI 的贴图与文字都是异步就绪的，页面刚打开的那几帧里两者都还没到——所以「看不到图」里也有一部分只是截图截早了，见「自测覆盖」那条关于 settle tick 的说明。

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
