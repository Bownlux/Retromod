/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Registry entries from 1.21.x mods, rewritten for the release that renamed or rebuilt them. */
class LegacyRegistryEntriesTest {

    private static final String ENCHANTMENT = "data/mod/enchantment/traveler.json";

    @Test
    @DisplayName("1.21.1 attribute ids lose their generic. and player. prefixes from 1.21.2")
    void attributeIdsLoseTheirPrefix() throws IOException {
        assertJson("""
                {"effects":{"minecraft:location_changed":[{"effect":{"type":"minecraft:attribute",
                 "attribute":"minecraft:step_height","id":"mod:step","operation":"add_value","amount":0.5}},
                 {"effect":{"type":"minecraft:attribute","attribute":"minecraft:block_interaction_range",
                 "id":"mod:reach","operation":"add_value","amount":0.5}}]}}""",
                convert(ENCHANTMENT, """
                {"effects":{"minecraft:location_changed":[{"effect":{"type":"minecraft:attribute",
                 "attribute":"minecraft:generic.step_height","id":"mod:step","operation":"add_value","amount":0.5}},
                 {"effect":{"type":"minecraft:attribute","attribute":"player.block_interaction_range",
                 "id":"mod:reach","operation":"add_value","amount":0.5}}]}}""", "1.21.1", "1.21.2"));
    }

    @Test
    @DisplayName("a function's attribute ids are renamed, a bare one stays bare, and another mod's is left alone")
    void functionAttributesAreRenamed() throws IOException {
        String function = "data/mod/function/init.mcfunction";
        MapResourceTree tree = new MapResourceTree(Map.of(function, bytes("""
                attribute @s generic.movement_speed base set 1.1
                summon guardian ~ ~ ~ {attributes:[{id:generic.max_health,base:10}]}
                attribute @s minecraft:player.block_interaction_range base set 6
                data modify storage mod:temp player.UUID set from entity @s UUID
                attribute @s othermod:generic.armor base set 2
                """)));
        run(tree, context("1.21.1", "26.3"));
        assertEquals("""
                attribute @s movement_speed base set 1.1
                summon guardian ~ ~ ~ {attributes:[{id:max_health,base:10}]}
                attribute @s minecraft:block_interaction_range base set 6
                data modify storage mod:temp player.UUID set from entity @s UUID
                attribute @s othermod:generic.armor base set 2
                """, text(tree, function));
    }

    @Test
    @DisplayName("a time check reads the overworld clock from 26.1, and damage_item is change_item_damage")
    void timeCheckAndDamageItem() throws IOException {
        assertJson("""
                {"effects":{"minecraft:tick":[{"effect":{"type":"minecraft:change_item_damage","amount":1},
                 "requirements":{"condition":"minecraft:time_check","value":{"min":0,"max":12000},"period":24000,
                 "clock":"minecraft:overworld"}}]}}""",
                convert(ENCHANTMENT, """
                {"effects":{"minecraft:tick":[{"effect":{"type":"minecraft:damage_item","amount":1},
                 "requirements":{"condition":"minecraft:time_check","value":{"min":0,"max":12000},"period":24000}}]}}""",
                        "1.21.1", "26.1"));
    }

    @Test
    @DisplayName("a 1.21.1 wolf variant gets assets, a biome spawn condition, the pale fallback and baby textures")
    void wolfVariantIsRebuilt() throws IOException {
        assertJson("""
                {"assets":{"wild":"minecraft:entity/wolf/wolf","tame":"minecraft:entity/wolf/wolf_tame",
                  "angry":"minecraft:entity/wolf/wolf_angry"},
                 "spawn_conditions":[{"condition":{"type":"minecraft:biome","biomes":"#c:has_wolf_variant/pale"},
                  "priority":1},{"priority":0}],
                 "baby_assets":{"wild":"minecraft:entity/wolf/wolf_baby","tame":"minecraft:entity/wolf/wolf_tame_baby",
                  "angry":"minecraft:entity/wolf/wolf_angry_baby"}}""",
                convert("data/minecraft/wolf_variant/pale.json", """
                {"wild_texture":"minecraft:entity/wolf/wolf","tame_texture":"minecraft:entity/wolf/wolf_tame",
                 "angry_texture":"minecraft:entity/wolf/wolf_angry","biomes":"#c:has_wolf_variant/pale"}""",
                        "1.21.1", "26.3"));
    }

    @Test
    @DisplayName("a visible root advancement gets a background on 26.3, and a child loses the one it cannot have")
    void advancementBackgrounds() throws IOException {
        assertJson("""
                {"display":{"icon":{"id":"minecraft:torch"},"title":"","description":"","hidden":true,
                 "background":"minecraft:gui/advancements/backgrounds/stone"},
                 "criteria":{"a":{"trigger":"minecraft:impossible"}}}""",
                convert("data/mod/advancement/root.json", """
                {"display":{"icon":{"id":"minecraft:torch"},"title":"","description":"","hidden":true},
                 "criteria":{"a":{"trigger":"minecraft:impossible"}}}""", "1.21.1", "26.3"));
        assertJson("""
                {"parent":"mod:root","display":{"icon":{"id":"minecraft:torch"},"title":"","description":""},
                 "criteria":{"a":{"trigger":"minecraft:impossible"}}}""",
                convert("data/mod/advancement/child.json", """
                {"parent":"mod:root","display":{"icon":{"id":"minecraft:torch"},"title":"","description":"",
                 "background":"minecraft:gui/advancements/backgrounds/stone"},
                 "criteria":{"a":{"trigger":"minecraft:impossible"}}}""", "1.21.1", "26.3"));
    }

    @Test
    @DisplayName("data built for the host or of unknown age is left alone")
    void gatedOnSource() throws IOException {
        String old = """
                {"condition":"minecraft:time_check","value":{"min":0,"max":12000}}""";
        assertJson(old, convert("data/mod/predicate/day.json", old, "26.1", "26.3"));
        assertFalse(new LegacyRegistryEntries().appliesTo(context(null, "26.3")), "unknown source");
    }

    @Test
    @DisplayName("item components 1.21.2 to 26.3 replaced are reshaped wherever data sets them")
    void componentsAreReshaped() throws IOException {
        assertJson("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:filled_map","functions":[
                 {"function":"minecraft:set_components","components":{
                   "minecraft:damage_resistant":{"types":"#minecraft:is_fire"},
                   "minecraft:attribute_modifiers":[{"type":"minecraft:armor","id":"mod:a","amount":1,"operation":"add_value"}],
                   "minecraft:tooltip_display":{"hidden_components":["minecraft:attribute_modifiers"]}}}]}]}]}""",
                convert("data/mod/loot_table/map.json", """
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:filled_map","functions":[
                 {"function":"minecraft:set_components","components":{"fire_resistant":{},
                   "minecraft:attribute_modifiers":{"modifiers":[{"type":"minecraft:armor","id":"mod:a","amount":1,
                     "operation":"add_value"}],"show_in_tooltip":false},
                   "minecraft:map_color":3679277}}]}]}]}""", "1.21.1", "26.3"));
    }

    @Test
    @DisplayName("an exploration map's own structure tag that the jar never shipped is kept as an empty tag")
    void missingOwnMapTagIsAddedEmpty() throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of("data/mod/loot_table/vault.json", bytes("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:map","functions":[
                 {"function":"minecraft:exploration_map","destination":"mod:lighthouse"}]}]}]}""")));
        run(tree, context("1.21.1", "26.3"));
        assertJson("{\"values\":[]}", text(tree, "data/mod/tags/worldgen/structure/lighthouse.json"));
    }

    @Test
    @DisplayName("on NeoForge an advancement whose icon is another mod's item loads only when that item is registered")
    void foreignIconGetsCondition() throws IOException {
        String advancement = """
                {"display":{"icon":{"item":"waystones:mossy_waystone"},"title":"","description":"",
                 "background":"minecraft:gui/advancements/backgrounds/stone"},
                 "criteria":{"a":{"trigger":"minecraft:impossible"}}}""";
        assertJson("""
                {"display":{"icon":{"item":"waystones:mossy_waystone"},"title":"","description":"",
                 "background":"minecraft:gui/advancements/backgrounds/stone"},
                 "criteria":{"a":{"trigger":"minecraft:impossible"}},
                 "neoforge:conditions":[{"type":"neoforge:registered","registry":"minecraft:item",
                   "value":"waystones:mossy_waystone"}]}""",
                convert("data/mod/advancement/root.json", advancement, "1.21.1", "26.3"));
        String own = advancement.replace("waystones:mossy_waystone", "mod:tower_key");
        assertJson(own, convert("data/mod/advancement/root.json", own, "1.21.1", "26.3"));
        MapResourceTree forge = new MapResourceTree(Map.of("data/mod/advancement/root.json", bytes(advancement)));
        run(forge, context("1.21.1", "26.3", MigrationContext.Loader.FORGE));
        assertJson(advancement, text(forge, "data/mod/advancement/root.json"));
    }

    @Test
    @DisplayName("on Forge a loot modifier's referenced predicate is put in place of the reference")
    void forgeModifierInlinesReference() throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of(
                "data/mod/loot_modifiers/add_gem.json", bytes("""
                        {"type":"mod:add_item","conditions":[{"condition":"minecraft:reference","name":"mod:in_cave"},
                          {"condition":"minecraft:reference","name":"other:missing"}]}"""),
                "data/mod/predicate/in_cave.json", bytes("""
                        [{"condition":"minecraft:random_chance","chance":0.5},{"condition":"minecraft:killed_by_player"}]""")));
        run(tree, context("1.21.1", "26.3", MigrationContext.Loader.FORGE));
        assertJson("""
                {"type":"mod:add_item","conditions":[{"condition":"minecraft:all_of","terms":[
                  {"condition":"minecraft:random_chance","chance":0.5},{"condition":"minecraft:killed_by_player"}]},
                  {"condition":"minecraft:reference","name":"other:missing"}]}""",
                text(tree, "data/mod/loot_modifiers/add_gem.json"));
    }

    @Test
    @DisplayName("entity data holding only an id the old game could not read is dropped, and a readable one stays")
    void unreadableEntityDataIsDropped() throws IOException {
        assertJson("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:ghast_spawn_egg",
                  "functions":[{"function":"minecraft:set_components","components":{}}]}]}]}""",
                convert("data/mod/loot_table/egg.json", """
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:ghast_spawn_egg",
                  "functions":[{"function":"minecraft:set_components","components":{"minecraft:entity_data":
                    {"id":"minecraft:allay,Tags:[\\"egg\\"],NoAI:1b"}}}]}]}]}""", "1.21.1", "26.3"));
        String readable = """
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:ghast_spawn_egg",
                  "functions":[{"function":"minecraft:set_components","components":{"minecraft:entity_data":
                    {"id":"minecraft:allay"}}}]}]}]}""";
        assertJson(readable, convert("data/mod/loot_table/egg.json", readable, "1.21.1", "26.3"));
    }

    private static String convert(String name, String json, String source, String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of(name, bytes(json)));
        run(tree, context(source, target));
        return text(tree, name);
    }

    private static void run(MapResourceTree tree, MigrationContext context) throws IOException {
        LegacyRegistryEntries converter = new LegacyRegistryEntries();
        if (converter.appliesTo(context)) converter.apply(tree, context);
    }

    private static MigrationContext context(String source, String target) {
        return context(source, target, MigrationContext.Loader.NEOFORGE);
    }

    private static MigrationContext context(String source, String target, MigrationContext.Loader loader) {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(loader).declaredNamespaces(List.of("mod")).build()
                .forRun(List.of("mod", "minecraft"));
    }

    private static String text(MapResourceTree tree, String name) throws IOException {
        return new String(tree.read(name), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertJson(String expected, String actual) {
        JsonElement want = JsonParser.parseString(expected);
        assertEquals(want, JsonParser.parseString(actual), actual);
    }
}
