---
name: mapping-work
description: Trace and update Minecraft names across intermediary, Mojang, SRG, and Yarn mappings. Use for missing mappings, generated mapping tables, and name-related linkage failures.
argument-hint: "task (e.g. add class mapping class_1234 Entity, generate mappings from tinyv2)"
---

# Mapping Work

Use this workflow to answer two questions: which symbol did the mod compile against, and which symbol exists on the host?

## Mapping Systems

| System | Used By | Format | Example |
|--------|---------|--------|---------|
| **Intermediary** | Fabric (pre-26.1) | `class_XXXX`, `method_XXXX`, `field_XXXX` | `class_1297` = Entity |
| **Mojang Official** | All loaders (26.1+), NeoForge (1.17+) | Human-readable | `Entity`, `getBlockState` |
| **SRG** | Forge (pre-1.20) | `func_XXXXX`, `field_XXXXX` | `func_70070_b` = getBlockState |
| **Yarn** | Fabric community (discontinued 26.1) | Human-readable | `Entity`, `getBlockState` |

## Key Files

- **IntermediaryToMojangMapper**: `src/main/java/com/retromod/mapping/IntermediaryToMojangMapper.java`
  - ~150 class mappings, ~63 method mappings, ~17 field mappings built-in
  - Loads additional mappings from bundled TinyV2 file
  - Supports external TinyV2 mapping files
  - `registerWithTransformer()` bulk-registers all mappings as redirects

- **MappingComposer**: `src/main/java/com/retromod/mapping/MappingComposer.java`
  - CLI tool to compose intermediary + Mojang mappings
  - Input: TinyV2 (obf→intermediary) + ProGuard (readable→obf)
  - Output: TinyV2 (intermediary→official)

- **Bundled mappings**: `src/main/resources/mappings/intermediary-to-mojang.tiny`

## Adding a New Class Mapping

In `IntermediaryToMojangMapper.java`, add to the `loadBuiltInMappings()` method:

```java
classMap.put("class_XXXX", "net/minecraft/path/ClassName");
```

## Adding a New Method Mapping

```java
// Key format: "ownerClass.methodName.descriptor"
methodMap.put("class_XXXX.method_YYYY.(Lnet/minecraft/class_ZZZZ;)V",
    new MethodMapping("class_XXXX", "newMethodName", "(Lnet/minecraft/path/ArgType;)V"));
```

## Adding a New Field Mapping

```java
fieldMap.put("class_XXXX.field_YYYY", "newFieldName");
```

## Generating Complete Mappings

Use MappingComposer to generate a full intermediary→official mapping file:

```bash
# Download source files
# 1. Fabric intermediary: https://github.com/FabricMC/intermediary/blob/master/mappings/<version>.tiny
# 2. Mojang official: https://piston-data.mojang.com/v1/packages/.../client.txt

# Compose them
java -cp retromod.jar com.retromod.mapping.MappingComposer \
    intermediary.tiny mojang-client.txt output.tiny
```

## Debugging Name Mismatches

1. Check if the class/method is in the built-in mappings
2. Check the bundled TinyV2 file (`intermediary-to-mojang.tiny`)
3. Use `isDeobfuscatedVersion()` to verify 26.1+ detection
4. Check `remapDescriptor()` for descriptor mismatches
5. Run `retromod analyze <mod.jar>` to see what names the mod uses

## Keep in Mind

- 26.1+ = Mojang official names (no obfuscation)
- NeoForge already used Mojang names since 1.17, so no name changes for NeoForge mods going to 26.1
- Fabric mods use intermediary names, which must map to Mojang names for 26.1+
- Forge mods use SRG names up to 1.20. The Forge 26.1 shim handles SRG→Mojang
- Method descriptors also need remapping (class references inside descriptors)
