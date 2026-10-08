## Rewind

Have you ever lost a god-tier pickaxe to a misclick?  
Have you ever wanted to save right before a boss fight?  
Have you ever wanted to plan a build one step at a time?  
Have you ever felt that backing up and reloading a world is slow and wastes space?

This mod creates checkpoints for your current world, so you can rewind to any of them without reloading the save. It supports autosave checkpoints and quick save, and ships with a GUI for managing checkpoints that can switch between a slot view and a node-tree view.

### Checkpoint Slots

In game, press F9 by default to open the time-tree GUI; it shows the slot view by default.

The slot view manages checkpoints as a flat list, showing a screenshot, an inventory snapshot, the biome, the in-game time and other details for each one.

![Slot view](https://resource-api.xyeidc.com//client/members/pics/e42f40ee)

The time-tree GUI can also switch to the node-tree view, which supports rotation, zooming and panning.

The node-tree view makes the timeline relationship between checkpoints much clearer — just like a visual novel.

![Node-tree view](https://resource-api.xyeidc.com//client/members/pics/7a843a69)

Autosave and quick save are two special slots. The autosave one is straightforward, so it is not covered here.

### Quick Save

Quick save is a special slot of its own: press F7 by default to write a checkpoint to it, and F8 to restore it. It was inspired by the quick-save feature in the game *觅长生* (Mi Chang Sheng).

It is meant for emergencies, or for situations where you need to rewind often.

Quick save plays a high-saturation filter as a visual cue; it can be tuned in the config file or from the time-tree GUI.

Creating a checkpoint normally takes about a second, since the world has to be saved to disk first before the checkpoint can be made.

Quick restore plays a blur transition, which can also be tuned in the config file or from the time-tree GUI.

The first restore needs a warm-up and is a little slower; later restores usually finish within a second (excluding the visual transition), close enough to be seamless.

### Other Notes

- Restoring happens *inside* a loaded world, so a world that no longer opens puts it out of reach. The "Repair from checkpoint" button on the world list covers that case: it overwrites the selected world's files on disk with one of its checkpoints, without having to load the world first. Open the world afterwards.

- Thanks to a built-in "baby Git", checkpoints are typically only a few KB in size. Restoring does not restart the world or reload every chunk, so it completes in an instant — which is what makes the time tree possible.

- The config file lets you adjust the number of checkpoint slots and the rollback cooldown. Save-scumming enchantments, villagers or loot chests is bound to happen; this is just a way of balancing it along the time axis.

- Restoring is currently free of known bugs and rewinds chunks, entities and other data correctly. But as you can imagine, a lightweight, seamless restore design is bound to conflict with some mods, so please report any compatibility issues you run into.
