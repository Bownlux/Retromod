/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Old mods' loot data on Minecraft 26.3. A 1.21.1 door table kept its {@code half = lower} check
 * in the old {@code "conditions"} list, which 26.3 ignores, so both halves of a broken door
 * dropped a door. Expected outputs are what 26.3's own data says for the same vanilla tables.
 */
class LegacyLootConditionMigratorTest {

    private static final String DOOR = "data/polished_planks/loot_table/blocks/polished_acacia_door.json";

    @Test
    @DisplayName("a 1.21.1 door table keeps its lower-half check on 26.3")
    void doorKeepsLowerHalfCheck() {
        String old = """
                {"type":"minecraft:block","pools":[{"bonus_rolls":0.0,
                 "conditions":[{"condition":"minecraft:survives_explosion"}],
                 "entries":[{"type":"minecraft:item","conditions":[{"block":"polished_planks:polished_acacia_door",
                   "condition":"minecraft:block_state_property","properties":{"half":"lower"}}],
                   "name":"polished_planks:polished_acacia_door"}],"rolls":1.0}]}""";
        assertJson("""
                {"type":"minecraft:block","pools":[{"bonus_rolls":0.0,
                 "condition":{"type":"minecraft:survives_explosion"},
                 "entries":[{"type":"minecraft:item","condition":{"type":"minecraft:match_block",
                   "blocks":"polished_planks:polished_acacia_door","state":{"half":"lower"}},
                   "name":"polished_planks:polished_acacia_door"}],"rolls":1.0}]}""",
                ModDataMigrator.migrate(DOOR, bytes(old), "26.3"));
    }

    @Test
    @DisplayName("functions become one modifier, and a function's own conditions follow")
    void functionsBecomeModifier() {
        String slab = """
                {"type":"minecraft:block","pools":[{"entries":[{"type":"minecraft:item","functions":[
                  {"conditions":[{"block":"minecraft:oak_slab","condition":"minecraft:block_state_property",
                    "properties":{"type":"double"}}],"count":2.0,"function":"minecraft:set_count"},
                  {"function":"minecraft:explosion_decay"}],"name":"minecraft:oak_slab"}],"rolls":1.0}]}""";
        assertJson("""
                {"type":"minecraft:block","pools":[{"entries":[{"type":"minecraft:item","modifier":[
                  {"type":"minecraft:set_count","condition":{"type":"minecraft:match_block",
                    "blocks":"minecraft:oak_slab","state":{"type":"double"}},"count":2.0},
                  {"type":"minecraft:explosion_decay"}],"name":"minecraft:oak_slab"}],"rolls":1.0}]}""",
                LegacyLootConditionMigrator.migrate(bytes(slab), true));

        String single = """
                {"pools":[{"entries":[{"type":"minecraft:item","name":"minecraft:barrel",
                  "functions":[{"function":"minecraft:copy_components","source":"block_entity"}]}]}]}""";
        assertJson("""
                {"pools":[{"entries":[{"type":"minecraft:item","name":"minecraft:barrel",
                  "modifier":{"type":"minecraft:copy_components","source":"block_entity"}}]}]}""",
                LegacyLootConditionMigrator.migrate(bytes(single), true));
    }

    @Test
    @DisplayName("several conditions become all_of, and composite terms are converted too")
    void severalConditionsBecomeAllOf() {
        String old = """
                {"pools":[{"entries":[{"type":"minecraft:item","name":"minecraft:oak_sapling","conditions":[
                  {"condition":"minecraft:survives_explosion"},
                  {"condition":"minecraft:inverted","term":{"condition":"minecraft:any_of","terms":[
                    {"condition":"minecraft:match_tool","predicate":{"items":"minecraft:shears"}}]}}]}]}]}""";
        assertJson("""
                {"pools":[{"entries":[{"type":"minecraft:item","name":"minecraft:oak_sapling","condition":
                  {"type":"minecraft:all_of","terms":[{"type":"minecraft:survives_explosion"},
                   {"type":"minecraft:inverted","term":{"type":"minecraft:any_of","terms":[
                     {"type":"minecraft:match_tool","predicate":{"items":"minecraft:shears"}}]}}]}}]}]}""",
                LegacyLootConditionMigrator.migrate(bytes(old), true));
    }

    @Test
    @DisplayName("tag entries name their tag in items, and damage type tags gain a #")
    void tagEntriesAndDamageTags() {
        String old = """
                {"pools":[{"conditions":[{"condition":"minecraft:damage_source_properties",
                  "predicate":{"tags":[{"expected":true,"id":"minecraft:is_lightning"}]}}],
                  "entries":[{"type":"minecraft:tag","name":"minecraft:creeper_drop_music_discs","expand":true}]}]}""";
        assertJson("""
                {"pools":[{"condition":{"type":"minecraft:damage_source_properties",
                  "predicate":{"tags":[{"expected":true,"id":"#minecraft:is_lightning"}]}},
                  "entries":[{"type":"minecraft:tag","items":"#minecraft:creeper_drop_music_discs","expand":true}]}]}""",
                LegacyLootConditionMigrator.migrate(bytes(old), true));
    }

    @Test
    @DisplayName("advancement condition lists collapse, including a list of victim lists")
    void advancementConditionLists() {
        String old = """
                {"criteria":{"c":{"trigger":"minecraft:channeled_lightning","conditions":{
                  "player":[{"condition":"minecraft:entity_properties","entity":"this","predicate":{}}],
                  "victims":[[{"condition":"minecraft:entity_properties","entity":"this",
                    "predicate":{"minecraft:entity_type":"minecraft:villager"}}]]}}}}""";
        assertJson("""
                {"criteria":{"c":{"trigger":"minecraft:channeled_lightning","conditions":{
                  "player":{"type":"minecraft:entity_properties","entity":"this","predicate":{}},
                  "victims":[{"type":"minecraft:entity_properties","entity":"this",
                    "predicate":{"minecraft:entity_type":"minecraft:villager"}}]}}}}""",
                LegacyLootConditionMigrator.migrate(bytes(old), false));
    }

    @Test
    @DisplayName("data already in 26.3's format, with predicate references, is left alone")
    void currentFormatIsUntouched() {
        String current = """
                {"type":"minecraft:block","pools":[{"entries":[{"type":"minecraft:item",
                  "condition":"minecraft:tool/can_shear","name":"minecraft:oak_leaves",
                  "modifier":[{"type":"minecraft:set_count","count":2},{"type":"minecraft:explosion_decay"}]}],
                  "condition":{"type":"minecraft:inverted","term":{"type":"minecraft:any_of",
                    "terms":["minecraft:tool/can_shear","minecraft:tool/can_silk_touch"]}},"rolls":1}]}""";
        byte[] in = bytes(current);
        assertSame(in, LegacyLootConditionMigrator.migrate(in, true));
    }

    @Test
    @DisplayName("loader condition keys such as fabric:load_conditions keep their own format")
    void loaderConditionsAreUntouched() {
        String advancement = """
                {"fabric:load_conditions":[{"condition":"fabric:all_mods_loaded","values":["a"]}],
                 "criteria":{"c":{"trigger":"minecraft:tick"}}}""";
        byte[] in = bytes(advancement);
        assertSame(in, LegacyLootConditionMigrator.migrate(in, false));
    }

    @Test
    @DisplayName("hosts before 26.3 keep the old format, which they still read")
    void olderHostsKeepTheOldFormat() {
        String old = """
                {"pools":[{"conditions":[{"condition":"minecraft:survives_explosion"}],"entries":[]}]}""";
        byte[] in = bytes(old);
        assertEquals(old, new String(ModDataMigrator.migrate(DOOR, in, "26.2"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a global loot modifier gets one condition on NeoForge and keeps its list on Forge")
    void globalLootModifiers() {
        String old = """
                {"type":"examplemod:add_item","item":"minecraft:diamond","conditions":[
                  {"condition":"minecraft:random_chance","chance":0.5},
                  {"condition":"minecraft:killed_by_player"}]}""";
        assertJson("""
                {"type":"examplemod:add_item","item":"minecraft:diamond","condition":{"type":"minecraft:all_of",
                  "terms":[{"type":"minecraft:random_chance","chance":0.5},{"type":"minecraft:killed_by_player"}]}}""",
                LegacyLootConditionMigrator.migrate(bytes(old), false, false));
        assertJson("""
                {"type":"examplemod:add_item","item":"minecraft:diamond","conditions":[
                  {"type":"minecraft:random_chance","chance":0.5},{"type":"minecraft:killed_by_player"}]}""",
                LegacyLootConditionMigrator.migrate(bytes(old), false, true));
    }

    @Test
    @DisplayName("a stray function name on an entry is dropped, a set_contents block entity type is not")
    void strayFunctionNameOnlyOnEntries() {
        String entry = """
                {"pools":[{"entries":[{"type":"minecraft:item","name":"minecraft:beef",
                  "function":"minecraft:furnace_smelt"}]}]}""";
        assertJson("""
                {"pools":[{"entries":[{"type":"minecraft:item","name":"minecraft:beef"}]}]}""",
                LegacyLootConditionMigrator.migrate(bytes(entry), true));

        String contents = """
                {"pools":[{"entries":[{"type":"minecraft:item","name":"minecraft:shulker_box","functions":[
                  {"function":"minecraft:set_contents","type":"minecraft:shulker_box","entries":[]}]}]}]}""";
        String migrated = new String(LegacyLootConditionMigrator.migrate(bytes(contents), true),
                StandardCharsets.UTF_8);
        assertTrue(migrated.contains("minecraft:set_contents"), migrated);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertJson(String expected, byte[] actual) {
        JsonElement want = JsonParser.parseString(expected);
        JsonElement got = JsonParser.parseString(new String(actual, StandardCharsets.UTF_8));
        assertEquals(want, got, "migrated JSON");
    }

    @Test
    @DisplayName("a reference condition names the predicate by id, and value_check is the int check")
    void referenceAndValueCheck() {
        String old = """
                [{"condition":"minecraft:value_check","value":{"type":"minecraft:score","target":{"type":"minecraft:fixed",
                  "name":"$x"},"score":"settings"},"range":{"min":1,"max":2}},
                 {"condition":"minecraft:inverted","term":{"condition":"minecraft:reference","name":"mod:tests/held"}}]""";
        assertJson("""
                {"type":"minecraft:all_of","terms":[{"type":"minecraft:int_value_check","value":{"type":"minecraft:score",
                  "target":{"type":"minecraft:fixed","name":"$x"},"score":"settings"},"test":{"min":1,"max":2}},
                 {"type":"minecraft:inverted","term":"mod:tests/held"}]}""",
                ModDataMigrator.migrate("data/mod/predicate/check.json", bytes(old), "26.3"));
        assertJson("""
                {"type":"minecraft:all_of","terms":["mod:tests/held"]}""",
                ModDataMigrator.migrate("data/mod/predicate/alias.json",
                        bytes("{\"condition\":\"minecraft:reference\",\"name\":\"mod:tests/held\"}"), "26.3"));
    }

    @Test
    @DisplayName("a Forge loot modifier's condition list keeps a reference as an object, since Forge reads direct conditions")
    void forgeModifierKeepsReferenceObject() {
        assertJson("""
                {"type":"mod:add_item","conditions":[{"type":"minecraft:reference","name":"mod:in_nether"},
                  {"type":"minecraft:inverted","term":"mod:raining"}]}""",
                LegacyLootConditionMigrator.migrate(bytes("""
                {"type":"mod:add_item","conditions":[{"condition":"minecraft:reference","name":"mod:in_nether"},
                  {"condition":"minecraft:inverted","term":{"condition":"minecraft:reference","name":"mod:raining"}}]}"""),
                        false, true));
    }

    @Test
    @DisplayName("a reference function names the item modifier by id")
    void referenceFunction() {
        assertJson("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:map"}]}],
                 "modifier":"mod:loot_modifier"}""",
                LegacyLootConditionMigrator.migrate(bytes("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:map"}]}],
                 "functions":[{"function":"minecraft:reference","name":"mod:loot_modifier"}]}"""), true));
    }
    @Test
    @DisplayName("an exploration map names its destination tag with a #, and gets the old default when it had none")
    void explorationMapDestination() {
        assertJson("""
                {"type":"minecraft:sequence","functions":[{"type":"minecraft:exploration_map","destination":"#mod:tower",
                  "zoom":1},{"type":"minecraft:exploration_map","destination":"#minecraft:on_treasure_maps"}]}""",
                ModDataMigrator.migrate("data/mod/item_modifier/maps.json", bytes("""
                [{"function":"minecraft:exploration_map","destination":"mod:tower","zoom":1},
                 {"function":"minecraft:exploration_map"}]"""), "26.3"));
    }

    @Test
    @DisplayName("an advancement's empty player check list, which matched everything, is dropped")
    void emptyPlayerCheckIsDropped() {
        assertJson("""
                {"criteria":{"a":{"trigger":"minecraft:player_interacted_with_entity","conditions":{"item":{}}}}}""",
                ModDataMigrator.migrate("data/mod/advancement/feed.json", bytes("""
                {"criteria":{"a":{"trigger":"minecraft:player_interacted_with_entity","conditions":{"player":[],"item":{}}}}}"""),
                        "26.3"));
    }

    @Test
    @DisplayName("an untyped range inside a uniform or an attribute modifier's amount gets its type")
    void nestedUntypedUniforms() {
        assertJson("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:stick","modifier":[
                 {"type":"minecraft:set_count","count":{"type":"minecraft:uniform","min":1,
                   "max":{"type":"minecraft:uniform","min":9,"max":13}}},
                 {"type":"minecraft:set_attributes","modifiers":[{"attribute":"minecraft:armor","id":"mod:a",
                   "amount":{"type":"minecraft:uniform","min":2.5,"max":3},"operation":"add_value","slot":"head"}]}]}]}]}""",
                ModDataMigrator.migrate("data/mod/loot_table/stick.json", bytes("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:stick","functions":[
                 {"function":"minecraft:set_count","count":{"type":"minecraft:uniform","min":1,"max":{"min":9,"max":13}}},
                 {"function":"minecraft:set_attributes","modifiers":[{"attribute":"minecraft:armor","id":"mod:a",
                   "amount":{"min":2.5,"max":3},"operation":"add_value","slot":"head"}]}]}]}]}"""), "26.3"));
    }
}
