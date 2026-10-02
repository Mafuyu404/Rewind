# 快照之后改动世界：方块 / 计分板 / 实体 / 玩家坐标与背包 / 世界时间都应该能被回溯抹掉。
setblock ~ ~2 ~ minecraft:diamond_block replace
scoreboard players set rewind_marker rewind_test 2
# 相对玩家自己传送：这样不管上一轮把玩家留在哪，位移都必然发生
execute as @a at @s run tp @s ~20 ~ ~20
give @a minecraft:diamond 5
tp @e[type=minecraft:armor_stand,tag=rewind_test] ~40 ~ ~40
# 建点之后才召唤的实体，落在另一个区块里：那个区块除了放它以外没有任何改动（没动方块、
# 没落过盘），专门用来验证「实体存储没有 per-chunk 脏标记」这条链也被覆盖到了。
summon minecraft:armor_stand ~-16 ~2 ~-16 {Tags:["rewind_test_extra"],NoGravity:1b,Invulnerable:1b}
# 把世界时间推远。**26.1 上这一行不再被 Java 侧断言**：dayTime 已经并进 level.dat 的 gameTime，
# 而 /time 现在改的是 world_clocks 那套 SavedData（不是 level.dat），任何数据包命令都挪不动 gameTime，
# 所以「推远再验回溯」这一对在 26.1 上不成立（见 RewindSelfTest 的类注释）。留着只是让世界确实往前走。
time add 10000
say REWIND_TEST mutated
