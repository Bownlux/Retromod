---
title: In-Game UI
nav_order: 3
---

# In-Game UI

Retromod adds one **Retromod** button to the top-right corner of the title screen. It opens the Retromod screen, which holds the mod picker, the input folder, and the settings.

## Add Mods

**Retromod > Choose Mods…** opens your system file picker. Select one or more jars. Retromod checks each one right away and lists the results. The picker starts in the folder you last took mods from, which Retromod keeps in `retromod-cache/file-picker.properties`, not in `config/`, so the path stays out of modpack exports.

You can also drag jars from your file manager onto the Retromod screen. Retromod handles dropped jars the same way as picked ones. It takes only `.jar` files, and only on the Retromod screen, which does not open on Forge 1.20 to 1.20.4 or NeoForge 1.20.1.

- An outdated Fabric or Quilt mod is translated into `mods/`.
- An outdated Forge or NeoForge mod in in-place mode is copied into `mods/` unchanged and translated into `retromod-cache/translated/`.
- An outdated Forge or NeoForge mod is translated into `mods/` when **Write translated jars to mods/** is on, and always on NeoForge 1.20.2 to 1.20.4, which run no in-place pass.
- A mod that needs no update is copied into `mods/` unchanged.

Retromod tries every jar you pick. A translation that fails is listed as failed, with its reason.

The jar you picked stays where it was. Retromod does not move it or keep another copy. Restart Minecraft to load the new mods. The results screen offers to close the game.

**Retromod > Open retromod-input/** opens the folder Retromod reads at startup and shows its path. Minecraft does not report whether a folder window opened, so the path is always on screen. When no file picker can open, **Choose Mods…** opens that folder instead and shows its path. When Retromod cannot read a jar you picked or dropped, such as a file a launcher sandbox keeps from the game, it names the jar, updates nothing from that choice, and shows the same folder. Jars in it are handled on the next launch, as [Installation]({{ '/installation' | relative_url }}) describes.

In in-place mode the Retromod screen also shows how many mods loaded from the cache this launch, how many load after a restart, and how many failed.

## Settings

The gear button in the top-right corner of the Retromod screen opens **Retromod Settings**, backed by `config/retromod/config.json`. Each change saves immediately and writes only that setting. See the [config reference]({{ '/config' | relative_url }}) for each option.

On Forge and NeoForge the screen also shows **Write translated jars to mods/**, the `write_translated_jars` setting. Retromod reads it at startup, so after a change the screen says it takes effect on the next launch. Fabric and Quilt do not show it, because they always write translated jars into `mods/`. They show **Cache transformed mods**, the `use_aot` setting, instead. Forge and NeoForge do not read `use_aot`.

The screen also shows whether the running jar matches an official build. Modified builds still run. The label is informational. See [Authenticity]({{ '/authenticity' | relative_url }}).

## Missing Button

If the **Retromod** button does not appear, Retromod did not finish loading. On Fabric and Quilt it also needs Fabric API. Check `logs/latest.log` and follow [Troubleshooting]({{ '/troubleshooting' | relative_url }}).
