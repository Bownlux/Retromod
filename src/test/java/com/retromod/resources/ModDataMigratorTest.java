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
 * Regression for {@link ModDataMigrator}: the 1.21.x -> 26.x data-pack JSON rewrites
 * that let a transformed structure mod's loot tables and advancements parse on 26.1.
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
    void normalizeIsGatedOffForPre26() {
        String in = "{\n // c\n \"a\":1\n}";
        assertEquals(in, mig("data/m/worldgen/template_pool/x.json", in, "1.21.1"),
                "lenient JSON is fine on a 1.21.x target - leave it");
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
}
