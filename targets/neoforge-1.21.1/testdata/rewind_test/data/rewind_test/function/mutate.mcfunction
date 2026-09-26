# 快照之后改动世界：方块 / 计分板 / 实体 / 玩家坐标与背包都应该能被回溯抹掉。
setblock ~ ~2 ~ minecraft:diamond_block replace
scoreboard players set rewind_marker rewind_test 2
tp @a ~20 ~ ~20
give @a minecraft:diamond 5
tp @e[type=minecraft:armor_stand,tag=rewind_test] ~40 ~ ~40
say REWIND_TEST mutated
