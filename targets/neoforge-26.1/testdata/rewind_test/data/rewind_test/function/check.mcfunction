# 手动观察当前世界状态（模组自测是在 Java 侧读状态的，这个函数是给人看的）。
execute if block ~ ~2 ~ minecraft:gold_block run say REWIND_TEST check: fixture = gold_block (pre-snapshot)
execute if block ~ ~2 ~ minecraft:diamond_block run say REWIND_TEST check: fixture = diamond_block (mutated)
execute if block ~ ~2 ~ minecraft:air run say REWIND_TEST check: fixture = air (unexpected)
execute if score rewind_marker rewind_test matches 1 run say REWIND_TEST check: score = 1 (pre-snapshot)
execute if score rewind_marker rewind_test matches 2 run say REWIND_TEST check: score = 2 (mutated)
execute if entity @e[type=minecraft:armor_stand,tag=rewind_test] run say REWIND_TEST check: armour stand present
execute if entity @e[type=minecraft:armor_stand,tag=rewind_test_extra] run say REWIND_TEST check: extra entity from after the checkpoint is still here (should be gone)
execute unless entity @e[type=minecraft:armor_stand,tag=rewind_test_extra] run say REWIND_TEST check: no extra entity (correct)
say REWIND_TEST check done
