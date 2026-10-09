# 更新日志

## 1.0.2

### 新增：进世界之前的「读取存档点」

- 世界选择界面第一排多了一颗「读取存档点」（英文 **Rewind**）：**进世界之前**，用这个存档自己的存档点把磁盘上的世界文件覆盖回去。世界坏到打不开、游戏里按不出 F8 时，这是唯一一条恢复路径。
- 目标存档点自动挑：优先**时间线的头**（世界当前站着的那个存档点），取不到时退回保存时间最新的那个完整存档点。
- 点下去先出确认界面（写明要还原到哪个存档点、以及「这会覆盖磁盘上的世界文件」），确认后在后台线程回拷，界面显示进行到哪一步与结果。没有可用存档点、或者存档被别的实例锁着时，按钮保持不可用。
- 第一排现在是**三颗等宽按钮**（进入选中的世界 / 读取存档点 / 创建新的世界），整排宽度与原版一致。
- 新增查询接口 `RewindApi.repairSlot(worldRoot)`，整合包作者可以自己接。

### 内部

- `.mca` 头部解析（`RegionHeader`）与存档数据回滚骨架（`SavedDataRollback`）从各 target 抽到 common——四个 target 逐字节相同，少一份重复。

## 1.0.1（自 1.0.0 起）

### 时间树

- F9 / `/rewind ui` 打开「时间树」，一页管理所有槽位（自动、快速 + 8 个手动槽）：卡片显示截图封面、背包快照、生物群系、坐标、游戏时间、游玩时长与占用大小。
- 两套布局：**档案布局**（槽位卡片，支持搜索 / 排序 / 重命名 / 删除 / 读取 / 覆盖）与**节点树布局**（把存档点按派生关系画成流程图，可拖拽平移、滚轮缩放、整体旋转 90°）。
- 节点树上有一个「在此存档」节点，挂在时间线的头下面；点它就直接存进序号最小的空手动槽位。
- 页面顶部还有 HUD：当前世界的群系、坐标、游戏内时间与已游玩时长。
- 存档点之间有了时间线关系：每个存档点记住自己是站在哪个存档点上建出来的，覆盖 / 删除槽位时会如实断掉这条边。

### 回溯

- **原地回溯**：F8 默认不再关世界——在活着的集成服务器里把世界倒回存档点（卸载受影响区块 → 回拷文件 → 重建 → 玩家 / 时间 / 天气 / 存档数据回滚），失败会自动退回「关世界 → 覆盖 → 重开」那条老路。
- 受影响区块按「真正改了多少区块」判定，与视距无关；实体存储没有脏标记，用「内存里还有会被保存的实体 / 快照里本来有实体」两条补判。
- 收尾会作废维度（26.1 还有服务器级）存档数据缓存里没被强引用的条目，让模组写在 `data/*.dat` 里的数据跟着回滚；袭击、记分板、等级附件这些被长生命周期对象强引用的会显式重建。
- 读档冷却与「死亡后自动回溯」两个可调项（都默认关闭；开启死亡回溯后，时间线的头会标红提醒）。
- 死亡回溯会先把重生点临时挪到玩家脚下、并把朝向接回来，避免「先瞬移回出生点、再被拉走」那一下。

### 存档点存储

- 快照按 **4 KiB 扇区**共享存储（内容寻址的块目录 + 每个槽位一份块映射）：换一个槽位建点只为真正变了的扇区付字节。同一份 30 MB 测试世界上，整文件级只能省 8.3%，按扇区切块省 **79.9%**。
- 回溯是**反向增量**：按建点时记录的清单只回拷真正被改写过的文件，回拷后把 mtime 拨回建点时的值。
- 槽位改名（只改显示名，不重拷）、删除、以及块存储的标记-清除垃圾回收。

### 观感

- F7 / F8 全程不出现任何界面、也不往聊天框发提示：过渡改成正片后处理（存档 = 高饱和、读档 = 高斯模糊），时长、饱和度、模糊半径、settle tick 全部进客户端配置，界面上还有滚动条可调。
- 死亡回溯有一套专门的红边过渡效果。
- 热键在世界内任何地方都生效，包括界面开着的时候。

### 自动存档

- 可以跟着原版自动保存建点（写 `auto` 槽位），也可以直接改原版的自动保存间隔（等比缩放，保留原版在冲刺时缩短间隔的行为）。

### 其他

- `/rewind status|ui|snapshot|restore` 命令；公开 `RewindApi`，供整合包与其它模组调用。
- 可选兼容：精妙核心（Sophisticated Core）——回溯后清掉它自己缓存的解码结果。
- 四个 target 功能对齐：1.20.1 Forge / 1.20.1 Fabric / 1.21.1 NeoForge / 26.1 NeoForge（26.1 的存档数据入口、维度路径与加载流程差异都已消化）。
- 界面文案中英双语；时间树整页字体改用本机字体（原来的远程 webfont 一直加载不到）。

---

## English

### 1.0.2

- New **Rewind** button on the world-selection screen: restore a world's files from one of its own checkpoints **before opening it** — the only recovery path when a world no longer loads. It picks the timeline head (falling back to the newest complete checkpoint), asks for confirmation before overwriting anything, then copies on a background thread and reports progress and result.
- The first button row is now three equal-width buttons (Play Selected World / Rewind / Create New World), the same total width as vanilla.
- New query API `RewindApi.repairSlot(worldRoot)`.

### 1.0.1 (since 1.0.0)

- **Time tree GUI** (F9 / `/rewind ui`): manage every slot on one page with screenshots, inventory snapshots, biome, position, in-game time, playtime and sizes; a slot view and a node-tree timeline view (drag, zoom, rotate); a "save here" node that writes into the first free manual slot; search, sort, rename, delete, restore and overwrite.
- **In-place rollback**: F8 rewinds the world inside the running integrated server (unload affected chunks, copy files back, rebuild, roll back player/time/weather/saved data), with automatic fallback to the close-world-then-reopen path.
- **4 KiB block-shared snapshots**: about 79.9% smaller than whole-file copies on the same 30 MB test world, plus reverse-incremental restores.
- **No-UI transitions**: post-process saturation (save) and gaussian blur (restore) with every parameter exposed in the client config; a dedicated red-edge effect for death-triggered rollback; hotkeys work everywhere, including in GUIs.
- **Autosave checkpoints** and an adjustable vanilla autosave interval; rollback cooldown; death-triggered rollback.
- `/rewind` commands and the public `RewindApi`; optional Sophisticated Core cache invalidation; the same feature set on Forge 1.20.1, Fabric 1.20.1, NeoForge 1.21.1 and NeoForge 26.1.
