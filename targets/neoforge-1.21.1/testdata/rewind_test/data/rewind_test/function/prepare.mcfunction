# Rewind 自测装置：布置可被回溯观测的世界状态。
# 所有坐标相对命令源位置（集成服务器 /performPrefixedCommand 的命令源 = 主世界出生点），
# 模组侧的校验用同一个锚点：出生点 + (0, 2, 0)。
gamerule sendCommandFeedback true
scoreboard objectives add rewind_test dummy
scoreboard players set rewind_marker rewind_test 1
setblock ~ ~2 ~ minecraft:gold_block replace
kill @e[type=minecraft:armor_stand,tag=rewind_test]
summon minecraft:armor_stand ~2 ~2 ~ {Tags:["rewind_test"],CustomName:'"REWIND_TEST_ENTITY"',NoGravity:1b,Invulnerable:1b}
# 背包也要有确定的内容：自测会断言「背包快照」抓到了这些物品，回溯时它们同样要回来
clear @a
give @a minecraft:redstone 12
give @a minecraft:iron_ingot 55
say REWIND_TEST prepared
