/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.aot.AotCompiler;
import com.retromod.cli.RetromodCli;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.resources.MapResourceTree;
import com.retromod.resources.MigrationContext;
import com.retromod.resources.ModResourceMigrations;
import com.retromod.resources.SourceVersionResolver;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every transform path runs the same resource migration, outer jar and jar-in-jar alike: the
 * Fabric, Quilt, and Forge or NeoForge runtime loaders, the CLI, and the AOT compiler. Each fixture
 * goes through each path that accepts its loader, with a copy of itself nested inside, and the
 * {@code assets/} and {@code data/} of both the outer and the nested output must match the shared
 * converters run on the fixture directly. Removing the migration from any path fails here.
 */
class ResourceMigrationPathParityTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private Field cliTarget;
    private String savedCliTarget;
    private String savedTarget;

    @BeforeEach
    void saveTargets() throws Exception {
        cliTarget = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        cliTarget.setAccessible(true);
        savedCliTarget = (String) cliTarget.get(null);
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restoreTargets() throws Exception {
        cliTarget.set(null, savedCliTarget);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
    }

    @ParameterizedTest(name = "Fabric 1.20.1 on {0}")
    @ValueSource(strings = {"1.21.1", "1.21.11", "26.3"})
    @DisplayName("A Fabric mod and its nested 1.18.2 library come out the same from every Fabric path")
    void fabricPaths(String target, @TempDir Path directory) throws Exception {
        Map<String, byte[]> mod = gameData("oldfab");
        mod.put("fabric.mod.json", bytes(fabricMetadata("oldfab", "1.20.1")));
        // Every path relaxes the nested minecraft dependency, so the library's own 1.18.2 has to
        // be read from its original bytes for its structure to be converted.
        Map<String, byte[]> library = gameData("oldlib");
        library.put("fabric.mod.json", bytes(fabricMetadata("oldlib", "1.18.2")));
        library.put("data/oldlib/worldgen/configured_structure_feature/tower.json", bytes(TOWER));
        assertEveryPathAgrees(target, mod, library, "META-INF/jars/nested.jar",
                MigrationContext.Loader.FABRIC, directory, Runtime.FABRIC);
    }

    @ParameterizedTest(name = "Quilt 1.20.1 on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("A Quilt mod comes out the same from the Quilt runtime, the CLI and AOT")
    void quiltPaths(String target, @TempDir Path directory) throws Exception {
        Map<String, byte[]> mod = gameData("oldquilt");
        mod.put("quilt.mod.json", bytes("{\"schema_version\":1,\"quilt_loader\":{\"group\":\"fixture\","
                + "\"id\":\"oldquilt\",\"version\":\"1.0\",\"depends\":[{\"id\":\"minecraft\","
                + "\"versions\":\"~1.20.1\"}]}}"));
        assertEveryPathAgrees(target, mod, mod, "META-INF/jars/nested.jar",
                MigrationContext.Loader.QUILT, directory, Runtime.QUILT);
    }

    @ParameterizedTest(name = "Forge 1.18.2 on {0}")
    @ValueSource(strings = {"1.21.1", "1.21.11", "26.3"})
    @DisplayName("A Forge mod and its nested jar come out the same from every Forge-family path")
    void forgePaths(String target, @TempDir Path directory) throws Exception {
        Map<String, byte[]> mod = gameData("oldforge");
        mod.put("META-INF/mods.toml", bytes("modLoader=\"javafml\"\nloaderVersion=\"[40,)\"\n"
                + "license=\"MIT\"\n[[mods]]\nmodId=\"oldforge\"\nversion=\"1.0\"\n"
                + "[[dependencies.oldforge]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.18.2,1.19)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"));
        mod.put("data/oldforge/worldgen/configured_structure_feature/tower.json", bytes(TOWER));
        assertEveryPathAgrees(target, mod, mod, "META-INF/jarjar/nested.jar",
                MigrationContext.Loader.FORGE, directory, Runtime.FORGE);
    }

    @ParameterizedTest(name = "Forge 1.20.4 data on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("Data whose rewrite depends on the mod's own version comes out the same from every path")
    void sourceVersionedDataPaths(String target, @TempDir Path directory) throws Exception {
        // 1.20.3 renamed grass, so a mod built for 1.20.4 means something else by the old name and
        // keeps it. A path that lost the source version would rename it.
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", bytes("modLoader=\"javafml\"\nloaderVersion=\"[49,)\"\n"
                + "license=\"MIT\"\n[[mods]]\nmodId=\"newforge\"\nversion=\"1.0\"\n"
                + "[[dependencies.newforge]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.4,1.21)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"));
        mod.put("assets/newforge/lang/en_us.json", bytes("{}"));
        mod.put("data/newforge/tags/blocks/plants.json", bytes("{\"values\":[\"minecraft:grass\"]}"));
        mod.put("data/newforge/loot_tables/blocks/bush.json", bytes("{\"type\":\"minecraft:block\",\"pools\":"
                + "[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"newforge:berry\","
                + "\"functions\":[{\"function\":\"minecraft:set_nbt\",\"tag\":\"{Ripe:1b}\"},"
                + "{\"function\":\"minecraft:looting_enchant\",\"count\":1}]}]}]}"));
        Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                "META-INF/jarjar/nested.jar", MigrationContext.Loader.FORGE, directory, Runtime.FORGE,
                Map.of());

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            String tag = new String(output.getValue().get("data/newforge/tags/block/plants.json"),
                    StandardCharsets.UTF_8);
            assertTrue(tag.contains("minecraft:grass"), output.getKey() + " kept the 1.20.4 id: " + tag);
        }
    }

    @ParameterizedTest(name = "Forge 1.20.1 data on NeoForge {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("A Forge mod's modifiers, conditions and tags come out the same from every NeoForge path")
    void forgeDataOnNeoForgePaths(String target, @TempDir Path directory) throws Exception {
        com.retromod.util.McReflect.setForceNeoForge(true);
        try {
            Map<String, byte[]> mod = forgeNamespaceData("forgedata");
            Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                    "META-INF/jarjar/nested.jar", MigrationContext.Loader.NEOFORGE, directory,
                    Runtime.FORGE, Map.of());
            for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
                Map<String, byte[]> jar = output.getValue();
                assertTrue(jar.containsKey("data/forgedata/neoforge/biome_modifier/ores.json"),
                        output.getKey() + " moved the biome modifier to NeoForge's folder");
                assertTrue(jar.containsKey("data/c/tags/item/ingots/iron.json"),
                        output.getKey() + " added the mod's iron to c:ingots/iron");
                assertTrue(new String(jar.get("data/forgedata/recipe/compat.json"), StandardCharsets.UTF_8)
                        .contains("neoforge:conditions"), output.getKey() + " kept the recipe guarded");
            }
        } finally {
            com.retromod.util.McReflect.setForceNeoForge(false);
        }
    }

    @ParameterizedTest(name = "Forge 1.20.1 data on Forge {0}")
    @ValueSource(strings = {"1.21.11", "26.3"})
    @DisplayName("A Forge mod's conditions and tags come out the same from every Forge path")
    void forgeDataOnForgePaths(String target, @TempDir Path directory) throws Exception {
        Map<String, byte[]> mod = forgeNamespaceData("forgedata");
        Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                "META-INF/jarjar/nested.jar", MigrationContext.Loader.FORGE, directory,
                Runtime.FORGE, Map.of());
        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            Map<String, byte[]> jar = output.getValue();
            // The plural original, which every host keeps: on 26.3 the load gate keeps the 1.21 copy
            // out because it knows no Forge condition key.
            assertTrue(new String(jar.get("data/forgedata/recipes/compat.json"), StandardCharsets.UTF_8)
                    .contains("forge:condition\""), output.getKey() + " gave the recipe Forge's single condition");
            assertEquals(target.equals("26.3"), jar.containsKey("data/c/tags/item/ingots/iron.json"),
                    output.getKey() + " fills c:ingots/iron only where Forge dropped its forge: tags");
        }
    }

    @ParameterizedTest(name = "Forge 1.12.2 on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("A 1.12 mod with object-form mcmod.info is accepted and migrated on every path")
    void legacyForgePaths(String target, @TempDir Path directory) throws Exception {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("mcmod.info", bytes("{\"modListVersion\":2,\"modList\":[{\"modid\":\"abyssal\","
                + "\"version\":\"2.0\",\"mcversion\":\"1.12.2\"}]}"));
        mod.put("assets/abyssal/lang/en_us.json", bytes("{}"));
        mod.put("assets/abyssal/models/item/gem.json", bytes("{\"parent\":\"item/generated\"}"));
        mod.put("assets/abyssal/recipes/gem_block.json", bytes(
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"abyssal:gem\"],"
                        + "\"result\":{\"id\":\"abyssal:gem_block\"}}"));
        mod.put("assets/abyssal/loot_tables/chests/temple.json", bytes("{\"pools\":[]}"));
        mod.put("assets/abyssal/structures/temple.nbt", bytes("nbt"));
        assertEveryPathAgrees(target, mod, mod, "META-INF/jarjar/nested.jar",
                MigrationContext.Loader.FORGE, directory, Runtime.FORGE);
    }

    @ParameterizedTest(name = "Forge 1.12.2 recipe features on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("A 1.12 mod's ore recipes, constants and item data values come out the same from every path")
    void legacyForgeRecipeFeaturesOnEveryPath(String target, @TempDir Path directory) throws Exception {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("mcmod.info", bytes("{\"modListVersion\":2,\"modList\":[{\"modid\":\"oremod\","
                + "\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]}"));
        mod.put("assets/oremod/lang/en_us.json", bytes("{}"));
        mod.put("assets/oremod/models/item/plate.json", bytes("{\"parent\":\"item/generated\"}"));
        mod.put("assets/oremod/recipes/_constants.json", bytes("[{\"name\":\"STICK\",\"ingredient\":"
                + "{\"type\":\"forge:ore_dict\",\"ore\":\"stickWood\"}}]"));
        mod.put("assets/oremod/recipes/plate.json", bytes("{\"type\":\"forge:ore_shaped\","
                + "\"pattern\":[\"SW\"],\"key\":{\"S\":{\"item\":\"#STICK\"},"
                + "\"W\":{\"item\":\"minecraft:wool\",\"data\":14}},\"result\":{\"item\":\"plate\",\"count\":2}}"));
        mod.put("assets/oremod/loot_tables/chests/hut.json", bytes("{\"pools\":[{\"rolls\":1,\"entries\":"
                + "[{\"type\":\"item\",\"name\":\"minecraft:dye\",\"functions\":[{\"function\":\"set_data\","
                + "\"data\":4}]}]}]}"));

        Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                "META-INF/jarjar/nested.jar", MigrationContext.Loader.FORGE, directory, Runtime.FORGE,
                Map.of());

        String sticks = target.equals("26.3") ? "#c:rods/wooden" : "forge:rods/wooden";
        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            String recipe = new String(output.getValue().get("data/oremod/recipe/plate.json"),
                    StandardCharsets.UTF_8);
            assertTrue(recipe.contains(sticks) && recipe.contains("minecraft:red_wool")
                    && recipe.contains("oremod:plate"), output.getKey() + " translated the recipe: " + recipe);
        }
    }

    @ParameterizedTest(name = "Forge 1.12.2 without mcmod.info on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("A 1.12 mod with no mcmod.info is read from @Mod and migrated on every path")
    void legacyForgeWithoutMcmodInfo(String target, @TempDir Path directory) throws Exception {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("globalxp/GlobalXp.class", legacyModClass());
        mod.put("assets/globalxp/lang/en_us.json", bytes("{}"));
        mod.put("assets/globalxp/recipes/xp_block.json", bytes(
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:emerald\"],"
                        + "\"result\":{\"id\":\"globalxp:xp_block\"}}"));
        assertEveryPathAgrees(target, mod, mod, "META-INF/jarjar/nested.jar",
                MigrationContext.Loader.FORGE, directory, Runtime.FORGE);
    }

    @ParameterizedTest(name = "Forge 1.12.2 blockstates on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("1.12 and forge_marker blockstates convert the same on every path, outer and nested")
    void legacyBlockStatesOnEveryPath(String target, @TempDir Path directory) throws Exception {
        // Only a source read as 1.12 converts these, so a path that loses the source version
        // ships them unconverted and differs here.
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("mcmod.info", bytes("[{\"modid\":\"lifts\",\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]"));
        mod.put("assets/lifts/blockstates/lift.json", bytes("{\"forge_marker\":1,\"defaults\":{"
                + "\"model\":\"cube_all\",\"textures\":{\"all\":\"lifts:blocks/lift\"}},\"variants\":{"
                + "\"facing\":{\"north\":{},\"east\":{\"y\":90}},\"inventory\":[{}]}}"));
        mod.put("assets/lifts/blockstates/rail.json", bytes("{\"variants\":{"
                + "\"normal\":{\"model\":\"lifts:rail\"},\"inventory\":{\"model\":\"lifts:rail\"}}}"));
        mod.put("assets/lifts/models/block/rail.json", bytes("{\"parent\":\"block/cube_all\"}"));
        Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                "META-INF/jarjar/nested.jar", MigrationContext.Loader.FORGE, directory, Runtime.FORGE,
                Map.of());

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            assertTrue(output.getValue().containsKey("assets/lifts/models/block/retromod/lift/0.json"),
                    output.getKey() + " wrote the textured model the forge_marker defaults described");
            assertTrue(output.getValue().containsKey("assets/lifts/models/item/rail.json"),
                    output.getKey() + " turned the inventory variant into the item model");
        }
    }

    @ParameterizedTest(name = "Forge 1.12.2 language files and id case on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("A 1.12 mod's .lang files and mixed-case ids come out the same from every path")
    void legacyLanguageAndIdCase(String target, @TempDir Path directory) throws Exception {
        // The language step reads the mod's classes, which the runtime path keeps in its extracted
        // tree and the CLI and AOT paths pass beside it, so every path has to find the same ids.
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("mcmod.info", bytes("{\"modListVersion\":2,\"modList\":[{\"modid\":\"gems\","
                + "\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]}"));
        mod.put("gems/Gems.class", namesSapphire());
        mod.put("assets/gems/lang/en_US.lang", bytes("item.gems.sapphire.name=Sapphire\n"
                + "item.gems:ruby.name=Ruby\n"));
        mod.put("assets/gems/models/item/ruby.json", bytes("{\"parent\":\"item/generated\","
                + "\"textures\":{\"layer0\":\"gems:items/Ruby_Gem\"}}"));
        mod.put("assets/gems/recipes/ruby.json", bytes("{\"type\":\"minecraft:crafting_shapeless\","
                + "\"ingredients\":[{\"item\":\"minecraft:redstone\"}],\"result\":{\"item\":\"gems:Ruby\"}}"));
        cliTarget.set(null, target);
        RetromodVersion.TARGET_MC_VERSION = target;
        byte[] nested = jar(mod);
        Map<String, byte[]> outer = new LinkedHashMap<>(mod);
        outer.put("META-INF/jarjar/nested.jar", nested);
        Path input = Files.write(directory.resolve("parity-input.jar"), jar(outer));

        Map<String, Map<String, byte[]>> outputs = new LinkedHashMap<>();
        outputs.put("CLI", cli(input, directory));
        outputs.put("AOT", aot(input, directory, target));
        outputs.put("FORGE runtime", runtime(Runtime.FORGE, input, directory, target));

        Map<String, String> expected = digests(outputs.get("CLI"));
        Map<String, String> expectedNested = digests(read(outputs.get("CLI").get("META-INF/jarjar/nested.jar"),
                directory));
        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            assertEquals(expected, digests(output.getValue()), output.getKey() + " outer jar on " + target);
            Map<String, byte[]> nestedOutput = read(output.getValue().get("META-INF/jarjar/nested.jar"), directory);
            assertEquals(expectedNested, digests(nestedOutput), output.getKey() + " nested jar on " + target);
            for (Map<String, byte[]> jar : java.util.List.of(output.getValue(), nestedOutput)) {
                String lang = new String(jar.get("assets/gems/lang/en_us.json"), StandardCharsets.UTF_8);
                assertTrue(lang.contains("\"item.gems.sapphire\""), output.getKey() + " named the id "
                        + "only the class shows: " + lang);
                assertTrue(lang.contains("\"item.gems.ruby\""), lang);
                String model = new String(jar.get("assets/gems/models/item/ruby.json"), StandardCharsets.UTF_8);
                assertTrue(model.contains("gems:items/ruby_gem"), output.getKey() + ": " + model);
                assertTrue(jar.containsKey("assets/gems/lang/en_US.lang"), "the original stays");
            }
        }
    }

    /** {@code new Item().setRegistryName("sapphire")}, as a 1.12 mod registers an item. */
    private static byte[] namesSapphire() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, "gems/Gems", null,
                "java/lang/Object", null);
        var register = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC
                | org.objectweb.asm.Opcodes.ACC_STATIC, "register", "()V", null, null);
        register.visitCode();
        register.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "net/minecraft/item/Item");
        register.visitInsn(org.objectweb.asm.Opcodes.DUP);
        register.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "net/minecraft/item/Item", "<init>",
                "()V", false);
        register.visitLdcInsn("sapphire");
        register.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "net/minecraft/item/Item",
                "setRegistryName", "(Ljava/lang/String;)Lnet/minecraftforge/registries/IForgeRegistryEntry;",
                false);
        register.visitInsn(org.objectweb.asm.Opcodes.POP);
        register.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @ParameterizedTest(name = "Forge 1.18.2 BiomeLoadingEvent on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("A mod that handles the old BiomeLoadingEvent gets its biome modifier entry on every path")
    void biomeLoadingModifierOnEveryPath(String target, @TempDir Path directory) throws Exception {
        // What the 1.18.2 to 1.19 Forge shim registers on a Forge host: the stand-in event, the
        // redirect to it, and the biome modifier.
        com.retromod.shim.forge.LegacyForge119Bridge.register(transformer);
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", bytes("modLoader=\"javafml\"\nloaderVersion=\"[40,)\"\n"
                + "license=\"MIT\"\n[[mods]]\nmodId=\"biomemod\"\nversion=\"1.0\"\n"
                + "[[dependencies.biomemod]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.18.2,1.19)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"));
        mod.put("biomemod/BiomeMod.class", forgeModClass("biomemod/BiomeMod", "biomemod"));
        mod.put("biomemod/Spawns.class", biomeLoadingHandler("biomemod/Spawns"));
        mod.put("assets/biomemod/lang/en_us.json", bytes("{}"));
        Map<String, byte[]> modifier = Map.of(
                "data/biomemod/forge/biome_modifier/retromod_biome_loading.json",
                bytes("{\n  \"type\": \"biomemod:retromod_biome_loading\"\n}\n"));
        Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                "META-INF/jarjar/nested.jar", MigrationContext.Loader.FORGE, directory,
                Runtime.FORGE, modifier);

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            assertTrue(registersSerializer(output.getValue().get("biomemod/BiomeMod.class")),
                    output.getKey() + " registers the modifier from the @Mod constructor");
            Map<String, byte[]> nestedOutput = read(output.getValue().get("META-INF/jarjar/nested.jar"),
                    directory);
            assertTrue(registersSerializer(nestedOutput.get("biomemod/BiomeMod.class")),
                    output.getKey() + " registers it in the nested jar too");
        }
    }

    @ParameterizedTest(name = "{0} with an embedded helper on {1}")
    @CsvSource({"FORGE, 1.21.11", "FORGE, 26.3", "FABRIC, 1.21.11", "FABRIC, 26.3"})
    @DisplayName("Retromod's own embedded helpers never feed the converters that read a mod's classes")
    void embeddedHelpersAreNotReadAsTheMods(String loader, String target, @TempDir Path directory)
            throws Exception {
        // A helper that builds a key binding with a constant category, as Retromod's own
        // key mapping polyfill does. The runtime paths copy it into the mod when the mod calls it.
        transformer.registerSyntheticClass(STRAY_KEYS, strayKeyHelper());
        Map<String, byte[]> mod = gameData("keymod");
        boolean forge = loader.equals("FORGE");
        if (forge) {
            mod.put("META-INF/mods.toml", bytes("modLoader=\"javafml\"\nloaderVersion=\"[40,)\"\n"
                    + "license=\"MIT\"\n[[mods]]\nmodId=\"keymod\"\nversion=\"1.0\"\n"
                    + "[[dependencies.keymod]]\nmodId=\"minecraft\"\nmandatory=true\n"
                    + "versionRange=\"[1.18.2,1.19)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"));
        } else {
            mod.put("fabric.mod.json", bytes(fabricMetadata("keymod", "1.20.1")));
        }
        mod.put("keymod/Keys.class", callsStrayKeyHelper("keymod/Keys"));

        Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                forge ? "META-INF/jarjar/nested.jar" : "META-INF/jars/nested.jar",
                MigrationContext.Loader.valueOf(loader), directory,
                forge ? Runtime.FORGE : Runtime.FABRIC, Map.of());

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            assertFalse(output.getValue().containsKey("assets/retromod_compat/lang/en_us.json"),
                    output.getKey() + " named a key category only Retromod's helper uses");
        }
    }

    @ParameterizedTest(name = "Forge 1.12.2 models on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("A 1.12 mod's models get the same parents, textures, atlas sprites and element fields on every path")
    void legacyModelAssetsOnEveryPath(String target, @TempDir Path directory) throws Exception {
        // Every model converter keys on the source version, which each path reads for itself.
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("mcmod.info", bytes("[{\"modid\":\"oldmodels\",\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]"));
        mod.put("assets/oldmodels/models/block/ruby_slab.json", bytes("{\"parent\":\"block/half_slab\","
                + "\"textures\":{\"bottom\":\"oldmodels:blocks/ruby\",\"top\":\"blocks/stone\",\"side\":\"oldmodels:blocks/ruby\"}}"));
        mod.put("assets/oldmodels/models/block/lamp.json", bytes("{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
                + "\"shade\":false,\"faces\":{}}]}"));
        mod.put("assets/oldmodels/blockstates/jar.json", bytes("{\"variants\":{\"\":{\"model\":\"oldmodels:custom/jar\"}}}"));
        mod.put("assets/oldmodels/models/custom/jar.json", bytes("{\"parent\":\"builtin/entity\","
                + "\"textures\":{\"particle\":\"oldmodels:blocks/ruby\"}}"));
        mod.put("assets/oldmodels/models/item/gem.json", bytes("{\"parent\":\"item/generated\","
                + "\"textures\":{\"layer0\":\"oldmodels:items/gem\"}}"));
        mod.put("assets/oldmodels/textures/blocks/ruby.png", bytes("ruby"));
        mod.put("assets/oldmodels/textures/items/gem.png", bytes("gem"));
        Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                "META-INF/jarjar/nested.jar", MigrationContext.Loader.FORGE, directory, Runtime.FORGE, Map.of());

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            assertTrue(output.getValue().containsKey("assets/minecraft/atlases/blocks.json"),
                    output.getKey() + " lists the 1.12 block texture in the blocks atlas");
        }
    }

    @ParameterizedTest(name = "Forge 1.18.2 texture constants on {0}")
    @CsvSource({"1.21.1, textures/entity/bat.png", "26.3, textures/entity/bat/bat.png"})
    @DisplayName("A vanilla texture path in a mod's code follows the host's moves on every path, outer and nested")
    void vanillaTextureConstantsOnEveryPath(String target, String bat, @TempDir Path directory) throws Exception {
        Map<String, byte[]> mod = gameData("oldsky");
        mod.put("META-INF/mods.toml", bytes("modLoader=\"javafml\"\nloaderVersion=\"[40,)\"\n"
                + "license=\"MIT\"\n[[mods]]\nmodId=\"oldsky\"\nversion=\"1.0\"\n"
                + "[[dependencies.oldsky]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.18.2,1.19)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"));
        mod.put("oldsky/SkyTextures.class", vanillaTextureIds("oldsky/SkyTextures"));
        Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                "META-INF/jarjar/nested.jar", MigrationContext.Loader.FORGE, directory, Runtime.FORGE, Map.of());

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            Map<String, byte[]> nested = read(output.getValue().get("META-INF/jarjar/nested.jar"), directory);
            for (byte[] classBytes : List.of(output.getValue().get("oldsky/SkyTextures.class"),
                    nested.get("oldsky/SkyTextures.class"))) {
                List<String> strings = stringConstants(classBytes);
                assertTrue(strings.contains(bat), output.getKey() + " has " + bat + " in " + strings);
                assertTrue(strings.contains("textures/block/short_grass.png"),
                        output.getKey() + " follows the 1.20.3 grass rename in " + strings);
            }
        }
    }

    /** A class that builds two vanilla texture ids: the entity texture 26.1 moved, and 1.20.3's grass. */
    private static byte[] vanillaTextureIds(String name) {
        String id = "net/minecraft/resources/ResourceLocation";
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null,
                "java/lang/Object", null);
        var textures = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "textures", "()V", null, null);
        textures.visitCode();
        for (String path : List.of("textures/entity/bat.png", "textures/block/grass.png")) {
            textures.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, id);
            textures.visitInsn(org.objectweb.asm.Opcodes.DUP);
            textures.visitLdcInsn("minecraft");
            textures.visitLdcInsn(path);
            textures.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, id, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            textures.visitInsn(org.objectweb.asm.Opcodes.POP);
        }
        textures.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        textures.visitMaxs(0, 0);
        textures.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> stringConstants(byte[] classBytes) {
        assertNotNull(classBytes, "the class is in the output");
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);
        List<String> strings = new java.util.ArrayList<>();
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof String text) {
                    strings.add(text);
                }
            }
        }
        return strings;
    }

    @ParameterizedTest(name = "{0} item models on {1}")
    @CsvSource({"FORGE, 1.21.11", "FORGE, 26.3", "FABRIC, 26.3"})
    @DisplayName("Item definitions read egg colors from the mod's classes the same way on every path")
    void itemDefinitionsFromClassesOnEveryPath(String loader, String target, @TempDir Path directory)
            throws Exception {
        boolean forge = loader.equals("FORGE");
        Map<String, byte[]> mod = gameData("eggmod");
        if (forge) {
            mod.put("META-INF/mods.toml", bytes("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n"
                    + "license=\"MIT\"\n[[mods]]\nmodId=\"eggmod\"\nversion=\"1.0\"\n"
                    + "[[dependencies.eggmod]]\nmodId=\"minecraft\"\nmandatory=true\n"
                    + "versionRange=\"[1.20.1,1.21)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"));
        } else {
            mod.put("fabric.mod.json", bytes(fabricMetadata("eggmod", "1.20.1")));
        }
        mod.put("eggmod/ModItems.class", eggRegistry("eggmod/ModItems", forge));
        mod.put("assets/eggmod/models/item/ruby_golem_spawn_egg.json",
                bytes("{\"parent\":\"minecraft:item/template_spawn_egg\"}"));
        mod.put("assets/eggmod/models/item/ruby_bow.json", bytes("{\"parent\":\"item/generated\","
                + "\"overrides\":[{\"predicate\":{\"pulling\":1},\"model\":\"eggmod:item/ruby_bow_pulling\"}]}"));

        Map<String, Map<String, byte[]>> outputs = assertEveryPathAgrees(target, mod, mod,
                forge ? "META-INF/jarjar/nested.jar" : "META-INF/jars/nested.jar",
                MigrationContext.Loader.valueOf(loader), directory, forge ? Runtime.FORGE : Runtime.FABRIC,
                Map.of());

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            String egg = new String(output.getValue().get("assets/eggmod/items/ruby_golem_spawn_egg.json"),
                    StandardCharsets.UTF_8);
            assertTrue(egg.contains(String.valueOf(0xFF3B6B2D)), output.getKey() + " tints the egg: " + egg);
            String bow = new String(output.getValue().get("assets/eggmod/items/ruby_bow.json"),
                    StandardCharsets.UTF_8);
            assertTrue(bow.contains("minecraft:using_item"), output.getKey() + " translates the bow: " + bow);
        }
    }

    /**
     * {@code RUBY_GOLEM_SPAWN_EGG = new SpawnEggItem(type, 0x3B6B2D, 0x9C2B2B, properties)}, with
     * Forge's egg class or Fabric's intermediary one.
     */
    private static byte[] eggRegistry(String owner, boolean forge) {
        String egg = forge ? "net/minecraftforge/common/ForgeSpawnEggItem" : "net/minecraft/class_1826";
        String descriptor = forge
                ? "(Ljava/util/function/Supplier;IILnet/minecraft/world/item/Item$Properties;)V"
                : "(Lnet/minecraft/class_1299;IILnet/minecraft/class_1792$class_1793;)V";
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null,
                "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "RUBY_GOLEM_SPAWN_EGG", "Ljava/lang/Object;", null, null).visitEnd();
        var clinit = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, egg);
        clinit.visitInsn(org.objectweb.asm.Opcodes.DUP);
        clinit.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        clinit.visitLdcInsn(0x3B6B2D);
        clinit.visitLdcInsn(0x9C2B2B);
        clinit.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        clinit.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, egg, "<init>", descriptor, false);
        clinit.visitFieldInsn(org.objectweb.asm.Opcodes.PUTSTATIC, owner, "RUBY_GOLEM_SPAWN_EGG",
                "Ljava/lang/Object;");
        clinit.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final String STRAY_KEYS = "com/retromod/generated/test/StrayKeys";

    /** A helper whose only method builds {@code new KeyMapping("key.stray", 0, "Stray Category")}. */
    private static byte[] strayKeyHelper() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, STRAY_KEYS, null,
                "java/lang/Object", null);
        var make = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "make", "()Ljava/lang/Object;", null, null);
        make.visitCode();
        make.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "net/minecraft/client/KeyMapping");
        make.visitInsn(org.objectweb.asm.Opcodes.DUP);
        make.visitLdcInsn("key.stray");
        make.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
        make.visitLdcInsn("Stray Category");
        make.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "net/minecraft/client/KeyMapping",
                "<init>", "(Ljava/lang/String;ILjava/lang/String;)V", false);
        make.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        make.visitMaxs(0, 0);
        make.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A mod class that calls the helper, so the runtime paths embed it. */
    private static byte[] callsStrayKeyHelper(String name) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null,
                "java/lang/Object", null);
        var register = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC
                | org.objectweb.asm.Opcodes.ACC_STATIC, "register", "()Ljava/lang/Object;", null, null);
        register.visitCode();
        register.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, STRAY_KEYS, "make",
                "()Ljava/lang/Object;", false);
        register.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Whether a class's constructor calls the biome modifier's serializer registration. */
    private static boolean registersSerializer(byte[] classBytes) {
        assertNotNull(classBytes, "the @Mod class is in the output");
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.name.equals("registerSerializer")) {
                    return true;
                }
            }
        }
        return false;
    }

    private enum Runtime { FABRIC, QUILT, FORGE }

    private static final String OLD_BIOME_LOADING_EVENT = "net/minecraftforge/event/world/BiomeLoadingEvent";

    private static final String TOWER = "{\"type\":\"minecraft:village\",\"config\":"
            + "{\"start_pool\":\"oldmod:tower\",\"size\":3},\"biomes\":\"#minecraft:is_overworld\","
            + "\"adapt_noise\":true,\"spawn_overrides\":{}}";

    private static String fabricMetadata(String modId, String minecraft) {
        return "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"" + minecraft + "\"}}";
    }

    /** A 1.12 {@code @Mod(modid = "globalxp", acceptedMinecraftVersions = "[1.12.2]")} class. */
    private static byte[] legacyModClass() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "globalxp/GlobalXp", null, "java/lang/Object", null);
        var mod = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        mod.visit("modid", "globalxp");
        mod.visit("acceptedMinecraftVersions", "[1.12.2]");
        mod.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class carrying Forge's {@code @Mod(modId)}, with a plain constructor. */
    private static byte[] forgeModClass(String name, String modId) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null,
                "java/lang/Object", null);
        var mod = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        mod.visit("value", modId);
        mod.visitEnd();
        var init = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>",
                "()V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A 1.18 handler for the old event, as the mod shipped it. */
    private static byte[] biomeLoadingHandler(String name) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null,
                "java/lang/Object", null);
        var handler = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC
                        | org.objectweb.asm.Opcodes.ACC_STATIC, "onBiomeLoading",
                "(L" + OLD_BIOME_LOADING_EVENT + ";)V", null, null);
        handler.visitCode();
        handler.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        handler.visitMaxs(0, 1);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A Forge 1.20.1 mod's data under Forge's names: a biome modifier, the loot modifier list and
     * a modifier, a guarded recipe, and a convention tag it fills.
     */
    private static Map<String, byte[]> forgeNamespaceData(String namespace) {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", bytes("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n"
                + "license=\"MIT\"\n[[mods]]\nmodId=\"" + namespace + "\"\nversion=\"1.0\"\n"
                + "[[dependencies." + namespace + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.21)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"));
        mod.put("assets/" + namespace + "/lang/en_us.json", bytes("{}"));
        mod.put("data/" + namespace + "/forge/biome_modifier/ores.json", bytes("{\"type\":\"forge:add_features\","
                + "\"forge:conditions\":[{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"}],"
                + "\"biomes\":\"#forge:is_hot\",\"features\":\"" + namespace + ":ruby_ore\","
                + "\"step\":\"underground_ores\"}"));
        mod.put("data/forge/loot_modifiers/global_loot_modifiers.json",
                bytes("{\"replace\":false,\"entries\":[\"" + namespace + ":hat\"]}"));
        mod.put("data/" + namespace + "/loot_modifiers/hat.json", bytes("{\"type\":\"" + namespace
                + ":add_item\",\"conditions\":[{\"condition\":\"forge:loot_table_id\","
                + "\"loot_table_id\":\"minecraft:chests/simple_dungeon\"}],\"item\":\"" + namespace + ":hat\"}"));
        mod.put("data/" + namespace + "/recipes/compat.json", bytes("{\"type\":\"minecraft:crafting_shapeless\","
                + "\"conditions\":[{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"}],"
                + "\"ingredients\":[{\"tag\":\"forge:ingots/iron\"}],\"result\":{\"item\":\"" + namespace + ":gear\"}}"));
        mod.put("data/forge/tags/items/ingots/iron.json", bytes("{\"values\":[\"" + namespace + ":alloy\"]}"));
        return mod;
    }

    /** Resources most converters act on, in a mod's own namespace and in vanilla's. */
    private static Map<String, byte[]> gameData(String namespace) {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("assets/" + namespace + "/lang/en_us.json", bytes("{}"));
        mod.put("assets/" + namespace + "/models/item/ruby.json", bytes("{\"parent\":\"item/generated\"}"));
        mod.put("assets/minecraft/models/item/stick.json", bytes("{\"parent\":\"item/handheld\"}"));
        mod.put("data/" + namespace + "/recipes/ruby.json", bytes(
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:redstone\"}],"
                        + "\"result\":{\"item\":\"" + namespace + ":ruby\"}} // made by hand"));
        mod.put("data/" + namespace + "/loot_tables/blocks/ruby_ore.json", bytes(
                "{\"pools\":[{\"rolls\":1,\"entries\":[],\"conditions\":"
                        + "[{\"condition\":\"minecraft:alternative\",\"terms\":[]}]}]}"));
        mod.put("data/minecraft/tags/blocks/mineable/pickaxe.json",
                bytes("{\"values\":[\"" + namespace + ":ruby_ore\",]}"));
        mod.put("data/" + namespace + "/worldgen/configured_feature/ore.json",
                bytes("{\"type\":\"minecraft:ore\",\"config\":{}}"));
        return mod;
    }

    private void assertEveryPathAgrees(String target, Map<String, byte[]> mod,
                                       Map<String, byte[]> nestedMod, String nestedName,
                                       MigrationContext.Loader loader, Path directory,
                                       Runtime runtime) throws Exception {
        assertEveryPathAgrees(target, mod, nestedMod, nestedName, loader, directory, runtime,
                Map.of());
    }

    /**
     * @param generated data a class step writes before the resource migration, on every path,
     *        outer and nested alike
     * @return each path's output jar, by path name
     */
    private Map<String, Map<String, byte[]>> assertEveryPathAgrees(String target,
            Map<String, byte[]> mod, Map<String, byte[]> nestedMod, String nestedName,
            MigrationContext.Loader loader, Path directory, Runtime runtime,
            Map<String, byte[]> generated) throws Exception {
        cliTarget.set(null, target);
        RetromodVersion.TARGET_MC_VERSION = target;
        byte[] nested = jar(nestedMod);
        Map<String, byte[]> outer = new LinkedHashMap<>(mod);
        outer.put(nestedName, nested);
        byte[] outerJar = jar(outer);
        Path input = Files.write(directory.resolve("parity-input.jar"), outerJar);

        Map<String, byte[]> expectedMod = new LinkedHashMap<>(mod);
        expectedMod.putAll(generated);
        Map<String, byte[]> expectedNestedMod = new LinkedHashMap<>(nestedMod);
        expectedNestedMod.putAll(generated);
        Map<String, String> expected = expectedResources(expectedMod, outerJar, target, loader);
        Map<String, String> expectedNested = expectedResources(expectedNestedMod, nested, target, loader);
        assertNotEquals(digests(mod), expected, "the fixture has resources to migrate on " + target);
        assertNotEquals(digests(nestedMod), expectedNested, "so does the nested jar");

        Map<String, Map<String, byte[]>> outputs = new LinkedHashMap<>();
        outputs.put("CLI", cli(input, directory));
        outputs.put("AOT", aot(input, directory, target));
        outputs.put(runtime + " runtime", runtime(runtime, input, directory, target));

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            assertEquals(expected, digests(output.getValue()),
                    output.getKey() + " outer jar on " + target);
            byte[] nestedOutput = output.getValue().get(nestedName);
            assertNotNull(nestedOutput, output.getKey() + " kept the nested jar");
            assertEquals(expectedNested, digests(read(nestedOutput, directory)),
                    output.getKey() + " nested jar on " + target);
        }
        return outputs;
    }

    /**
     * The shared converters run on the fixture's resources with the context every path builds,
     * including its classes, which the streaming paths pass beside the resource tree.
     */
    private static Map<String, String> expectedResources(Map<String, byte[]> mod, byte[] jar,
            String target, MigrationContext.Loader loader) throws IOException {
        MapResourceTree tree = new MapResourceTree();
        for (Map.Entry<String, byte[]> entry : mod.entrySet()) {
            if (ModResourceMigrations.isMigratedResource(entry.getKey())) {
                tree.write(entry.getKey(), entry.getValue());
            }
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : mod.entrySet()) {
            if (entry.getKey().endsWith(".class")) classes.put(entry.getKey(), entry.getValue());
        }
        MigrationContext context = MigrationContext.builder(target)
                .source(SourceVersionResolver.inspect(jar)).targetLoader(loader)
                .classes(classes.keySet(), classes::get).build();
        ModResourceMigrations.standard().run(tree, context);
        return digests(tree.entries());
    }

    private Map<String, byte[]> cli(Path input, Path directory) throws Exception {
        Path output = directory.resolve("cli-output.jar");
        ModVersionInfo info = new ModVersionDetector().detectVersion(input);
        assertNotNull(info, "the CLI reads this jar's metadata");
        Method transform = RetromodCli.class.getDeclaredMethod("transformJar", Path.class,
                Path.class, RetromodTransformer.class, ModVersionInfo.class);
        transform.setAccessible(true);
        transform.invoke(null, input, output, transformer, info);
        return read(output);
    }

    private Map<String, byte[]> aot(Path input, Path directory, String target) throws Exception {
        Path output = directory.resolve("aot-output.jar");
        ModVersionInfo info = new ModVersionDetector().detectVersion(input);
        assertNotNull(info, "AOT reads this jar's metadata");
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), target);
        Method compile = AotCompiler.class.getDeclaredMethod("compileJar", Path.class, Path.class,
                ModVersionInfo.class);
        compile.setAccessible(true);
        compile.invoke(compiler, input, output, info);
        return read(output);
    }

    private static Map<String, byte[]> runtime(Runtime runtime, Path input, Path directory,
                                               String target) throws IOException {
        Path outputDirectory = Files.createDirectories(directory.resolve("runtime-output"));
        Path output = switch (runtime) {
            case FABRIC -> new FabricModTransformer(target).transformMod(input, outputDirectory);
            case QUILT -> new QuiltModTransformer(target).transformMod(input, outputDirectory);
            case FORGE -> new ForgeModTransformer(target).transformMod(input, outputDirectory);
        };
        assertNotNull(output, runtime + " runtime produced a jar");
        return read(output);
    }

    private static Map<String, String> digests(Map<String, byte[]> entries) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("assets/") || name.startsWith("data/")) {
                digests.put(name, sha256(entry.getValue()));
            }
        }
        return digests;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IOException(missing);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> read(byte[] jar, Path directory) throws IOException {
        return read(Files.write(Files.createTempFile(directory, "nested-", ".jar"), jar));
    }

    private static Map<String, byte[]> read(Path jar) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            var all = file.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                if (entry.isDirectory()) continue;
                try (var input = file.getInputStream(entry)) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return entries;
    }
}
