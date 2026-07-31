---
name: test-mod-transform
description: Verify a mod transform from input jar through a stable game launch. Use for new shims, regressions, and compatibility reports.
argument-hint: "mod-jar-or-slug target-version (e.g. sodium 26.1, jei, path/to/mod.jar)"
---

# Test Mod Transform

Follow the transform all the way to the affected feature. A clean CLI run does not prove that the game can load the result.

## Quick Test Flow

1. **Get test mods.** Download from Modrinth API or use existing mods:
   ```bash
   # Search Modrinth
   curl -s 'https://api.modrinth.com/v2/search?query=<name>&facets=%5B%5B"categories:<loader>"%5D%5D'

   # Get versions
   curl -s 'https://api.modrinth.com/v2/project/<slug>/version?loaders=%5B"<loader>"%5D&limit=5'

   # Download
   curl -sL -o mod.jar "<download-url>"
   ```

2. **Analyze with CLI**:
   ```bash
   mvn -f pom.xml exec:java -Dexec.mainClass="com.retromod.cli.RetromodCli" \
     -Dexec.args="analyze '/path/to/mod.jar'" -q
   ```
   Check: Mod ID, Target MC, Mod Loader, Complexity Score

3. **Transform with CLI**:
   ```bash
   mvn -f pom.xml exec:java -Dexec.mainClass="com.retromod.cli.RetromodCli" \
     -Dexec.args="batch '/path/to/mods-folder' --aot" -q
   ```

4. **Verify metadata patching.** Extract and check the patched metadata:
   ```bash
   # For NeoForge/Forge
   jar xf transformed.jar META-INF/neoforge.mods.toml
   grep -A5 '"minecraft"' META-INF/neoforge.mods.toml

   # For Fabric
   jar xf transformed.jar fabric.mod.json
   cat fabric.mod.json | python3 -m json.tool
   ```

5. **Run it in game.** Deploy and launch:
   - Copy mod to `retromod-input/` in game directory
   - Launch with the target MC version + loader
   - Check `logs/latest.log` for Retromod transform messages
   - Check if the mod appears in the mod list
   - Wait past mod initialization and confirm no new crash report was created
   - Exercise the feature involved in the original report

## What to Verify

### Metadata Patching
- [ ] Minecraft version constraint accepts the target version
- [ ] Non-core dependencies are optional (won't crash if missing)
- [ ] Forge/NeoForge version range is permissive
- [ ] Fabric API constraint is `"*"`

### Bytecode Transform
- [ ] No `NoSuchMethodError` at runtime
- [ ] No `NoClassDefFoundError` at runtime
- [ ] No `AbstractMethodError`
- [ ] Mod features work (GUI, blocks, items, etc.)

### Mixin Compatibility
- [ ] No `MixinApplyError` about missing targets
- [ ] Mixin config JSON has correct entries

## Game Directories

- **macOS**: `~/Library/Application Support/minecraft/`
- **Linux**: `~/.minecraft/`
- **Windows**: `%APPDATA%/.minecraft/`

### Folder Structure
```
.minecraft/
├── mods/
│   ├── retromod-1.0.0-rc.1+26.1.jar    (Retromod itself)
│   ├── retromod-input/                      (Drop old mods here)
│   │   ├── old-mod-1.20.jar
│   │   └── processed/                       (Originals moved here after transform)
│   ├── retromod-backups/                    (Backups of in-place transforms)
│   └── retromod-output/                     (CLI batch output)
├── versions/
│   ├── fabric-loader-0.18.4-26.1-pre-2/
│   └── neoforge-26.1.0.0-alpha.6+snapshot-2/
├── logs/
│   └── latest.log                           (Check for [Retromod] messages)
└── config/
    └── retromod/
        ├── config.json
        └── aot-cache/
```

## Example Test Mods by Complexity

### Simple (should always work)
- MouseTweaks, AppleSkin, Dynamic FPS, Mod Menu (old versions)

### Medium (usually works)
- Jade/HWYLA, Lithium, NoChatReports, NotEnoughCrashes

### Complex (may need force_translate_complex)
- JEI, Sodium, Iris, VoiceChat, Create, Waystones

### Cross-version (tests shim chains)
- Sodium 1.16.5 (tests 1.16→...→26.1 chain)
- Lithium 1.18.2 (tests 1.18→...→26.1 chain)
- Iris 1.20.6 (tests 1.20→...→26.1 chain)

## Keep in Mind
- Keep the original mod JAR. Transforms are one-way
- Use `force_translate_complex: true` in config for complex mods
- The CLI `aot --force` flag overrides complexity warnings
- NeoForge mods already use Mojang names. They mainly need metadata patching, not bytecode transforms
- Fabric mods need intermediary→Mojang name remapping for 26.1+
