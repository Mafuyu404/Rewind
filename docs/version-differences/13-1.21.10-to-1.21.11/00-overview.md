# Minecraft 1.21.10 -> 1.21.11

> Complete Primer source text, split into one top-level topic per file.

Original author: ChampionAsh5357. License: Creative Commons Attribution 4.0 International.
Full license: [`../licenses/LICENSE-CHAMPIONASH5357.txt`](../licenses/LICENSE-CHAMPIONASH5357.txt).

## Original Preamble

# Minecraft 1.21.10 -> 1.21.11 Mod Migration Primer

This is a high level, non-exhaustive overview on how to migrate your mod from 1.21.10 to 1.21.11. This does not look at any specific mod loader, just the changes to the vanilla classes. All provided names use the official mojang mappings.

This primer is licensed under the [Creative Commons Attribution 4.0 International](http://creativecommons.org/licenses/by/4.0/), so feel free to use it as a reference and leave a link so that other readers can consume the primer.

If there's any incorrect or missing information, please file an issue on this repository or ping @ChampionAsh5357 in the Neoforged Discord server.

Thank you to:

- @xfacthd for some educated guesses regarding the usage annotations
- @dinnerbone for pointing out gizmos can also be submitted on the server in singleplayer worlds
- @thatgravyboat for pointing out the change in parameter orders for `Mth#clampedLerp`
