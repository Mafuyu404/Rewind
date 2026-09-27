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
# 把世界时间推远：回溯后 dayTime 只可能来自快照里的 level.dat，用它验证「快速重启」确实重读了 level.dat
time add 10000
say REWIND_TEST mutated
