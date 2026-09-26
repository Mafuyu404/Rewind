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

### 过渡与「无界面」（neoforge-1.21.1）

F7/F8 全程不允许出现任何界面，也不往聊天框发任何提示（只写日志；`/rewind status` 是显式查询命令，保留输出）。观感由两种过渡承担：

- **过渡包络**：`RewindTransition` 用真实时间推进 0 → 1 → 0（存档 = 广角，读档 = 高斯模糊）。`isFadeInDone()` 用来卡「等画面拉宽 / 糊住之后再动手」，这样真正危险的动作玩家看不到。
- **存档的广角不做后处理**：直接在 `ViewportEvent.ComputeFov` 里把摄像机 FOV 拉满（`RewindScreens.FOV_MAX` = 120°，比原版滑块上限 110 更大，保证「拉到最大」一定看得出来；例如 70° → 120°），随强度淡入淡出。改的是每帧算出来的 FOV、不动玩家设置，所以效果结束自动恢复。
- **读档的高斯模糊是后处理**：`RewindTransitionRenderer` 在 `RenderFrameEvent.Post` 把画面重画一遍。两个必须记住的点：那一刻这一帧**还没** blit 到屏幕，所以要画进**主渲染目标**而不是帧缓冲 0；并且必须「读副本、写主目标」，所以每帧先把当前帧拷进自己的 `TextureTarget`——世界被拆掉之后，这张副本同时就是唯一还能显示的遮罩内容。着色器在 `assets/rewind/shaders/core/rewind_transition.{json,vsh,fsh}`，**JSON 里的 `vertex`/`fragment` 必须带命名空间**（`rewind:rewind_transition`，否则会去 `minecraft:` 找）。
- **世界还在时不挂屏**（F7）：改成在服务器线程上 `await` 一个 latch 冻结世界（落盘完成后摁住，快照写完放行，服务端侧 20s 超时兜底），等价于原来的「暂停世界」但不产生任何界面。
- **世界不在时必须挂一个「逻辑屏」**（F8 重载期间）：`RewindBlankScreen` 不画任何东西，但必须存在——原版假设「没有世界就一定有界面」（`Minecraft.tick()` 里 `handleKeybinds` 直接访问 `player`），不挂会 NPE 崩客户端。世界回来后立刻摘掉。
- **拦掉原版加载屏**：`RewindScreens` 只在过渡进行期间取消 `GenericMessageScreen` / `LevelLoadingScreen` / `ProgressScreen` / `ReceivingLevelScreen` 的打开。过渡期间不碰 `Options.hideGui`（HUD/手持物保持原样，只是在「世界已拆、新世界未到」的那一小段里画面来自冻结帧，本身就没有活的 HUD 可画）。后处理不可用时（着色器加载失败）不再拦截，让原版加载屏兜底，避免黑屏空档。
- **验证**：`-PrwSelfTest=true` 会断言「过渡期间除逻辑屏外不得出现任何界面」，并在满强度存一张 `run/screenshots/rewind-<效果>.png`——过渡是视觉效果，日志看不出对错，需要人眼（或视觉模型）确认；FOV 变化另有一条 `wide-angle fov A -> B` 日志。

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
