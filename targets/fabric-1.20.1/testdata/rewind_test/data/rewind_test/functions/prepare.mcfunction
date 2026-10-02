# Rewind 自测装置：布置可被回溯观测的世界状态。
# 所有坐标相对命令源位置（集成服务器 /performPrefixedCommand 的命令源 = 主世界出生点），
# 模组侧的校验用同一个锚点：出生点 + (0, 2, 0)。
#
# 与 NeoForge 1.21.1 那份的差别只有打包结构：1.20.1 的函数目录是 functions/（复数），
# 1.21.1 起才改成 function/（单数）；pack.mcmeta 的 pack_format 是 15（1.21.1 是 48）。
# 函数体本身逐条一致，命令在 1.20.1 上都成立。
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
