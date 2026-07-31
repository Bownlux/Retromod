---
title: In-game UI
nav_order: 3
---

# In-game UI

Retromod adds a mod button and a settings button to the title screen.

## Add Mods

The **Retromod** button opens your system file picker. Select one or more jars, then restart.

Selected files are copied to `retromod-input/`. On the next launch Retromod transforms them, moves the results to `mods/`, and keeps the originals in a backup folder.

Fabric always needs this restart because it scans `mods/` before Retromod can process incompatible metadata.

## Settings

The gear button opens settings backed by `config/retromod/config.json`. Changes save immediately. See the [config reference]({{ '/config' | relative_url }}) for each option.

The screen also shows whether the running jar matches an official build. Modified builds still run; the label is informational. See [Authenticity]({{ '/authenticity' | relative_url }}).

Use **Reset** to restore defaults.

## Missing Buttons

If neither button appears, Retromod probably did not finish loading. Check `logs/latest.log` and follow [Troubleshooting]({{ '/troubleshooting' | relative_url }}).
