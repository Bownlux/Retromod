/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for {@link ModDataMigrator}: the data-pack JSON rewrites that let a transformed mod's
 * loot tables, advancements and recipes parse on the host, each from the release that changed it.
 */
class ModDataMigratorTest {

    private static String mig(String name, String json, String target) {
        return new String(
                ModDataMigrator.migrate(name, json.getBytes(StandardCharsets.UTF_8), target),
                StandardCharsets.UTF_8);
    }

    @Test
    void chainRenamedTo26Name() {
        String in = "{\"pools\":[{\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:chain\"}]}]}";
        String out = mig("data/m/loot_table/chests/x.json", in, "26.1");
        assertTrue(out.contains("\"minecraft:iron_chain\""), "chain should become iron_chain");
        assertFalse(out.contains("\"minecraft:chain\""), "no bare minecraft:chain should remain");
    }

    @Test
    void chainRenameIsQuoteBounded() {
        // must not touch chain_command_block (a real 26.1 item) or a modid:chain
        String in = "{\"a\":\"minecraft:chain_command_block\",\"b\":\"dungeons_arise:chain\"}";
        assertEquals(in, mig("data/m/loot_table/x.json", in, "26.1"));
    }

    @Test
    void dyedColorObjectCollapsesToInt() {
        String in = "{\"components\":{\"minecraft:dyed_color\":{\"rgb\":32832}}}";
        String out = mig("data/m/loot_table/x.json", in, "26.1");
        assertTrue(out.contains("\"minecraft:dyed_color\":32832"), "dyed_color should be the bare int");
        assertFalse(out.contains("\"rgb\""), "the rgb wrapper object should be gone");
    }

    @Test
    void dyedColorHandlesExtraKeys() {
        String in = "{\"minecraft:dyed_color\":{\"rgb\":255,\"show_in_tooltip\":false}}";
        assertTrue(mig("data/m/loot_table/x.json", in, "26.1").contains("\"minecraft:dyed_color\":255"));
    }

    @Test
    void customModelDataIntBecomesObject() {
        String in = "{\"components\":{\"minecraft:custom_model_data\":2341669}}";
        String out = mig("data/m/loot_table/x.json", in, "26.1");
        assertTrue(out.contains("\"minecraft:custom_model_data\":{\"floats\":[2341669]}"),
                "legacy CMD int should become a floats object");
    }

    @Test
    void customModelDataObjectLeftAlone() {
        // already-migrated object form must not be double-wrapped
        String in = "{\"minecraft:custom_model_data\":{\"floats\":[5]}}";
        assertEquals(in, mig("data/m/loot_table/x.json", in, "26.1"));
    }

    @Test
    void advancementIconItemBecomesId() {
        String in = "{\"display\":{\"icon\":{\"item\":\"minecraft:barrel\"}}}";
        String out = mig("data/m/advancement/find_x.json", in, "26.1");
        assertTrue(out.contains("\"icon\":{\"id\":\"minecraft:barrel\"}"), "icon item should become id");
        assertFalse(out.contains("\"item\""), "no item key should remain in the icon");
    }

    @Test
    void iconRuleScopedToAdvancementsOnly() {
        // outside an advancement path the icon item->id rule must not fire
        String in = "{\"display\":{\"icon\":{\"item\":\"minecraft:barrel\"}}}";
        assertTrue(mig("data/m/loot_table/x.json", in, "26.1").contains("\"item\""));
    }

    @Test
    void gatedOffForPre26Targets() {
        // on 1.21.x, minecraft:chain still exists and dyed_color is still an object
        String in = "{\"name\":\"minecraft:chain\",\"minecraft:dyed_color\":{\"rgb\":1}}";
        assertEquals(in, mig("data/m/loot_table/x.json", in, "1.21.1"));
    }

    @Test
    void noOpOnUnrelatedData() {
        String in = "{\"type\":\"minecraft:crafting_shaped\",\"result\":{\"id\":\"minecraft:stick\"}}";
        assertEquals(in, mig("data/m/recipe/x.json", in, "26.1"));
    }

    @Test
    void noOpOnNonDataPaths() {
        String in = "{\"name\":\"minecraft:chain\"}";
        assertEquals(in, mig("assets/m/models/item/x.json", in, "26.1"), "only data paths are migrated");
    }

    // entity_type tag: minecraft:potion split (26.x ThrownPotionSplitFix)

    @Test
    void potionEntitySplitInEntityTypeTag() {
        String in = "{\"values\":[\"minecraft:player\",\"minecraft:experience_orb\","
                + "\"minecraft:potion\",\"minecraft:item_frame\"]}";
        String out = mig("data/dungeons_arise/tags/entity_type/ignores_ensnaring.json", in, "26.1");
        assertTrue(out.contains("\"minecraft:splash_potion\""), "should add splash_potion");
        assertTrue(out.contains("\"minecraft:lingering_potion\""), "should add lingering_potion");
        assertFalse(out.contains("\"minecraft:potion\""), "old single potion entity should be gone");
        // neighbours preserved, still valid JSON array
        assertTrue(out.contains("\"minecraft:player\"") && out.contains("\"minecraft:item_frame\""));
    }

    @Test
    void potionSplitHandlesSoleAndTrailingEntries() {
        // sole entry
        assertTrue(mig("data/m/tags/entity_type/x.json", "{\"values\":[\"minecraft:potion\"]}", "26.1")
                .contains("\"minecraft:splash_potion\", \"minecraft:lingering_potion\""));
        // last entry (no trailing comma) must not corrupt the closing bracket
        String out = mig("data/m/tags/entity_type/x.json",
                "{\"values\":[\"minecraft:arrow\",\"minecraft:potion\"]}", "26.1");
        assertTrue(out.endsWith("\"minecraft:lingering_potion\"]}"), out);
    }

    @Test
    void potionSplitScopedToEntityTypeTagsOnly() {
        // the potion ITEM is unchanged in 26.x, so a loot table referencing it must be left alone
        String loot = "{\"pools\":[{\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:potion\"}]}]}";
        assertEquals(loot, mig("data/m/loot_table/x.json", loot, "26.1"));
        // a non-entity_type tag (an item tag) is not subject to the entity split
        String itemTag = "{\"values\":[\"minecraft:potion\"]}";
        assertEquals(itemTag, mig("data/m/tags/item/x.json", itemTag, "26.1"));
    }

    @Test
    void potionSplitLeavesObjectIdFormUntouched() {
        // {"id":"minecraft:potion"} object form is not an array element, so it must not be corrupted
        String in = "{\"values\":[{\"id\":\"minecraft:potion\",\"required\":false}]}";
        assertEquals(in, mig("data/m/tags/entity_type/x.json", in, "26.1"));
    }

    @Test
    void chainRenameAlsoAppliesInItemTags() {
        // tags are migratable, so the chain rename applies to an item tag too
        String in = "{\"values\":[\"minecraft:chain\"]}";
        assertTrue(mig("data/m/tags/item/x.json", in, "26.1").contains("\"minecraft:iron_chain\""));
    }

    @Test
    void tagsAreRecognizedAsMigratableData() {
        assertTrue(ModDataMigrator.isMigratableData("data/m/tags/entity_type/x.json"));
        assertTrue(ModDataMigrator.isMigratableData("data/m/tags/item/x.json"));
        assertFalse(ModDataMigrator.isMigratableData("data/m/tags/entity_type/x.nbt"));
    }

    // migrateTree: the extract/repack helper used by the runtime transformers

    @Test
    void migrateTreeRewritesOnlyAffectedFiles(@TempDir Path root) throws Exception {
        Path loot = root.resolve("data/m/loot_table/c.json");
        Path tag = root.resolve("data/m/tags/entity_type/e.json");
        Path clean = root.resolve("data/m/loot_table/ok.json");
        Path nonData = root.resolve("assets/m/models/x.json");
        for (Path p : new Path[]{loot, tag, clean, nonData}) Files.createDirectories(p.getParent());
        Files.writeString(loot, "{\"name\":\"minecraft:chain\"}");
        Files.writeString(tag, "{\"values\":[\"minecraft:potion\"]}");
        Files.writeString(clean, "{\"name\":\"minecraft:stick\"}");
        Files.writeString(nonData, "{\"name\":\"minecraft:chain\"}");

        int changed = ModDataMigrator.migrateTree(root, "26.1");

        assertEquals(2, changed, "only the chain loot table and the potion tag should change");
        assertTrue(Files.readString(loot).contains("iron_chain"));
        assertTrue(Files.readString(tag).contains("splash_potion"));
        assertEquals("{\"name\":\"minecraft:stick\"}", Files.readString(clean));
        assertEquals("{\"name\":\"minecraft:chain\"}", Files.readString(nonData), "non-data path untouched");
    }

    @Test
    void migrateTreeGatedOffForPre26Targets(@TempDir Path root) throws Exception {
        Path loot = root.resolve("data/m/loot_table/c.json");
        Files.createDirectories(loot.getParent());
        Files.writeString(loot, "{\"name\":\"minecraft:chain\"}");
        assertEquals(0, ModDataMigrator.migrateTree(root, "1.21.1"));
        assertEquals("{\"name\":\"minecraft:chain\"}", Files.readString(loot));
    }

    @Test
    void migrateTreeHandlesMissingRoot(@TempDir Path root) {
        assertEquals(0, ModDataMigrator.migrateTree(root.resolve("does-not-exist"), "26.1"));
    }

    // strict-JSON normalization: the worldgen fix (Philips Ruins, 26.1 strict gson)

    /** Asserts a string parses under a strict (non-lenient) Gson reader, like 26.1 does. */
    private static void assertStrictParses(String json) {
        try {
            var r = new com.google.gson.stream.JsonReader(new java.io.StringReader(json));
            r.setLenient(false);
            com.google.gson.JsonParser.parseReader(r);
            assertEquals(com.google.gson.stream.JsonToken.END_DOCUMENT, r.peek(), "trailing content");
        } catch (Exception e) {
            fail("expected strict-parseable JSON but got: " + e.getMessage() + "\n--- json ---\n" + json);
        }
    }

    @Test
    void stripsLineCommentsFromWorldgenJson() {
        // the real Philips Ruins processor_list shape: comments + the // at line 2 col 6 that
        // threw MalformedJsonException on 26.1
        String in = "{\n  // Processor lists run a processor for every block placed.\n"
                + "  \"processors\": [ { \"processor_type\": \"minecraft:rule\", \"rules\": [] } ]\n}";
        String out = mig("data/philipsruins/worldgen/processor_list/randomize_crypt_brick.json", in, "26.1");
        assertFalse(out.contains("//"), "line comment should be gone");
        assertTrue(out.contains("minecraft:rule"), "real content preserved");
        assertStrictParses(out);
    }

    @Test
    void stripsBlockCommentsAndTrailingCommas() {
        String in = "{ /* header */ \"a\": 1, \"b\": [1, 2,], }";
        String out = mig("data/m/worldgen/template_pool/x.json", in, "26.1");
        assertFalse(out.contains("/*"), "block comment gone");
        assertStrictParses(out);
        // values survive
        assertTrue(out.contains("\"a\"") && out.contains("\"b\""));
    }

    @Test
    void unterminatedBlockCommentIsPreservedAndRejected(@TempDir Path root) throws Exception {
        Path json = root.resolve("data/m/worldgen/template_pool/x.json");
        Files.createDirectories(json.getParent());
        byte[] original = "{} /* unterminated".getBytes(StandardCharsets.UTF_8);
        Files.write(json, original);

        ModDataMigrator.migrateTreeChecked(root, "26.1");

        assertArrayEquals(original, Files.readAllBytes(json));
        assertThrows(java.io.IOException.class,
                () -> ModDataMigrator.validateStrictDataJsonTree(root));
    }

    @Test
    void blockCommentRemovalKeepsAdjacentTokensInvalid(@TempDir Path root) throws Exception {
        Path json = root.resolve("data/m/worldgen/template_pool/x.json");
        Files.createDirectories(json.getParent());
        Files.writeString(json, "{\"value\":1/* separator */2}");

        ModDataMigrator.migrateTreeChecked(root, "26.1");

        assertTrue(Files.readString(json).contains("1 2"));
        assertThrows(java.io.IOException.class,
                () -> ModDataMigrator.validateStrictDataJsonTree(root));
    }

    @Test
    void doesNotCorruptDoubleSlashInsideStringValue() {
        // a // inside a string value (e.g. a URL) must survive the reserialize intact
        String in = "{\"url\":\"https://example.com/x\", \"trailing\":1,}";
        String out = mig("data/m/worldgen/template_pool/x.json", in, "26.1");
        assertStrictParses(out);
        assertTrue(out.contains("https://example.com/x"), "URL in a string value must be preserved");
    }

    @Test
    void cleanStrictJsonIsLeftByteForByte() {
        // a file with no comments / trailing commas must not be reserialized (byte-identical)
        String in = "{\"processors\":[{\"processor_type\":\"minecraft:rule\",\"rules\":[]}]}";
        assertEquals(in, mig("data/m/worldgen/processor_list/x.json", in, "26.1"));
    }

    @Test
    void malformedUtf8IsNeverRewrittenIntoAcceptedJson() {
        byte[] input = "{\"bad\":\"~\",\"name\":\"minecraft:chain\"}"
                .getBytes(StandardCharsets.UTF_8);
        input[8] = (byte) 0x80;

        byte[] output = ModDataMigrator.migrate(
                "data/m/loot_table/x.json", input, "26.1");

        assertSame(input, output);
    }

    @Test
    void worldgenJsonIsRecognizedAsMigratable() {
        assertTrue(ModDataMigrator.isMigratableData("data/m/worldgen/template_pool/x.json"));
        assertTrue(ModDataMigrator.isMigratableData("data/m/worldgen/processor_list/x.json"));
        assertFalse(ModDataMigrator.isMigratableData("assets/m/models/block/x.json"));
    }

    @Test
    void legacyItemVertexShaderMatchesThe26_2ItemFragmentContract() {
        String name = "assets/old-2d-items/shaders/core/"
                + "rendertype_item_entity_no_cardinal_shading.vsh";
        String in = "#moj_import <minecraft:projection.glsl>\r\n"
                + "in vec2 UV1;\r\n"
                + "out vec4 vertexColor;\r\n"
                + "out vec2 texCoord0;\r\n"
                + "out vec2 texCoord1;\r\n"
                + "out vec2 texCoord2;\r\n"
                + "vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);\r\n"
                + "texCoord0 = UV0;\r\n"
                + "    texCoord1 = UV1;\r\n"
                + "    texCoord2 = UV2;\r\n";
        String out = mig(name, in, "26.2");

        assertTrue(out.contains("#moj_import <minecraft:sample_lightmap.glsl>"));
        assertTrue(out.contains("in ivec2 UV1;"));
        assertTrue(out.contains("sample_lightmap(Sampler2, UV2)"));
        assertTrue(out.contains("out vec4 lightMapColor;"));
        assertTrue(out.contains("out vec4 overlayColor;"));
        assertTrue(out.contains("overlayColor = vec4(0.0);"));
        assertFalse(out.contains("texCoord1"));
        assertFalse(out.contains("texCoord2"));
        assertEquals(in, mig(name, in, "26.1"), "26.1 still provides the old fragment shader");
    }

    @Test
    void normalizeIsGatedOffBeforeTheStrictReader() {
        String in = "{\n // c\n \"a\":1\n}";
        assertEquals(in, mig("data/m/worldgen/template_pool/x.json", in, "1.21.1"),
                "1.21.1 reads data JSON leniently, so leave it");
        assertEquals(in, mig("data/m/worldgen/template_pool/x.json", in, "1.21.5"),
                "1.21.5 is the last lenient host");
    }

    @Test
    void normalizeRunsFromTheFirstStrictHost() {
        String in = "{\n // c\n \"a\":[1,2,],\n}";
        for (String host : new String[] {"1.21.6", "1.21.11", "26.1"}) {
            String out = mig("data/m/worldgen/template_pool/x.json", in, host);
            assertFalse(out.contains("//"), host + " parses data JSON strictly: " + out);
            assertFalse(out.contains(",]") || out.contains(",\n}"), host + ": " + out);
        }
    }

    @Test
    void assetsSideDataIsNotRewrittenInPlace() {
        assertFalse(ModDataMigrator.isMigratableData("assets/m/recipes/x.json"),
                "1.12 mods read these from code in the old shape; the copy under data/ is rewritten");
        assertFalse(ModDataMigrator.isMigratableData("assets/m/loot_tables/x.json"));
        assertFalse(ModDataMigrator.isMigratableData("pack/assets/minecraft/models/item/recipe_book.json"));
        assertTrue(ModDataMigrator.isMigratableData("pack/data/m/recipe/x.json"));
        assertTrue(ModDataMigrator.isMigratableData(
                "assets/m/shaders/core/rendertype_item_entity_no_cardinal_shading.vsh"));
    }

    @Test
    void entityPredicateKeysAreNamespaced() {
        String in = "{\"effects\":{\"minecraft:damage\":[{\"requirements\":{\"condition\":\"minecraft:entity_properties\",\"entity\":\"this\",\"predicate\":{\"type\":\"minecraft:player\",\"flags\":{\"is_baby\":true},\"vehicle\":{\"type\":\"minecraft:pig\"}}}}]}}";
        String out = mig("data/minecraft/enchantment/x.json", in, "26.2");
        assertTrue(out.contains("\"minecraft:entity_type\":\"minecraft:player\""), "type -> minecraft:entity_type");
        assertTrue(out.contains("\"minecraft:flags\""), "flags namespaced");
        assertTrue(out.contains("\"minecraft:vehicle\":{\"minecraft:entity_type\":\"minecraft:pig\"}"),
                "nested vehicle predicate migrated recursively");
        assertFalse(out.contains("\"type\":\"minecraft:player\""), "old key gone");
    }

    @Test
    void alreadyNamespacedPredicatesUntouched() {
        String in = "{\"a\":[{\"condition\":\"minecraft:entity_properties\",\"predicate\":{\"minecraft:entity_type\":\"#minecraft:skeletons\"}}]}";
        assertEquals(in, mig("data/m/enchantment/x.json", in, "26.2"), "26.x-shaped predicate passes through");
    }

    @Test
    void deeplyNestedPredicateJsonIsLeftUntouched() {
        int depth = 10_000;
        String nested = "[".repeat(depth)
                + "{\"condition\":\"minecraft:entity_properties\","
                + "\"predicate\":{\"type\":\"minecraft:pig\"}}"
                + "]".repeat(depth);
        byte[] input = nested.getBytes(StandardCharsets.UTF_8);

        assertSame(input, ModDataMigrator.migrateEntityPredicates(input));
    }

    @Test
    void oversizedPredicateJsonIsLeftUntouched() {
        byte[] input = new byte[16 * 1024 * 1024 + 1];

        assertSame(input, ModDataMigrator.migrateEntityPredicates(input));
    }

    // --- 1.21.4+ client item definitions (items/*.json) ---

    @Test
    void itemDefinitionSynthesizedForBareItemModel() {
        var names = java.util.Set.of(
                "assets/mcwbridges/models/item/iron_bridge.json",
                "assets/mcwbridges/models/item/parts/rail.json",     // nested = template, not an item
                "assets/mcwbridges/textures/item/iron_bridge.png",   // not a model
                "assets/mcwroofs/models/item/black_roof.json",
                "assets/mcwroofs/items/black_roof.json");            // this one already has a definition
        var defs = ModDataMigrator.synthesizeItemDefinitionEntries(names, "26.2");

        assertEquals(1, defs.size(), "only the bare top-level item model gets a definition");
        byte[] def = defs.get("assets/mcwbridges/items/iron_bridge.json");
        assertNotNull(def, "definition must land at assets/<ns>/items/<id>.json");
        String json = new String(def, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"minecraft:model\""), "must use the model definition type");
        assertTrue(json.contains("\"mcwbridges:item/iron_bridge\""), "must point at the existing item model");
    }

    @Test
    void itemDefinitionsGatedTo26xTargets() {
        var names = java.util.Set.of("assets/m/models/item/thing.json");
        assertTrue(ModDataMigrator.synthesizeItemDefinitionEntries(names, "1.21.1").isEmpty(),
                "pre-26.x targets are untouched");
    }

    @Test
    void itemDefinitionsRejectInvalidNamespaces() {
        var names = java.util.Set.of(
                "assets/bad\"namespace/models/item/thing.json",
                "assets/UPPER/models/item/thing.json",
                "assets/valid_namespace/models/item/thing.json");

        var definitions = ModDataMigrator.synthesizeItemDefinitionEntries(names, "26.2");

        assertEquals(java.util.Set.of("assets/valid_namespace/items/thing.json"),
                definitions.keySet());
    }

    @Test
    void migrateTreeWritesItemDefinitions(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("assets/m/models/item/gadget.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "{\"parent\":\"minecraft:item/generated\"}");

        int changed = ModDataMigrator.migrateTree(dir, "26.2");

        Path def = dir.resolve("assets/m/items/gadget.json");
        assertTrue(Files.exists(def), "migrateTree must create the item definition");
        assertTrue(changed >= 1, "the created definition counts as a change");
        assertTrue(Files.readString(def).contains("\"m:item/gadget\""));
    }
    @Test
    void recipeUnlockedConditionRenamedOn263() {
        // Macaw's Trapdoors and Bridges: 652 of these stopped a 26.3 world from loading.
        String in = "{\"criteria\":{\"has_the_recipe\":{\"trigger\":\"minecraft:recipe_unlocked\","
                + "\"conditions\":{\"recipe\":\"m:thing\"}}},\"rewards\":{\"recipes\":[\"m:thing\"]}}";
        String out = mig("data/m/advancement/recipes/thing.json", in, "26.3");
        assertTrue(out.contains("\"conditions\":{\"recipes\":\"m:thing\"}"), out);
        assertEquals(in, mig("data/m/advancement/recipes/thing.json", in, "26.2"),
                "26.2 still reads the old key");
    }

    @Test
    void renamedTriggerFieldsOn263() {
        // Vanilla's own 26.2 criteria for every trigger whose field 26.3 renamed come out as 26.3 wrote them.
        String advancement = "data/m/advancement/adventure/triggers.json";
        String out = mig(advancement, DataLoadGateTest.VANILLA_26_2_CRITERIA, "26.3");

        assertEquals(com.google.gson.JsonParser.parseString(DataLoadGateTest.VANILLA_26_3_CRITERIA),
                com.google.gson.JsonParser.parseString(out));
        assertEquals(DataLoadGateTest.VANILLA_26_2_CRITERIA, mig(advancement, DataLoadGateTest.VANILLA_26_2_CRITERIA,
                "26.2"), "26.2 reads only the old keys");
        assertEquals(DataLoadGateTest.VANILLA_26_3_CRITERIA, mig(advancement, DataLoadGateTest.VANILLA_26_3_CRITERIA,
                "26.3"), "26.3's own data stays as it is");
    }
    @Test
    void legacyRecipeIngredientsBecomeStrings() {
        // Macaw's Roofs: 607 recipes in this shape stopped a 26.3 world from loading.
        String in = "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"WS\"],"
                + "\"key\":{\"W\":{\"item\":\"minecraft:acacia_log\"},\"S\":{\"tag\":\"c:rods\"}},"
                + "\"result\":{\"id\":\"m:roof\"}}";
        String out = mig("data/m/recipe/roof.json", in, "26.3");
        assertTrue(out.contains("\"W\":\"minecraft:acacia_log\""), out);
        assertTrue(out.contains("\"S\":\"#c:rods\""), out);

        String shapeless = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":"
                + "[{\"item\":\"minecraft:stick\"},[{\"item\":\"minecraft:oak_log\"},{\"item\":\"minecraft:birch_log\"}]],"
                + "\"result\":{\"id\":\"m:x\"}}";
        String converted = mig("data/m/recipe/x.json", shapeless, "26.2");
        assertTrue(converted.contains("\"ingredients\":[\"minecraft:stick\",[\"minecraft:oak_log\",\"minecraft:birch_log\"]]"),
                converted);
    }

    @Test
    void oldBlockStatesInModDataUseTheIdKeysOn263() {
        // ProjectE's world transmutations: 26.3's BlockState codec reads only "id" and "properties".
        String in = "{\"transmutations\":[{\"origin\":\"minecraft:ice\",\"result\":{\"Name\":\"minecraft:water\","
                + "\"Properties\":{\"level\":\"0\"}}},{\"origin\":{\"Name\":\"minecraft:lava\"},\"result\":\"minecraft:obsidian\"}]}";
        String out = mig("data/projecte/pe_world_transmutations/defaults.json", in, "26.3");
        assertEquals("{\"transmutations\":[{\"origin\":\"minecraft:ice\",\"result\":{\"id\":\"minecraft:water\","
                + "\"properties\":{\"level\":\"0\"}}},{\"origin\":\"minecraft:lava\",\"result\":\"minecraft:obsidian\"}]}", out);
        assertEquals(in, mig("data/projecte/pe_world_transmutations/defaults.json", in, "26.2"),
                "26.2 still reads Name and Properties");
    }

    @Test
    void nameKeysThatAreNotStatesStayOn263() {
        String display = "{\"display\":{\"Name\":\"{\\\"text\\\":\\\"Hi\\\"}\"},\"owner\":{\"Name\":\"Steve\"},"
                + "\"nested\":{\"Name\":\"m:x\",\"Properties\":{\"a\":{\"b\":1}}}}";
        assertEquals(display, mig("data/m/custom/thing.json", display, "26.3"));
    }

    @Test
    void customIngredientsAreLeftAlone() {
        String in = "{\"type\":\"minecraft:smelting\",\"ingredient\":{\"type\":\"neoforge:components\",\"items\":\"minecraft:stick\"},"
                + "\"result\":{\"id\":\"m:x\"}}";
        assertEquals(in, mig("data/m/recipe/x.json", in, "26.3"));
    }
    @Test
    void recipeAdvancementForMissingRecipeIsDroppedOn263(@TempDir Path root)
            throws Exception {
        // Macaw's Bridges rewards mcwbridges:iron_bridge, but its recipe is iron_bridge_middle.
        Path adv = root.resolve("data/mcwbridges/advancement/recipes/iron_bridge.json");
        Path kept = root.resolve("data/mcwbridges/advancement/recipes/iron_bridge_pier.json");
        Path other = root.resolve("data/mcwbridges/advancement/recipes/uses_other_mod.json");
        Files.createDirectories(adv.getParent());
        Files.createDirectories(root.resolve("data/mcwbridges/recipe"));
        Files.writeString(root.resolve("data/mcwbridges/recipe/iron_bridge_pier.json"), "{}");
        Files.writeString(adv, "{\"rewards\":{\"recipes\":[\"mcwbridges:iron_bridge\"]}}");
        Files.writeString(kept, "{\"rewards\":{\"recipes\":[\"mcwbridges:iron_bridge_pier\"]}}");
        Files.writeString(other, "{\"rewards\":{\"recipes\":[\"othermod:thing\"]}}");

        assertTrue(ModDataMigrator.danglingRecipeAdvancements(
                java.util.List.of("data/mcwbridges/advancement/recipes/iron_bridge.json"),
                name -> new byte[0], "26.2").isEmpty(), "26.2 ignores dangling ids");

        ModDataMigrator.migrateTree(root, "26.3");
        assertFalse(Files.exists(adv), "the advancement for a missing recipe must be dropped");
        assertTrue(Files.exists(kept), "an advancement for a shipped recipe stays");
        assertTrue(Files.exists(other), "another mod may provide a foreign recipe");
    }

    @Test
    void oldProviderValueWrappersAreInlined() {
        String old = """
                {"monster_spawn_light_level": {"type": "minecraft:uniform", "value": {"min_inclusive": 0, "max_inclusive": 7}},
                 "height": {"type": "minecraft:constant", "value": {"absolute": 64}},
                 "count": {"type": "minecraft:clamped", "value": {"source": {"type": "minecraft:uniform",
                   "value": {"min_inclusive": -2, "max_inclusive": 4}}, "min_inclusive": 0, "max_inclusive": 4}}}""";
        String migrated = new String(ModDataMigrator.migrate("data/mod/dimension_type/gourd.json",
                old.getBytes(StandardCharsets.UTF_8), "26.3"), StandardCharsets.UTF_8);
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(migrated).getAsJsonObject();

        assertEquals(com.google.gson.JsonParser.parseString(
                "{\"type\": \"minecraft:uniform\", \"min_inclusive\": 0, \"max_inclusive\": 7}"),
                json.get("monster_spawn_light_level"));
        assertEquals(com.google.gson.JsonParser.parseString(
                "{\"type\": \"minecraft:constant\", \"value\": {\"absolute\": 64}}"),
                json.get("height"), "a constant height provider's value is an anchor, not a wrapper");
        assertEquals(com.google.gson.JsonParser.parseString("""
                {"type": "minecraft:clamped", "source": {"type": "minecraft:uniform", "min_inclusive": -2,
                 "max_inclusive": 4}, "min_inclusive": 0, "max_inclusive": 4}"""), json.get("count"),
                "a nested provider is inlined too");
    }


    /**
     * Mythic Fantasies, a 1.19.2 Forge mod, on NeoForge 1.21.1: its dimension type's light level
     * still had the wrapper 1.21 dropped, and the 26.x-only rewrite never ran on that host.
     */
    @Test
    void hostsFrom1_21RewriteOnlyTheirOwnFormatChanges() {
        byte[] wrapped = """
                {"monster_spawn_light_level": {"type": "minecraft:uniform", "value": {"min_inclusive": 0, "max_inclusive": 7}},
                 "bed_works": true}""".getBytes(StandardCharsets.UTF_8);

        String on1211 = new String(ModDataMigrator.migrate("data/mod/dimension_type/gourd.json", wrapped, "1.21.1"),
                StandardCharsets.UTF_8);
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(on1211).getAsJsonObject();
        assertFalse(json.getAsJsonObject("monster_spawn_light_level").has("value"), "1.21 dropped the wrapper");
        assertTrue(json.has("bed_works"), "1.21.1 still reads the old flags; attributes came in 1.21.11");

        assertTrue(new String(ModDataMigrator.migrate("data/mod/dimension_type/gourd.json", wrapped, "1.21.11"),
                StandardCharsets.UTF_8).contains("\"attributes\""), "1.21.11 moved the flags to attributes");
        assertSame(wrapped, ModDataMigrator.migrate("data/mod/dimension_type/gourd.json", wrapped, "1.20.1"),
                "1.20.1 still reads the wrapper");
    }
    @Test
    void oldTreeSoilBecomesBelowTrunkProvider() {
        String old = """
                {"type": "minecraft:tree", "config": {"trunk_placer": {"type": "minecraft:straight_trunk_placer",
                 "base_height": 4, "height_rand_a": 2, "height_rand_b": 0},
                 "dirt_provider": {"type": "minecraft:simple_state_provider", "state": {"Name": "minecraft:dirt"}},
                 "force_dirt": false}}""";
        com.google.gson.JsonObject config = com.google.gson.JsonParser.parseString(new String(ModDataMigrator.migrate(
                "data/mod/worldgen/configured_feature/birch.json", old.getBytes(StandardCharsets.UTF_8), "26.2"),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("config");

        assertFalse(config.has("dirt_provider") || config.has("force_dirt"), config.toString());
        assertEquals(com.google.gson.JsonParser.parseString("""
                {"type": "minecraft:rule_based_state_provider", "rules": [{"if_true": {"type": "minecraft:not",
                 "predicate": {"type": "minecraft:matching_block_tag", "tag": "minecraft:cannot_replace_below_tree_trunk"}},
                 "then": {"type": "minecraft:simple_state_provider", "state": {"Name": "minecraft:dirt"}}}]}"""),
                config.get("below_trunk_provider"));

        String forced = old.replace("\"force_dirt\": false", "\"force_dirt\": true");
        com.google.gson.JsonObject forcedConfig = com.google.gson.JsonParser.parseString(new String(
                ModDataMigrator.migrate("data/mod/worldgen/configured_feature/azalea.json",
                        forced.getBytes(StandardCharsets.UTF_8), "26.2"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("config");
        assertEquals("minecraft:simple_state_provider",
                forcedConfig.getAsJsonObject("below_trunk_provider").get("type").getAsString(),
                "force_dirt keeps the provider as it is");
    }

    @Test
    void renamesTheAlternativeLootConditionFrom1_20() {
        String loot = "{\"pools\":[{\"conditions\":[{\"condition\": \"minecraft:alternative\","
                + "\"terms\":[{\"condition\":\"minecraft:survives_explosion\"}]}]}]}";
        for (String host : new String[] {"1.20.1", "1.21.1", "26.3"}) {
            String out = new String(ModDataMigrator.migrate("data/archipelago/loot_tables/blocks/palm.json",
                    loot.getBytes(StandardCharsets.UTF_8), host), StandardCharsets.UTF_8);
            assertTrue(out.contains("minecraft:any_of"), "renamed on " + host + ": " + out);
            assertFalse(out.contains("alternative"), "nothing left of the old name on " + host);
        }
        String untouched = new String(ModDataMigrator.migrate("data/archipelago/loot_tables/blocks/palm.json",
                loot.getBytes(StandardCharsets.UTF_8), "1.19.2"), StandardCharsets.UTF_8);
        assertTrue(untouched.contains("alternative"), "1.19 still reads the old name");
    }

    @Test
    void inlinesAProviderWhoseTypeHasNoNamespace() {
        String placed = "{\"feature\":\"boh:sasafras_tree_feature\",\"placement\":[{\"type\":\"minecraft:count\","
                + "\"count\":{\"type\":\"uniform\",\"value\":{\"min_inclusive\":0,\"max_inclusive\":2}}}]}";
        String out = new String(ModDataMigrator.migrate("data/boh/worldgen/placed_feature/sasafras_tree_feature.json",
                placed.getBytes(StandardCharsets.UTF_8), "1.21.1"), StandardCharsets.UTF_8);
        assertFalse(out.contains("\"value\""), "MCreator writes a bare type id: " + out);
        assertTrue(out.contains("\"max_inclusive\": 2"), out);
    }

    @Test
    void typesUntypedLootUniformsOn26_3() {
        String loot = "{\"pools\":[{\"rolls\":{\"min\":1,\"max\":3},\"entries\":[{\"type\":\"minecraft:item\","
                + "\"name\":\"minecraft:bread\",\"functions\":[{\"function\":\"minecraft:set_count\","
                + "\"count\":{\"min\":2,\"max\":4}}],\"conditions\":[{\"condition\":\"minecraft:entity_properties\","
                + "\"entity\":\"this\",\"predicate\":{\"distance\":{\"absolute\":{\"min\":1,\"max\":3}}}}]}]}]}";
        String out = new String(ModDataMigrator.migrate("data/chefsdelight/loot_table/chests/cooker.json",
                loot.getBytes(StandardCharsets.UTF_8), "26.3"), StandardCharsets.UTF_8);
        com.google.gson.JsonObject pool = com.google.gson.JsonParser.parseString(out).getAsJsonObject()
                .getAsJsonArray("pools").get(0).getAsJsonObject();
        assertEquals("minecraft:uniform", pool.getAsJsonObject("rolls").get("type").getAsString());
        // After 26.3's own rewrite the function is a "modifier" and the condition a "condition".
        com.google.gson.JsonObject entry = pool.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("minecraft:uniform", entry.getAsJsonObject("modifier")
                .getAsJsonObject("count").get("type").getAsString());
        assertFalse(entry.getAsJsonObject("condition").toString().contains("uniform"),
                "a range inside a condition is not a number provider: " + out);
    }

    @Test
    void recipeResultsNameTheirItemByIdFrom1_20_5() {
        String shaped = "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"#\"],"
                + "\"key\":{\"#\":\"minecraft:stick\"},\"result\":{\"item\":\"mod:wand\",\"count\":2}}";
        for (String target : new String[]{"1.20.5", "1.21.1", "1.21.11", "26.3"}) {
            String out = mig("data/mod/recipe/wand.json", shaped, target);
            assertTrue(out.contains("\"result\":{\"id\":\"mod:wand\",\"count\":2}"), target + ": " + out);
        }
        assertEquals(shaped, mig("data/mod/recipes/wand.json", shaped, "1.20.4"),
                "1.20.4 still reads the item key");
    }

    @Test
    void bareCookingAndStonecuttingResultsBecomeItemStacks() {
        String smelting = "{\"type\":\"minecraft:smelting\",\"ingredient\":\"mod:ore\","
                + "\"result\":\"mod:ingot\",\"experience\":0.7,\"cookingtime\":200}";
        assertTrue(mig("data/mod/recipe/ingot.json", smelting, "1.21.1")
                .contains("\"result\":{\"id\":\"mod:ingot\"}"));
        String stonecutting = "{\"type\":\"stonecutting\",\"count\":2,\"ingredient\":\"mod:stone\","
                + "\"result\":\"mod:slab\"}";
        String out = mig("data/mod/recipe/slab.json", stonecutting, "26.3");
        com.google.gson.JsonObject recipe = com.google.gson.JsonParser.parseString(out).getAsJsonObject();
        assertFalse(recipe.has("count"), "the count moved into the result: " + out);
        assertEquals("mod:slab", recipe.getAsJsonObject("result").get("id").getAsString());
        assertEquals(2, recipe.getAsJsonObject("result").get("count").getAsInt());
    }

    @Test
    void resultsThatCannotBeRewrittenFaithfullyAreLeftAlone() {
        String modNbt = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:stick\"],"
                + "\"result\":{\"item\":\"mod:wand\",\"nbt\":\"{Charge:3}\"}}";
        assertTrue(mig("data/mod/recipe/wand.json", modNbt, "1.21.1")
                        .contains("\"components\":{\"minecraft:custom_data\":\"{Charge:3}\"}"),
                "the mod's own NBT carries over whole as custom data");
        String vanillaNbt = modNbt.replace("{Charge:3}", "{CanDestroy:[\\\"minecraft:stone\\\"]}");
        assertTrue(mig("data/mod/recipe/wand.json", vanillaNbt, "1.21.1").contains("\"item\""),
                "without the key it cannot carry over, the recipe would give a different item");
        String modType = "{\"type\":\"mod:infusing\",\"result\":{\"item\":\"mod:gem\"}}";
        assertEquals(modType, mig("data/mod/recipe/gem.json", modType, "26.3"),
                "the mod's own code reads its own recipe type in the shape it expects");
    }

    @Test
    void componentRulesStartAtTheReleaseThatChangedThem() {
        String modelData = "{\"components\":{\"minecraft:custom_model_data\":7}}";
        assertEquals(modelData, mig("data/m/loot_table/x.json", modelData, "1.21.3"));
        assertTrue(mig("data/m/loot_table/x.json", modelData, "1.21.4").contains("{\"floats\":[7]}"));

        String dyed = "{\"components\":{\"minecraft:dyed_color\":{\"rgb\":255}}}";
        assertEquals(dyed, mig("data/m/loot_table/x.json", dyed, "1.21.4"));
        assertTrue(mig("data/m/loot_table/x.json", dyed, "1.21.5").contains("\"minecraft:dyed_color\":255"));
    }

    @Test
    void thePotionEntitySplitStartsAt1_21_5() {
        String tag = "{\"values\":[\"minecraft:potion\"]}";
        assertEquals(tag, mig("data/m/tags/entity_type/x.json", tag, "1.21.4"));
        assertTrue(mig("data/m/tags/entity_type/x.json", tag, "1.21.5").contains("minecraft:splash_potion"));
    }

    @Test
    void entityPredicateKeysWaitFor26_2() {
        String in = "{\"a\":[{\"condition\":\"minecraft:entity_properties\",\"entity\":\"this\","
                + "\"predicate\":{\"type\":\"minecraft:pig\"}}]}";
        assertEquals(in, mig("data/m/predicate/pig.json", in, "26.1.2"),
                "26.1.2 still reads type and ignores the registry keys");
        assertTrue(mig("data/m/predicate/pig.json", in, "26.2").contains("\"minecraft:entity_type\""));
    }

    @Test
    void anItemWithComponentsBecomesTheLoadersComponentIngredient() {
        String recipe = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:sugar\"},"
                + "{\"item\":\"minecraft:potion\",\"components\":{\"minecraft:potion_contents\":\"minecraft:water\"}}],"
                + "\"result\":{\"id\":\"m:brew\"}}";
        byte[] in = recipe.getBytes(StandardCharsets.UTF_8);

        String neo = new String(ModDataMigrator.migrate("data/m/recipe/brew.json", in, "1.21.1", "26.3",
                MigrationContext.Loader.NEOFORGE), StandardCharsets.UTF_8);
        assertTrue(neo.contains("{\"neoforge:ingredient_type\":\"neoforge:components\",\"items\":\"minecraft:potion\","
                + "\"components\":{\"minecraft:potion_contents\":\"minecraft:water\"}}"), neo);

        String fabric = new String(ModDataMigrator.migrate("data/m/recipe/brew.json", in, "1.21.1", "1.21.11",
                MigrationContext.Loader.FABRIC), StandardCharsets.UTF_8);
        assertTrue(fabric.contains("{\"fabric:type\":\"fabric:components\",\"base\":\"minecraft:potion\""), fabric);

        String forge = new String(ModDataMigrator.migrate("data/m/recipe/brew.json", in, "1.21.1", "26.3",
                MigrationContext.Loader.FORGE), StandardCharsets.UTF_8);
        assertTrue(forge.contains("\"components\":{\"minecraft:potion_contents\""), "Forge has no such ingredient");
        assertTrue(forge.contains("\"minecraft:sugar\""), "the plain ingredient still becomes a string");

        assertSame(in, ModDataMigrator.migrate("data/m/recipe/brew.json", in, "1.21.1", "1.21.1",
                MigrationContext.Loader.NEOFORGE), "1.21.1 still reads the object form");
    }

    @Test
    void advancementIconsNameTheirItemByIdOn1_21Hosts() {
        String advancement = "{\"display\":{\"icon\":{\"item\":\"mod:wand\"},\"title\":\"x\"},\"criteria\":{}}";
        assertTrue(mig("data/mod/advancement/wand.json", advancement, "1.21.1")
                .contains("\"icon\":{\"id\":\"mod:wand\"}"));
        assertEquals(advancement, mig("data/mod/advancements/wand.json", advancement, "1.20.1"));
    }

    @Test
    void lootModifierRewriteFollowsTheNamedLoaderNotTheRunningOne() {
        String name = "data/mod/loot_modifiers/fish.json";
        String modifier = "{\"type\":\"mod:add\",\"conditions\":[{\"condition\":\"forge:loot_table_id\","
                + "\"loot_table_id\":\"minecraft:gameplay/fishing\"}]}";
        byte[] in = modifier.getBytes(StandardCharsets.UTF_8);
        String forge = new String(ModDataMigrator.migrate(name, in, "26.3", MigrationContext.Loader.FORGE),
                StandardCharsets.UTF_8);
        assertTrue(forge.contains("\"conditions\":[{\"type\":\"forge:loot_table_id\""), forge);
        assertSame(in, ModDataMigrator.migrate(name, in, "26.3", MigrationContext.Loader.FABRIC),
                "no host loader reads a Forge loot modifier on Fabric");
    }

    @Test
    void pixelSizedPaintingVariantIsReadInBlocks() {
        // #283: Scorched Guns ships a 64 by 32 pixel painting, which 1.21 rejects as blocks
        // ("Value must be within range [1;16]") and which then failed the whole registry load.
        String painting = "{\"asset_id\":\"scguns:the_collective\",\"height\":32,\"width\":64}";

        com.google.gson.JsonObject migrated = com.google.gson.JsonParser.parseString(
                mig("data/scguns/painting_variant/the_collective.json", painting, "1.21.1")).getAsJsonObject();

        assertEquals(4, migrated.get("width").getAsInt());
        assertEquals(2, migrated.get("height").getAsInt());
        assertEquals("scguns:the_collective", migrated.get("asset_id").getAsString());
    }

    @Test
    void paintingVariantAlreadyInBlocksIsKept() {
        String painting = "{\"asset_id\":\"mod:wide\",\"height\":2,\"width\":16}";
        assertEquals(painting, mig("data/mod/painting_variant/wide.json", painting, "1.21.1"));
    }
}
