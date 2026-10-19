---
title: In-Game UI
nav_order: 3
---

# In-Game UI

Retromod adds a mod button and a settings button to the title screen.

## Add Mods

The **Retromod** button opens your system file picker. Select one or more jars, then restart.

Selected files are copied to `retromod-input/`. On the next launch Retromod transforms them, moves the results to `mods/`, and keeps the originals in a backup folder.

Fabric always needs this restart because it scans `mods/` before Retromod can process incompatible metadata.

On Forge and NeoForge in-place mode, the picker copies a chosen old jar into `mods/` unchanged and translates it into `retromod-cache/translated/` right away, so one restart loads it. The Retromod screen also shows how many mods loaded from the cache this launch, how many load after a restart, and how many failed.

## Settings

The gear button opens settings backed by `config/retromod/config.json`. Changes save immediately. See the [config reference]({{ '/config' | relative_url }}) for each option.

On Forge and NeoForge the screen also shows **Write translated jars to mods/**, the `write_translated_jars` setting. Retromod reads it at startup, so after a change the screen says it takes effect on the next launch. Fabric and Quilt do not show it, because they always write translated jars into `mods/`.

The screen also shows whether the running jar matches an official build. Modified builds still run. The label is informational. See [Authenticity]({{ '/authenticity' | relative_url }}).

Use **Reset** to restore defaults.

## Missing Buttons

If neither button appears, Retromod probably did not finish loading. Check `logs/latest.log` and follow [Troubleshooting]({{ '/troubleshooting' | relative_url }}).
