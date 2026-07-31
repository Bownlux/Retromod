---
name: add-version-shim
description: Add or extend a version shim for a verified Minecraft API change. Use for missing links in the shim chain and for class, method, field, or descriptor changes between releases.
argument-hint: "source-version target-version loader (e.g. 1.21.11 26.1 fabric)"
---

# Add a Version Shim

A version shim records a real API transition between two releases. Keep each redirect narrow enough that it cannot affect an unrelated owner or overload.

## Steps

1. **Identify the version gap.** Check `src/main/java/com/retromod/shim/` for existing shims. Find what source→target versions are missing. Use `ShimRegistry` BFS to verify no chain already exists.

2. **Verify each API change.** Compare mappings and class files from both versions. Record:
   - Renamed classes (e.g. `net/minecraft/class_1234` → `net/minecraft/world/entity/Entity`)
   - Renamed methods (e.g. `method_5678` → `getBlockState`)
   - Renamed fields (e.g. `field_9012` → `STONE`)
   - Moved classes (package relocations)
   - Changed method signatures (descriptor changes)

3. **Create the shim file** at `src/main/java/com/retromod/shim/<loader>/`:
   ```
   <Loader>_<source>_to_<target>.java
   ```
   Use underscores for dots in versions (e.g. `Fabric_1_21_10_to_26_1.java`).

4. **Implement `VersionShim` interface**:
   ```java
   public class <ClassName> implements VersionShim {
       @Override public String getShimName() { return "<Loader> <source> → <target>"; }
       @Override public String getSourceVersion() { return "<source>"; }
       @Override public String getTargetVersion() { return "<target>"; }
       @Override public String getModLoaderType() { return "<loader>"; } // "fabric", "neoforge", "forge", or "common"

       @Override
       public void registerRedirects(RetromodTransformer transformer) {
           // Class redirects
           transformer.registerClassRedirect("old/class/Name", "new/class/Name");
           // Method redirects
           transformer.registerMethodRedirect(
               "owner/Class", "oldMethod", "(Larg;)Lreturn;",
               "owner/Class", "newMethod", "(Larg;)Lreturn;"
           );
           // Field redirects
           transformer.registerFieldRedirect("owner/Class", "oldField", "owner/Class", "newField");
       }
   }
   ```

5. **Register in ServiceLoader.** Add the full class name to:
   ```
   src/main/resources/META-INF/services/com.retromod.core.VersionShim
   ```

6. **Add version aliases.** If the new version has sub-versions (e.g. 26.1.0, 26.1.1), add aliases in `ShimRegistry.java`:
   ```java
   VERSION_ALIASES.put("26.1.0", "26.1");
   ```

7. **Add tests.** Create focused tests under `src/test/java/com/retromod/` that verify:
   - Shim registers correctly
   - ShimRegistry BFS finds a chain through the new shim
   - Key redirects work (class, method, field)

## Key Files
- Shim implementations: `src/main/java/com/retromod/shim/<loader>/`
- ShimRegistry: `src/main/java/com/retromod/shim/ShimRegistry.java`
- ServiceLoader registration: `src/main/resources/META-INF/services/com.retromod.core.VersionShim`
- VersionShim interface: `src/main/java/com/retromod/core/VersionShim.java`
- Tests: `src/test/java/com/retromod/RetromodTest.java`

## Naming Conventions
- Fabric intermediary names: `class_XXXX`, `method_XXXX`, `field_XXXX`
- Mojang official names (26.1+): Human-readable (e.g. `Entity`, `getBlockState`)
- NeoForge already uses Mojang names since 1.17
- Forge uses SRG names (e.g. `func_XXXXX`, `field_XXXXX`) up to 1.20

## Keep in Mind
- For 26.1+ Fabric shims, let `IntermediaryToMojangMapper` handle intermediary names.
- NeoForge already uses Mojang names, so its shims usually cover API changes rather than namespace changes.
- Shims compose. A 1.16.5 mod can pass through every intermediate edge on its way to 26.1.
