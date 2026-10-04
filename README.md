# Rewind

基于 `common + targets/<loader>-<minecraft-version>` 结构的多加载器 Minecraft 模组。

包名与 Gradle group 为 `cc.sighs`，mod id 为 `rewind`，作者 MerrySnow。

项目骨架来自 [SighsTemple](https://github.com/Tower-of-Sighs/SighsTemple) 框架；该框架的迁移工作流为：先将当前 mc 版本的所有源码及资源迁移至 common 部分，再将经查询源码证实存在明显版本差异的内容转移到 targets 中的对应版本下，然后搭建 spi 模式，将 targets 中的内容抽象化，只保留具体实现差异、共通逻辑迁移回 common 部分，最后将当前 targets 下的主版本内容移植到全部所有版本。

## IDEA

直接打开任意 `targets/<loader>-<version>/` 目录。IDEA 会导入当前 target 与可编辑的 `../../common` 源码模块，只下载该 target 的加载器和 Minecraft 依赖。

## Target

| Target | JDK | 构建命令 |
| --- | --- | --- |
| `forge-1.20.1` | JDK 21 | `targets\forge-1.20.1\.\gradlew.bat clean build` |
| `fabric-1.20.1` | JDK 21 | `targets\fabric-1.20.1\.\gradlew.bat clean build` |
| `neoforge-1.21.1` | JDK 21 | `targets\neoforge-1.21.1\.\gradlew.bat clean build` |
| `neoforge-26.1` | JDK 25 | `targets\neoforge-26.1\.\gradlew.bat clean build` |

根项目默认只同步 `common`。使用 JDK 21 时可选择性构建前三个 target：

```powershell
.\gradlew.bat '-Ptarget=forge-1.20.1' build
.\gradlew.bat '-Ptarget=fabric-1.20.1' build
.\gradlew.bat '-Ptarget=neoforge-1.21.1' build
.\gradlew.bat -PallTargets=true build
```

`neoforge-26.1` 因 JDK 25 要求独立构建。

## 结构

- `common/`: 不依赖 Minecraft 或任意 loader 的共享 Java 代码。
- `targets/*`: loader 和版本专属入口、metadata、资源及 API 适配。

## 共享资源

将所有加载器和版本共用的资源放在 `common/src/main/resources/`。构建任意 target 时，该目录会与 target 自己的 `src/main/resources/` 合并并写入最终 jar。

加载器 metadata 仍必须保留在 target 中：Fabric 使用 `fabric.mod.json`，Forge 使用 `META-INF/mods.toml`，NeoForge 使用 `META-INF/neoforge.mods.toml`。

## 本地依赖

每个 target 都会自动将自身 `libs/` 目录中的 `*.jar` 作为 `implementation` 依赖。将 jar 放入对应目录后不需要在 `build.gradle` 中逐条声明；`*-sources.jar` 和 `*-javadoc.jar` 会被忽略。

```text
targets/forge-1.20.1/libs/
targets/fabric-1.20.1/libs/
targets/neoforge-1.21.1/libs/
targets/neoforge-26.1/libs/
```

本地 jar 的传递依赖无法自动推导。若某个 jar 还依赖其他库，需要将这些库也放入同一个 `libs/` 目录，或按常规方式声明依赖。

## 发布

每个 target 都提供 `publishMods`，可手动发布其自身的产物至 CurseForge 与 Modrinth。两个平台的项目 ID 是所有 target 共用的非敏感信息，在根 `gradle.properties` 中取消注释并填写：

```properties
publish_curseforge_project_id=你的CurseForge项目ID
publish_modrinth_project_id=你的Modrinth项目ID
```

token 只从环境变量读取，不要写入仓库。PowerShell 示例：

```powershell
$env:CURSEFORGE_TOKEN = '...'
$env:MODRINTH_TOKEN = '...'
$env:PUBLISH_CHANGELOG = '本次版本的更新说明' # 可选

cd targets\forge-1.20.1
.\gradlew.bat publishMods
```

将目录替换为其他 target 即可单独发布对应加载器和 Minecraft 版本。Fabric 会上传重映射后的 jar；Forge 与 NeoForge 上传各自的最终 jar。

## 版本参考

- [Minecraft 1.20.1、1.21.1、26.1 完整迁移差异参考](docs/version-differences/README.md)
- [多版本日常维护工作流](docs/MAINTENANCE_WORKFLOW.md)

## 许可证

Copyright (C) 2026 Tower of Sighs

本项目以 **GNU General Public License v3.0**（`GPL-3.0`）发布：你可以再分发和/或修改它，
但必须遵守该协议；本程序不提供任何担保。完整协议文本见根目录的 [LICENSE](LICENSE)。
