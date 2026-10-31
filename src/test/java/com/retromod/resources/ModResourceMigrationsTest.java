/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every transform path runs one ordered list of converters over a mod. The runtime loaders hand it
 * an extracted directory and the CLI and AOT compilers an in-memory tree, so the same mod has to
 * come out identical from both.
 */
class ModResourceMigrationsTest {

    /** A mod with something for most converters: old layouts, item models, a nested pack. */
    private static Map<String, byte[]> legacyMod() {
        Map<String, byte[]> mod = new LinkedHashMap<>(entries(
                "assets/oldmod/lang/en_us.json", "{\"item.oldmod.ruby\":\"Ruby\"}",
                "assets/oldmod/models/item/ruby.json",
                "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"oldmod:item/ruby\"}}",
                "assets/minecraft/models/item/fishing_rod.json",
                "{\"parent\":\"item/handheld_rod\",\"overrides\":[{\"predicate\":{\"cast\":1},"
                        + "\"model\":\"item/fishing_rod_cast\"}]}",
                "assets/oldmod/recipes/ruby_block.json",
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"oldmod:ruby\"}],"
                        + "\"result\":{\"item\":\"oldmod:ruby_block\"}}",
                "data/oldmod/recipes/ruby.json",
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:redstone\"}],"
                        + "\"result\":{\"item\":\"oldmod:ruby\",\"count\":1}} // a comment",
                "data/oldmod/loot_tables/blocks/ruby_ore.json",
                "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"oldmod:ruby\"}],"
                        + "\"conditions\":[{\"condition\":\"minecraft:alternative\",\"terms\":[]}]}]}",
                "data/minecraft/tags/blocks/mineable/pickaxe.json", "{\"values\":[\"oldmod:ruby_ore\",]}",
                "data/oldmod/worldgen/configured_feature/ruby_ore.json", "{\"type\":\"minecraft:ore\"}",
                "data/oldmod/worldgen/configured_structure_feature/tower.json",
                "{\"type\":\"minecraft:village\",\"config\":{\"start_pool\":\"oldmod:tower\",\"size\":3},"
                        + "\"biomes\":\"#minecraft:is_overworld\",\"adapt_noise\":true,\"spawn_overrides\":{}}",
                "compat_pack/pack.mcmeta", "{\"pack\":{\"pack_format\":3,\"description\":\"compat\"}}",
                "compat_pack/assets/minecraft/textures/blocks/grass_top.png", "png",
                "compat_pack/assets/minecraft/models/block/grass_block.json",
                "{\"textures\":{\"top\":\"blocks/grass_top\"}}",
                "pack.mcmeta", "{\"pack\":{\"pack_format\":3,\"description\":\"mod\"}}"));
        return mod;
    }

    private static MigrationContext context(String source, String target,
                                            MigrationContext.Loader loader) {
        return MigrationContext.builder(target).sourceMcVersion(source).targetLoader(loader)
                .declaredNamespaces(List.of("oldmod")).build();
    }

    @ParameterizedTest(name = "{0} -> {1} on {2}")
    @CsvSource({
            "1.12.2, 1.21.1, NEOFORGE",
            "1.12.2, 26.3, FORGE",
            "1.18.2, 26.3, NEOFORGE",
            "1.20.1, 1.21.11, FABRIC",
            "1.20.1, 26.3, FABRIC",
            ", 26.3, QUILT"
    })
    @DisplayName("An extracted directory and an in-memory tree come out identical")
    void directoryAndMapTreesAgree(String source, String target, String loader,
                                   @TempDir Path directory) throws IOException {
        Map<String, byte[]> mod = legacyMod();
        MigrationContext context = context(source, target, MigrationContext.Loader.valueOf(loader));

        writeDirectory(directory, mod);
        int onDisk = ModResourceMigrations.migrateDirectory(directory, context);
        MapResourceTree inMemory = new MapResourceTree(mod);
        int inMap = ModResourceMigrations.standard().run(inMemory, context);

        assertTrue(onDisk > 0, "the fixture has something to migrate");
        assertEquals(onDisk, inMap, "both trees report the same number of changes");
        assertEquals(gameResourceDigests(readDirectory(directory)),
                gameResourceDigests(inMemory.entries()));
        assertEquals(readDirectory(directory).keySet(), new java.util.TreeSet<>(inMemory.entries().keySet()));
    }

    @Test
    @DisplayName("Converters run in a fixed order, the same on both trees, skipping what does not apply")
    void runsConvertersInOrder(@TempDir Path directory) throws IOException {
        for (ResourceTree tree : List.of(new MapResourceTree(legacyMod()), openDirectory(directory))) {
            List<String> calls = new ArrayList<>();
            ModResourceMigrations migrations = new ModResourceMigrations(List.of(
                    recording("first", true, calls), recording("skipped", false, calls),
                    recording("second", true, calls)));

            assertEquals(2, migrations.run(tree, context("1.20.1", "26.3",
                    MigrationContext.Loader.FABRIC)));
            assertEquals(List.of("first", "second"), calls);
        }
    }

    @Test
    @DisplayName("The standard list puts layout first, content before the worldgen moves, and the gate near the end")
    void standardOrder() {
        List<String> names = ModResourceMigrations.standard().converters().stream()
                .map(ModResourceConverter::name).toList();

        assertEquals(List.of("1.12 identifier case", "1.12 language files",
                "data layout", "Forge 1.12 recipes", "1.12 item ids",
                "pre-1.20 structure and smithing data", "1.12 and Forge v1 blockstates",
                "vanilla model parent renames", "builtin/entity block models",
                "vanilla texture references in mod models", "mod texture atlas sources",
                "26.x model element fields", "banner pattern tags", "armor equipment assets",
                "nested resource pack textures", "Forge data namespaces", "data content",
                "item definitions", "key category names", "renamed vanilla tags", "26.3 worldgen folders",
                "mod pack metadata", "data load gate", "dangling recipe advancements"), names);
    }

    @Test
    @DisplayName("Structures converted from 1.18.2 also get the 26.3 content rewrites")
    void legacyStructuresRunBeforeContent() throws IOException {
        MapResourceTree tree = new MapResourceTree(legacyMod());

        ModResourceMigrations.standard().run(tree, context("1.18.2", "26.3",
                MigrationContext.Loader.NEOFORGE));

        String structure = text(tree.read("data/oldmod/worldgen/structure/tower.json"));
        assertTrue(structure.contains("minecraft:jigsaw"), structure);
        assertTrue(tree.exists("data/oldmod/worldgen/feature/ruby_ore.json"),
                "the 26.3 folder moves see the final layout");
        assertFalse(tree.exists("data/oldmod/worldgen/configured_feature/ruby_ore.json"));
    }

    @Test
    @DisplayName("A recipe the gate keeps out of 26.3 takes the advancement that rewards it along")
    void danglingCheckSeesTheGate() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/oldmod/recipes/gear.json", "{\"type\":\"forge:ore_shaped\",\"pattern\":[\"#\"],"
                        + "\"key\":{\"#\":{\"ore\":\"ingotIron\"}},\"result\":{\"item\":\"oldmod:gear\"}}",
                "data/oldmod/advancement/recipes/gear.json",
                "{\"criteria\":{},\"rewards\":{\"recipes\":[\"oldmod:gear\"]}}"));

        ModResourceMigrations.standard().run(tree, context("1.20.1", "26.3",
                MigrationContext.Loader.NEOFORGE));

        assertFalse(tree.exists("data/oldmod/recipe/gear.json"), "the gate kept the Forge type out");
        assertTrue(tree.exists("data/oldmod/recipes/gear.json"), "the original stays");
        assertFalse(tree.exists("data/oldmod/advancement/recipes/gear.json"),
                "26.3 binds the reward to a recipe that is not there and stops the world");
    }

    @Test
    @DisplayName("A plural recipe advancement is shipped as it was, since 26.3 never reads it")
    void pluralRecipeAdvancementsStay() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/oldmod/recipes/gear.json", "{\"type\":\"forge:ore_shaped\",\"pattern\":[\"#\"],"
                        + "\"key\":{\"#\":{\"ore\":\"ingotIron\"}},\"result\":{\"item\":\"oldmod:gear\"}}",
                "data/oldmod/advancements/recipes/gear.json",
                "{\"criteria\":{},\"rewards\":{\"recipes\":[\"oldmod:gear\"]}}"));

        ModResourceMigrations.standard().run(tree, context("1.20.1", "26.3",
                MigrationContext.Loader.NEOFORGE));

        assertFalse(tree.exists("data/oldmod/recipe/gear.json"), "the gate kept the Forge type out");
        assertFalse(tree.exists("data/oldmod/advancement/recipes/gear.json"),
                "the copy goes, so nothing on 26.3 rewards the missing recipe");
        assertTrue(tree.exists("data/oldmod/advancements/recipes/gear.json"),
                "copy, never move: the inert original stays");
    }

    @Test
    @DisplayName("A pre-1.20.5 vanilla recipe copied for 26.3 takes the item stack result and loads")
    void copiedVanillaRecipesGetTheItemStackResult() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/oldmod/recipes/ruby_block.json", "{\"type\":\"minecraft:crafting_shaped\","
                        + "\"pattern\":[\"##\",\"##\"],\"key\":{\"#\":{\"item\":\"oldmod:ruby\"}},"
                        + "\"result\":{\"item\":\"oldmod:ruby_block\",\"count\":1}}",
                "data/oldmod/recipes/ruby_from_ore.json", "{\"type\":\"minecraft:smelting\","
                        + "\"ingredient\":{\"item\":\"oldmod:ruby_ore\"},\"result\":\"oldmod:ruby\","
                        + "\"experience\":0.7,\"cookingtime\":200}"));

        ModResourceMigrations.standard().run(tree, context("1.20.1", "26.3",
                MigrationContext.Loader.NEOFORGE));

        assertTrue(text(tree.read("data/oldmod/recipe/ruby_block.json"))
                .contains("\"id\":\"oldmod:ruby_block\""), "the gate kept the shaped copy");
        assertTrue(text(tree.read("data/oldmod/recipe/ruby_from_ore.json"))
                .contains("\"result\":{\"id\":\"oldmod:ruby\"}"), "the gate kept the smelting copy");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"FORGE", "NEOFORGE", "FABRIC"})
    @DisplayName("A global loot modifier is rewritten for the loader the context names")
    void lootModifiersFollowTheContextLoader(String loader) throws IOException {
        String name = "data/oldmod/loot_modifiers/fish.json";
        String modifier = "{\"type\":\"oldmod:add_item\",\"conditions\":[{\"condition\":"
                + "\"forge:loot_table_id\",\"loot_table_id\":\"minecraft:gameplay/fishing\"}]}";
        MapResourceTree tree = new MapResourceTree(entries(name, modifier));

        ModResourceMigrations.standard().run(tree, context("1.18.2", "26.3",
                MigrationContext.Loader.valueOf(loader)));

        String migrated = text(tree.read(name));
        switch (loader) {
            case "FORGE" -> assertTrue(migrated.contains("\"conditions\":[{\"type\":\"forge:loot_table_id\""),
                    "Forge's 26.3 codec still reads a conditions list: " + migrated);
            case "NEOFORGE" -> assertTrue(migrated.contains("\"condition\":{")
                    && !migrated.contains("\"conditions\""), "NeoForge reads one condition: " + migrated);
            default -> assertEquals(modifier, migrated, "no Fabric host reads a Forge loot modifier");
        }
    }

    @Test
    @DisplayName("A nested pack that also declares the host's format is left as shipped")
    void nestedPackSupportingTheHostIsLeftAlone() throws IOException {
        Map<String, byte[]> mod = entries(
                "resourcepacks/fancy/pack.mcmeta",
                "{\"pack\":{\"pack_format\":15,\"supported_formats\":[15,999],\"description\":\"x\"}}",
                "resourcepacks/fancy/assets/minecraft/textures/block/grass.png", "old",
                "resourcepacks/fancy/assets/minecraft/textures/block/short_grass.png", "new");
        for (String target : List.of("1.21.11", "26.3")) {
            MapResourceTree tree = new MapResourceTree(mod);

            ModResourceMigrations.standard().run(tree, context("1.20.1", target,
                    MigrationContext.Loader.FABRIC));

            assertEquals(mod.keySet(), tree.entries().keySet(), target);
            for (Map.Entry<String, byte[]> entry : mod.entrySet()) {
                assertArrayEquals(entry.getValue(), tree.read(entry.getKey()), target + " " + entry.getKey());
            }
        }
    }

    @Test
    @DisplayName("A nested pack whose texture moves collide stays as shipped and the mod still migrates")
    void collidingNestedPackDoesNotFailTheMod() throws IOException {
        Map<String, byte[]> mod = entries(
                "resourcepacks/fancy/pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"x\"}}",
                "resourcepacks/fancy/assets/minecraft/textures/block/grass.png", "old",
                "resourcepacks/fancy/assets/minecraft/textures/block/short_grass.png", "new",
                "data/oldmod/recipes/ruby.json", "{\"type\":\"minecraft:crafting_shapeless\","
                        + "\"ingredients\":[\"minecraft:redstone\"],\"result\":{\"id\":\"oldmod:ruby\"}}");
        MapResourceTree tree = new MapResourceTree(mod);

        ModResourceMigrations.standard().run(tree, context("1.20.1", "26.3",
                MigrationContext.Loader.FABRIC));

        assertArrayEquals(bytes("old"), tree.read("resourcepacks/fancy/assets/minecraft/textures/block/grass.png"));
        assertArrayEquals(bytes("new"),
                tree.read("resourcepacks/fancy/assets/minecraft/textures/block/short_grass.png"));
        assertTrue(tree.exists("data/oldmod/recipe/ruby.json"), "the rest of the mod still migrates");
    }

    @Test
    @DisplayName("A nested pack's own textures in the mod's namespace stay where the mod's models name them")
    void nestedPackKeepsModNamespacePaths() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/grass.json", "{\"textures\":{\"all\":\"oldmod:block/grass\"}}",
                "assets/oldmod/textures/block/grass.png", "mod",
                "resourcepacks/hd/pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"hd\"}}",
                "resourcepacks/hd/assets/oldmod/textures/block/grass.png", "hd mod",
                "resourcepacks/hd/assets/minecraft/textures/block/grass.png", "hd vanilla"));

        ModResourceMigrations.standard().run(tree, context("1.20.1", "26.3",
                MigrationContext.Loader.FABRIC));

        assertArrayEquals(bytes("hd mod"), tree.read("resourcepacks/hd/assets/oldmod/textures/block/grass.png"),
                "the override still matches the path the mod's own model names");
        assertFalse(tree.exists("resourcepacks/hd/assets/oldmod/textures/block/short_grass.png"));
        assertArrayEquals(bytes("hd vanilla"),
                tree.read("resourcepacks/hd/assets/minecraft/textures/block/short_grass.png"),
                "vanilla's renamed texture still moves");
    }

    @Test
    @DisplayName("A pre-1.13 nested pack repoints only its references to vanilla textures")
    void legacyNestedPackRepointsOnlyVanillaReferences() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/ruby_block.json", "{\"textures\":{\"all\":\"oldmod:blocks/ruby\"}}",
                "assets/oldmod/textures/blocks/ruby.png", "mod",
                "compat/pack.mcmeta", "{\"pack\":{\"pack_format\":3,\"description\":\"compat\"}}",
                "compat/assets/oldmod/textures/blocks/ruby.png", "compat mod",
                "compat/assets/oldmod/models/block/ruby_block.json",
                "{\"textures\":{\"all\":\"oldmod:blocks/ruby\",\"top\":\"blocks/grass_top\"}}",
                "compat/assets/minecraft/textures/blocks/grass_top.png", "compat vanilla"));

        ModResourceMigrations.standard().run(tree, context("1.12.2", "1.21.1",
                MigrationContext.Loader.NEOFORGE));

        assertArrayEquals(bytes("compat mod"), tree.read("compat/assets/oldmod/textures/blocks/ruby.png"),
                "the mod's own path is kept, as the mod's own model names it");
        assertTrue(tree.exists("compat/assets/minecraft/textures/block/grass_block_top.png"));
        String model = text(tree.read("compat/assets/oldmod/models/block/ruby_block.json"));
        assertTrue(model.contains("\"all\":\"oldmod:blocks/ruby\""), model);
        assertTrue(model.contains("\"top\":\"block/grass_block_top\""), model);
    }

    @Test
    @DisplayName("Item definitions are written for the mod's own items, never in the minecraft namespace")
    void itemDefinitionsNeverShadowVanilla() throws IOException {
        MapResourceTree tree = new MapResourceTree(legacyMod());

        ModResourceMigrations.standard().run(tree, context("1.20.1", "26.3",
                MigrationContext.Loader.NEOFORGE));

        assertTrue(tree.exists("assets/oldmod/items/ruby.json"));
        assertFalse(tree.exists("assets/minecraft/items/fishing_rod.json"),
                "a plain definition would replace vanilla's cast condition for every fishing rod");
        assertTrue(tree.exists("assets/minecraft/models/item/fishing_rod.json"),
                "the mod's model still applies through vanilla's own definition");
    }

    @Test
    @DisplayName("A resource pack's vanilla item model gets no definition of its own")
    void resourcePackKeepsVanillaDefinitions(@TempDir Path directory) throws IOException {
        Path pack = directory.resolve("bows");
        writeDirectory(pack, entries(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"bows\"}}",
                "assets/minecraft/models/item/bow.json", "{\"parent\":\"item/generated\"}",
                "assets/packmod/models/item/staff.json", "{\"parent\":\"item/generated\"}"));
        Path output = Files.createDirectories(directory.resolve("out"));

        Path result = new ResourcePackTransformer("26.3").transformPack(pack, output);

        Map<String, byte[]> files = readJar(result);
        assertFalse(files.containsKey("assets/minecraft/items/bow.json"),
                "a plain definition would remove the bow's pull animation");
        assertTrue(files.containsKey("assets/packmod/items/staff.json"));
    }

    @Test
    @DisplayName("A 1.20 data pack gets the 1.21 folder names, read from the format it declares")
    void dataPackGetsSingularFolders(@TempDir Path directory) throws IOException {
        Path pack = directory.resolve("towers");
        writeDirectory(pack, entries(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"towers\"}}",
                "data/towers/recipes/brick.json", "{\"type\":\"minecraft:crafting_shapeless\","
                        + "\"ingredients\":[\"minecraft:clay\"],\"result\":{\"id\":\"minecraft:brick\"}}",
                "data/towers/tags/blocks/walls.json", "{\"values\":[\"minecraft:stone\"]}"));
        Path output = Files.createDirectories(directory.resolve("out"));

        Path result = new DataPackTransformer("1.21.1").transformPack(pack, output);

        Map<String, byte[]> files = readJar(result);
        assertTrue(files.containsKey("data/towers/recipe/brick.json"));
        assertTrue(files.containsKey("data/towers/tags/block/walls.json"));
        assertTrue(files.containsKey("data/towers/recipes/brick.json"), "the original is kept");
    }


    @Test
    @DisplayName("A pack the mod ships inside its jar gets the pack texture steps; the mod's own assets do not")
    void nestedResourcePackGetsTextureSteps() throws IOException {
        MapResourceTree tree = new MapResourceTree(legacyMod());
        tree.write("assets/oldmod/textures/blocks/grass_top.png", bytes("own"));

        ModResourceMigrations.standard().run(tree, context("1.12.2", "1.21.1",
                MigrationContext.Loader.NEOFORGE));

        assertTrue(tree.exists("compat_pack/assets/minecraft/textures/block/grass_block_top.png"));
        assertFalse(tree.exists("compat_pack/assets/minecraft/textures/blocks/grass_top.png"));
        assertTrue(text(tree.read("compat_pack/assets/minecraft/models/block/grass_block.json"))
                .contains("block/grass_block_top"));
        assertTrue(tree.exists("assets/oldmod/textures/blocks/grass_top.png"),
                "vanilla renames never apply to a mod's own texture");
    }

    @Test
    @DisplayName("Banner pattern tags come from the transformed classes the path passes in")
    void bannerPatternTagsFromPassedClasses() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries("assets/oldmod/lang/en_us.json", "{}"));
        byte[] patterns = bannerPatternClass();
        MigrationContext context = MigrationContext.builder("1.20.1").sourceMcVersion("1.18.2")
                .classes(List.of("oldmod/Patterns.class"), name -> patterns).build();

        ModResourceMigrations.standard().run(tree, context);

        assertTrue(text(tree.read("data/oldmod/tags/banner_pattern/pattern_item/rune.json"))
                .contains("oldmod:rune"));
    }

    @Test
    @DisplayName("The streaming paths hold back game resources and keep loader files in their loop")
    void migratedResourceSelection() {
        for (String held : List.of("assets/oldmod/lang/en_us.json", "data/oldmod/recipes/a.json",
                "data/oldmod/recipes/mixing_bowl.json", "pack.mcmeta", "mcmod.info",
                "compat_pack/assets/minecraft/models/block/x.json")) {
            assertTrue(ModResourceMigrations.isMigratedResource(held), held);
        }
        for (String kept : List.of("oldmod/Main.class", "META-INF/MANIFEST.MF", "META-INF/mods.toml",
                "META-INF/jars/lib.jar", "fabric.mod.json", "quilt.mod.json", "oldmod.mixins.json",
                "mixins.oldmod.json", "oldmod-refmap.json", "oldmod.accesswidener", "../escape.json")) {
            assertFalse(ModResourceMigrations.isMigratedResource(kept), kept);
        }
    }

    private static DirectoryResourceTree openDirectory(Path directory) throws IOException {
        writeDirectory(directory, legacyMod());
        return DirectoryResourceTree.open(directory);
    }

    private static ModResourceConverter recording(String name, boolean applies, List<String> calls) {
        return new ModResourceConverter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean appliesTo(MigrationContext context) {
                return applies;
            }

            @Override
            public int apply(ResourceTree tree, MigrationContext context) {
                calls.add(name);
                return 1;
            }
        };
    }

    /** A class that creates the pattern {@code oldmod:rune} with an item, through the bridge. */
    private static byte[] bannerPatternClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "oldmod/Patterns", null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn("RUNE");
        init.visitLdcInsn("oldmod:rune");
        init.visitLdcInsn("orn");
        init.visitInsn(Opcodes.ICONST_1);
        init.visitMethodInsn(Opcodes.INVOKESTATIC, "com/retromod/generated/LegacyBannerPatterns",
                "create", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)"
                        + "Lnet/minecraft/world/level/block/entity/BannerPattern;", false);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(4, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
