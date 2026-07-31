---
name: debug-crash
description: Trace a transformed mod crash to the first useful failure and verify the fix end to end. Use after a transformed mod fails during discovery, startup, mixin application, or gameplay.
argument-hint: "no args needed - reads latest.log automatically"
---

# Debug a Crash

Start with the first relevant error, not the final cascade. Loader shutdown often adds secondary exceptions that obscure the original failure.

## Step 1: Read the Crash Log

```bash
# macOS
tail -200 ~/Library/Application\ Support/minecraft/logs/latest.log

# Also check crash reports
ls ~/Library/Application\ Support/minecraft/crash-reports/
cat ~/Library/Application\ Support/minecraft/crash-reports/crash-*.txt | tail -100
```

## Step 2: Identify the Error Type

### `NoSuchMethodError: <class>.<method>`
**Cause:** A method was renamed/removed and Retromod didn't have a redirect for it.
**Fix:** Add a method redirect to the appropriate version shim:
```java
transformer.registerMethodRedirect(
    "owner/Class", "oldMethod", "(descriptor)V",
    "owner/Class", "newMethod", "(descriptor)V"
);
```

### `NoClassDefFoundError: <class>`
**Cause:** A class was removed or relocated.
**Fix options:**
1. Add a class redirect in the version shim
2. Create a polyfill stub if the class was completely removed
3. Check if intermediary→Mojang mapping is missing for this class

### `ClassNotFoundException: <class>`
**Cause:** Usually the same as above, but reached through reflection or `Class.forName()`.
**Fix:** Check the class redirect and the reflection remapping in `RetromodTransformer`.

### `AbstractMethodError`
**Cause:** An interface gained a new abstract method that the mod doesn't implement.
**Fix:** The polyfill system may need a bridge class, or the method needs a default implementation.

### `IncompatibleClassChangeError`
**Cause:** A class changed to an interface (or vice versa).
**Fix:** Use `registerSuperclassRedirect()` in the polyfill provider.

### `Missing or unsupported mandatory dependencies`
**Cause:** Mod metadata has version constraints that reject the current MC version.
**Fix:** Check `ForgeModTransformer.updateMinecraftVersionRange()` or `FabricModTransformer.updateVersionRequirements()`.

### `MixinApplyError` / `InvalidMixinException`
**Cause:** Mixin target class or method no longer exists.
**Fix:** Check `MixinCompatibilityTransformer` and `MixinTargetRedirector`. The target redirect table may be missing an entry.

### `UnsupportedClassVersionError`
**Cause:** Mod was compiled for a newer Java version than what's running.
**Fix:** Retromod cannot lower another mod's bytecode version. Report the required Java version clearly. MC 26.1 requires Java 25.

## Step 3: Enable Debug Logging

Edit `config/retromod/config.json`:
```json
{
    "log_level": "DEBUG",
    "dump_bytecode": true,
    "log_transformations": true
}
```

This creates bytecode dumps in `config/retromod/bytecode-dump/` showing exactly what Retromod transformed.

## Step 4: Analyze the Mod

```bash
mvn -f pom.xml exec:java -Dexec.mainClass="com.retromod.cli.RetromodCli" \
  -Dexec.args="analyze '/path/to/mod.jar'" -q
```

Check:
- Complexity score (>100 = likely problems)
- API dependencies
- Risk factors (coremods, ASM manipulation, NMS access)

## Step 5: Check Shim Coverage

```bash
mvn -f pom.xml exec:java -Dexec.mainClass="com.retromod.cli.RetromodCli" \
  -Dexec.args="shims" -q
```

Verify the shim chain exists from the mod's source version to the target version.

## Step 6: Apply Fix

1. **Missing redirect** → Add to appropriate version shim
2. **Missing polyfill** → Create new polyfill provider (use `add-polyfill` skill)
3. **Missing mapping** → Add to IntermediaryToMojangMapper (use `mapping-work` skill)
4. **Metadata issue** → Fix in ForgeModTransformer or FabricModTransformer (use `mod-loader-compat` skill)
5. **Mixin issue** → Add target redirect to MixinTargetRedirector

## Key Debugging Files
- Crash log: `logs/latest.log`
- Crash reports: `crash-reports/crash-*.txt`
- Retromod crash log: `config/retromod/crash-log.txt`
- Bytecode dumps: `config/retromod/bytecode-dump/`
- AOT cache: `config/retromod/aot-cache/`
