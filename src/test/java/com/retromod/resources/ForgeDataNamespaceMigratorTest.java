/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * NeoForge reads a Forge mod's data under its own names, and newer Forge reads its conditions and
 * convention tags under new ones. The converter moves the data a Forge 1.20.1 mod ships to what the
 * host reads, and leaves alone every host that still reads Forge's names.
 */
class ForgeDataNamespaceMigratorTest {

    private static final MigrationContext.Loader FORGE = MigrationContext.Loader.FORGE;
    private static final MigrationContext.Loader NEOFORGE = MigrationContext.Loader.NEOFORGE;

    private static final String ORES = "data/mod/forge/biome_modifier/ores.json";
    private static final String ORES_ON_NEOFORGE = "data/mod/neoforge/biome_modifier/ores.json";
    private static final String ADD_ORES = "{\"type\":\"forge:add_features\",\"biomes\":\"#minecraft:is_overworld\","
            + "\"features\":\"mod:ruby_ore\",\"step\":\"underground_ores\"}";
    private static final String LIST = "data/forge/loot_modifiers/global_loot_modifiers.json";
    private static final String LIST_ON_NEOFORGE = "data/neoforge/loot_modifiers/global_loot_modifiers.json";
    private static final String HAT_IN_DUNGEONS = "{\"type\":\"mod:add_item\",\"conditions\":[{\"condition\":"
            + "\"forge:loot_table_id\",\"loot_table_id\":\"minecraft:chests/simple_dungeon\"}],\"item\":\"mod:hat\"}";

    private static MigrationContext context(String source, MigrationContext.Loader sourceLoader,
                                            MigrationContext.Loader host, String target) {
        return MigrationContext.builder(target).sourceMcVersion(source).sourceLoader(sourceLoader)
                .targetLoader(host).build().forRun(List.of("mod"));
    }

    /** Runs only this converter, as the orchestrator would. */
    private static int migrate(MapResourceTree tree, MigrationContext.Loader host, String target)
            throws IOException {
        MigrationContext context = context("1.20.1", FORGE, host, target);
        ForgeDataNamespaceMigrator converter = new ForgeDataNamespaceMigrator();
        return converter.appliesTo(context) ? converter.apply(tree, context) : 0;
    }

    private static JsonObject read(MapResourceTree tree, String name) throws IOException {
        assertTrue(tree.exists(name), name + " exists");
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject();
    }

    // ------------------------------------------------------------ biome and structure modifiers

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"1.20.2", "1.21.1", "26.3"})
    @DisplayName("A Forge biome modifier moves to NeoForge's folder with NeoForge's type")
    void biomeModifierMovesToNeoForge(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORES, ADD_ORES));

        migrate(tree, NEOFORGE, target);

        JsonObject modifier = read(tree, ORES_ON_NEOFORGE);
        assertEquals("neoforge:add_features", modifier.get("type").getAsString());
        assertEquals("mod:ruby_ore", modifier.get("features").getAsString());
        assertFalse(tree.exists(ORES), "NeoForge never reads the forge folder, so the original goes");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"NEOFORGE, 1.20.1", "FORGE, 1.20.1", "FORGE, 1.21.11", "FORGE, 26.3"})
    @DisplayName("A host that reads Forge's biome modifiers keeps them where they are")
    void forgeNamedHostsKeepBiomeModifiers(MigrationContext.Loader host, String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORES, ADD_ORES));

        migrate(tree, host, target);

        assertEquals(ADD_ORES, text(tree.read(ORES)));
        assertFalse(tree.exists(ORES_ON_NEOFORGE));
    }

    @Test
    @DisplayName("forge:any and the other holder sets take NeoForge's names on 26.3")
    void holderSetsTakeNeoForgeNames() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORES,
                "{\"type\":\"forge:add_spawns\",\"biomes\":{\"type\":\"forge:any\"},"
                        + "\"spawners\":{\"type\":\"mod:ghost\",\"weight\":5,\"minCount\":1,\"maxCount\":2}}",
                "data/mod/forge/biome_modifier/caves.json",
                "{\"type\":\"forge:remove_features\",\"biomes\":{\"type\":\"forge:not\",\"value\":"
                        + "{\"type\":\"forge:or\",\"values\":[\"#minecraft:is_nether\",\"#minecraft:is_end\"]}},"
                        + "\"features\":\"minecraft:ore_coal_upper\",\"steps\":\"underground_ores\"}"));

        migrate(tree, NEOFORGE, "26.3");

        JsonObject spawns = read(tree, ORES_ON_NEOFORGE);
        assertEquals("neoforge:any", spawns.getAsJsonObject("biomes").get("type").getAsString());
        assertEquals(JsonParser.parseString("{\"type\":\"minecraft:uniform\",\"min_inclusive\":1,"
                + "\"max_inclusive\":2}"), spawns.getAsJsonObject("spawners").get("count"),
                "26.3 reads only count, so an active spawn entry must carry it");
        JsonObject not = read(tree, "data/mod/neoforge/biome_modifier/caves.json").getAsJsonObject("biomes");
        assertEquals("neoforge:not", not.get("type").getAsString());
        assertEquals("neoforge:or", not.getAsJsonObject("value").get("type").getAsString());
    }

    private static final String SPAWNS = "data/mod/forge/biome_modifier/spawns.json";
    private static final String SPAWNS_ON_NEOFORGE = "data/mod/neoforge/biome_modifier/spawns.json";

    private static String addSpawns(String spawners) {
        return "{\"type\":\"forge:add_spawns\",\"biomes\":\"#minecraft:is_overworld\",\"spawners\":" + spawners + "}";
    }

    @Test
    @DisplayName("On 26.3 an activated add_spawns gives each entry one count, as a number or a uniform range")
    void addSpawnsTakeOneCountOn263() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(SPAWNS, addSpawns(
                "[{\"type\":\"mod:ghost\",\"weight\":5,\"minCount\":1,\"maxCount\":3},"
                        + "{\"type\":\"mod:wisp\",\"weight\":2,\"minCount\":4,\"maxCount\":4}]")));

        migrate(tree, NEOFORGE, "26.3");

        assertEquals(JsonParser.parseString("[{\"type\":\"mod:ghost\",\"weight\":5,\"count\":{\"type\":"
                + "\"minecraft:uniform\",\"min_inclusive\":1,\"max_inclusive\":3}},"
                + "{\"type\":\"mod:wisp\",\"weight\":2,\"count\":4}]"),
                read(tree, SPAWNS_ON_NEOFORGE).get("spawners"));
        assertFalse(tree.exists(SPAWNS));
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"1.21.1", "26.2"})
    @DisplayName("Before 26.3 an activated add_spawns keeps minCount and maxCount, which the host still reads")
    void addSpawnsKeepTheirCountsBefore263(String target) throws IOException {
        String entry = "{\"type\":\"mod:ghost\",\"weight\":5,\"minCount\":1,\"maxCount\":3}";
        MapResourceTree tree = new MapResourceTree(entries(SPAWNS, addSpawns(entry)));

        migrate(tree, NEOFORGE, target);

        assertEquals(JsonParser.parseString(entry), read(tree, SPAWNS_ON_NEOFORGE).get("spawners"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"type\":\"mod:ghost\",\"weight\":5,\"minCount\":3,\"maxCount\":1}",
            "[{\"type\":\"mod:ghost\",\"weight\":5,\"minCount\":1}]",
            "{\"type\":\"mod:ghost\",\"weight\":5,\"minCount\":1.5,\"maxCount\":2}"})
    @DisplayName("On 26.3 an add_spawns whose counts cannot convert stays inert instead of stopping the world")
    void unconvertibleSpawnCountsStayInert(String spawners) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(SPAWNS, addSpawns(spawners)));

        migrate(tree, NEOFORGE, "26.3");

        assertTrue(tree.exists(SPAWNS), "the modifier stays in the Forge folder");
        assertFalse(tree.exists(SPAWNS_ON_NEOFORGE));
    }

    @Test
    @DisplayName("After the full pipeline on 26.3, every active add_spawns entry has the count shape")
    void fullPipelineLeavesLoadableSpawnsOn263() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(SPAWNS, addSpawns(
                "{\"type\":\"mod:ghost\",\"weight\":20,\"minCount\":4,\"maxCount\":4}")));

        ModResourceMigrations.standard().run(tree, context("1.20.1", FORGE, NEOFORGE, "26.3"));

        JsonObject entry = read(tree, SPAWNS_ON_NEOFORGE).getAsJsonObject("spawners");
        assertEquals(4, entry.get("count").getAsInt());
        assertFalse(entry.has("minCount") || entry.has("maxCount"), entry.toString());
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"1.20.2", "1.21.1", "26.3"})
    @DisplayName("A modifier whose Forge guard cannot convert stays inert, so it never runs unguarded")
    void unconvertibleGuardKeepsModifierInert(String target) throws IOException {
        String modifier = "{\"type\":\"forge:add_features\",\"forge:conditions\":[{\"type\":\"forge:mod_loaded\","
                + "\"modid\":\"other\"},{\"type\":\"mod:config_enabled\",\"option\":\"compat\"}],"
                + "\"biomes\":\"#minecraft:is_overworld\",\"features\":\"other:tin_ore\",\"step\":\"underground_ores\"}";
        MapResourceTree tree = new MapResourceTree(entries(ORES, modifier,
                "data/mod/forge/biome_modifier/single.json", "{\"type\":\"forge:add_features\","
                        + "\"forge:condition\":{\"type\":\"mod:config_enabled\",\"option\":\"compat\"},"
                        + "\"biomes\":\"#minecraft:is_overworld\",\"features\":\"other:tin_ore\","
                        + "\"step\":\"underground_ores\"}"));

        ModResourceMigrations.standard().run(tree, context("1.20.1", FORGE, NEOFORGE, target));

        assertFalse(tree.exists(ORES_ON_NEOFORGE), "NeoForge would ignore the forge:conditions it keeps");
        assertTrue(tree.exists(ORES));
        assertFalse(tree.exists("data/mod/neoforge/biome_modifier/single.json"));
        assertTrue(tree.exists("data/mod/forge/biome_modifier/single.json"));
    }

    @Test
    @DisplayName("A modifier that also ships NeoForge's own guard moves, since NeoForge reads that guard")
    void neoForgeGuardLetsModifierMove() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORES, "{\"type\":\"forge:add_features\","
                + "\"forge:conditions\":[{\"type\":\"mod:config_enabled\",\"option\":\"compat\"}],"
                + "\"neoforge:conditions\":[{\"type\":\"neoforge:mod_loaded\",\"modid\":\"other\"}],"
                + "\"biomes\":\"#minecraft:is_overworld\",\"features\":\"other:tin_ore\",\"step\":\"underground_ores\"}"));

        migrate(tree, NEOFORGE, "26.3");

        assertEquals("neoforge:mod_loaded", read(tree, ORES_ON_NEOFORGE).getAsJsonArray("neoforge:conditions")
                .get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    @DisplayName("A modifier of the mod's own type stays inert, since its Forge class cannot load on NeoForge")
    void customModifierTypeStaysInert() throws IOException {
        String custom = "{\"type\":\"mod:retromod_biome_loading\"}";
        MapResourceTree tree = new MapResourceTree(entries(
                "data/mod/forge/biome_modifier/retromod_biome_loading.json", custom));

        assertEquals(0, migrate(tree, NEOFORGE, "26.3"));

        assertEquals(custom, text(tree.read("data/mod/forge/biome_modifier/retromod_biome_loading.json")));
        assertFalse(tree.exists("data/mod/neoforge/biome_modifier/retromod_biome_loading.json"));
    }

    @ParameterizedTest(name = "{0} on NeoForge {1}")
    @CsvSource({"biome_modifier, 1.21.1, true", "biome_modifier, 26.1.1, true",
            "biome_modifier, 26.1.2, false", "structure_modifier, 26.2, true",
            "structure_modifier, 26.3, false"})
    @DisplayName("A none modifier moves only while the host still registers the none type")
    void noneModifiersFollowTheHost(String folder, String target, boolean moves) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/mod/forge/" + folder + "/off.json", "{\"type\":\"forge:none\"}"));

        migrate(tree, NEOFORGE, target);

        assertEquals(moves, tree.exists("data/mod/neoforge/" + folder + "/off.json"));
        assertEquals(!moves, tree.exists("data/mod/forge/" + folder + "/off.json"),
                "a none modifier the host cannot read stays inert in the Forge folder");
    }

    @Test
    @DisplayName("A jar that already ships NeoForge's modifier keeps it unchanged")
    void existingNeoForgeModifierWins() throws IOException {
        String native_ = "{\"type\":\"neoforge:add_features\",\"biomes\":\"#c:is_overworld\","
                + "\"features\":\"mod:ruby_ore\",\"step\":\"underground_ores\"}";
        MapResourceTree tree = new MapResourceTree(entries(ORES, ADD_ORES, ORES_ON_NEOFORGE, native_));

        migrate(tree, NEOFORGE, "26.3");

        assertEquals(native_, text(tree.read(ORES_ON_NEOFORGE)));
    }

    // ------------------------------------------------------------ global loot modifiers

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"1.20.4", "1.21.1", "26.1.1"})
    @DisplayName("The loot modifier list moves to NeoForge's namespace while NeoForge reads a list")
    void lootModifierListMoves(String target) throws IOException {
        String list = "{\"replace\":false,\"entries\":[\"mod:hat_in_dungeons\"]}";
        MapResourceTree tree = new MapResourceTree(entries(LIST, list,
                "data/mod/loot_modifiers/hat_in_dungeons.json", HAT_IN_DUNGEONS));

        migrate(tree, NEOFORGE, target);

        assertEquals(list, text(tree.read(LIST_ON_NEOFORGE)));
        assertFalse(tree.exists(LIST));
        assertEquals("neoforge:loot_table_id", read(tree, "data/mod/loot_modifiers/hat_in_dungeons.json")
                .getAsJsonArray("conditions").get(0).getAsJsonObject().get("condition").getAsString());
    }

    @Test
    @DisplayName("From 26.1.2 the list is gone, and a modifier it left out is switched off")
    void lootModifierFolderHostDropsTheList() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(LIST,
                "{\"replace\":false,\"entries\":[\"mod:hat_in_dungeons\"]}",
                "data/mod/loot_modifiers/hat_in_dungeons.json", HAT_IN_DUNGEONS,
                "data/mod/loot_modifiers/unused.json", "{\"type\":\"mod:add_item\",\"item\":\"mod:old_hat\"}"));

        migrate(tree, NEOFORGE, "26.3");

        assertFalse(tree.exists(LIST), "the host would load the list as a modifier with no type");
        assertFalse(tree.exists(LIST_ON_NEOFORGE));
        assertFalse(read(tree, "data/mod/loot_modifiers/hat_in_dungeons.json").has("neoforge:conditions"));
        JsonArray off = read(tree, "data/mod/loot_modifiers/unused.json").getAsJsonArray("neoforge:conditions");
        assertEquals("neoforge:never", off.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    @DisplayName("On 26.3 the whole pipeline leaves a loot modifier in NeoForge's single-condition shape")
    void lootModifierEndsInNeoForge263Shape() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(LIST,
                "{\"replace\":false,\"entries\":[\"mod:hat_in_dungeons\"]}",
                "data/mod/loot_modifiers/hat_in_dungeons.json", HAT_IN_DUNGEONS));
        MigrationContext context = context("1.20.1", FORGE, NEOFORGE, "26.3");

        ModResourceMigrations.standard().run(tree, context);

        JsonObject modifier = read(tree, "data/mod/loot_modifiers/hat_in_dungeons.json");
        JsonObject condition = modifier.getAsJsonObject("condition");
        assertEquals("neoforge:loot_table_id", condition.get("type").getAsString());
        assertEquals("minecraft:chests/simple_dungeon", condition.get("loot_table_id").getAsString());
        assertFalse(modifier.has("conditions"));
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @CsvSource({"1.20.4, neoforge:can_tool_perform_action, action",
            "1.21.1, neoforge:can_item_perform_ability, ability",
            "26.3, neoforge:can_item_perform_ability, ability"})
    @DisplayName("Forge's tool action loot condition takes the host's name and field")
    void toolActionCondition(String target, String type, String field) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries("data/mod/loot_modifiers/shears.json",
                "{\"type\":\"mod:add_item\",\"conditions\":[{\"condition\":\"forge:can_tool_perform_action\","
                        + "\"action\":\"shears_dig\"}],\"item\":\"mod:fiber\"}"));

        migrate(tree, NEOFORGE, target);

        JsonObject condition = read(tree, "data/mod/loot_modifiers/shears.json")
                .getAsJsonArray("conditions").get(0).getAsJsonObject();
        assertEquals(type, condition.get("condition").getAsString());
        assertEquals("shears_dig", condition.get(field).getAsString());
    }

    // ------------------------------------------------------------ load conditions

    private static final String GUARDED_RECIPE = "data/mod/recipes/compat.json";

    private static String guarded(String conditions) {
        return "{\"type\":\"minecraft:crafting_shapeless\",\"conditions\":" + conditions
                + ",\"ingredients\":[{\"item\":\"other:gear\"}],\"result\":{\"item\":\"mod:widget\"}}";
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @CsvSource({"1.20.4, neoforge:item_exists, item, neoforge:true",
            "1.21.1, neoforge:item_exists, item, neoforge:true",
            "26.3, neoforge:registered, value, neoforge:always"})
    @DisplayName("Forge recipe conditions become the host's neoforge:conditions")
    void recipeConditionsOnNeoForge(String target, String exists, String itemField, String always)
            throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(GUARDED_RECIPE, guarded(
                "[{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"},"
                        + "{\"type\":\"forge:not\",\"value\":{\"type\":\"forge:item_exists\",\"item\":\"other:gone\"}},"
                        + "{\"type\":\"forge:or\",\"values\":[{\"type\":\"forge:true\"},"
                        + "{\"type\":\"forge:tag_empty\",\"tag\":\"forge:ingots/tin\"}]}]")));

        migrate(tree, NEOFORGE, target);

        JsonObject recipe = read(tree, GUARDED_RECIPE);
        assertFalse(recipe.has("conditions"), "the old key would be an unknown recipe field");
        JsonArray conditions = recipe.getAsJsonArray("neoforge:conditions");
        assertEquals("neoforge:mod_loaded", conditions.get(0).getAsJsonObject().get("type").getAsString());
        JsonObject missing = conditions.get(1).getAsJsonObject().getAsJsonObject("value");
        assertEquals(exists, missing.get("type").getAsString());
        assertEquals("other:gone", missing.get(itemField).getAsString());
        JsonArray either = conditions.get(2).getAsJsonObject().getAsJsonArray("values");
        assertEquals(always, either.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("neoforge:tag_empty", either.get(1).getAsJsonObject().get("type").getAsString());
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"1.20.2", "1.21.1", "26.3"})
    @DisplayName("On Forge 1.20.2 and newer, a conditions list becomes the single forge:condition")
    void recipeConditionsOnForge(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(GUARDED_RECIPE, guarded(
                "[{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"},"
                        + "{\"type\":\"forge:item_exists\",\"item\":\"other:gear\"}]"),
                "data/mod/recipes/single.json", guarded("[{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"}]")));

        migrate(tree, FORGE, target);

        JsonObject both = read(tree, GUARDED_RECIPE).getAsJsonObject("forge:condition");
        assertEquals("forge:and", both.get("type").getAsString());
        assertEquals(2, both.getAsJsonArray("values").size());
        JsonObject single = read(tree, "data/mod/recipes/single.json");
        assertEquals("forge:mod_loaded", single.getAsJsonObject("forge:condition").get("type").getAsString());
        assertFalse(single.has("conditions"));
    }

    @Test
    @DisplayName("Forge 1.20.1 still reads the conditions list, so nothing changes there")
    void forge1201KeepsItsConditions() throws IOException {
        String recipe = guarded("[{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"}]");
        MapResourceTree tree = new MapResourceTree(entries(GUARDED_RECIPE, recipe));

        assertEquals(0, migrate(tree, FORGE, "1.20.1"));
        assertEquals(recipe, text(tree.read(GUARDED_RECIPE)));
    }

    @Test
    @DisplayName("A condition list with the mod's own condition type stays as shipped")
    void modConditionTypesAreNotConverted() throws IOException {
        String recipe = guarded("[{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"},"
                + "{\"type\":\"mod:config_enabled\",\"option\":\"compat\"}]");
        MapResourceTree tree = new MapResourceTree(entries(GUARDED_RECIPE, recipe));

        migrate(tree, NEOFORGE, "26.3");

        assertEquals(recipe, text(tree.read(GUARDED_RECIPE)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"NEOFORGE, 26.3, neoforge:conditions", "FORGE, 26.3, forge:condition"})
    @DisplayName("A biome modifier's forge:conditions take the host's key")
    void registryEntryConditions(MigrationContext.Loader host, String target, String key) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORES, "{\"type\":\"forge:add_features\","
                + "\"forge:conditions\":[{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"}],"
                + "\"biomes\":\"#minecraft:is_overworld\",\"features\":\"other:ore\",\"step\":\"underground_ores\"}"));

        migrate(tree, host, target);

        String name = host == NEOFORGE ? ORES_ON_NEOFORGE : ORES;
        JsonObject modifier = read(tree, name);
        assertFalse(modifier.has("forge:conditions"));
        assertTrue(modifier.has(key), name + " has " + key);
    }

    // ------------------------------------------------------------ convention tags

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"NEOFORGE, 1.21.1", "NEOFORGE, 26.3", "FORGE, 26.1", "FORGE, 26.3"})
    @DisplayName("forge: convention tag references take the host's c: tag")
    void conventionTagReferences(MigrationContext.Loader host, String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/mod/recipes/rod.json", "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"I\",\"S\"],"
                        + "\"key\":{\"I\":{\"tag\":\"forge:ingots/iron\"},\"S\":\"#forge:rods/wooden\"},"
                        + "\"result\":{\"item\":\"mod:rod\"}}",
                "data/mod/tags/items/shiny.json", "{\"values\":[\"#forge:glass\",\"#forge:boh_entities\"]}"));

        migrate(tree, host, target);

        JsonObject key = read(tree, "data/mod/recipes/rod.json").getAsJsonObject("key");
        assertEquals("c:ingots/iron", key.getAsJsonObject("I").get("tag").getAsString());
        assertEquals("#c:rods/wooden", key.get("S").getAsString());
        JsonArray values = read(tree, "data/mod/tags/items/shiny.json").getAsJsonArray("values");
        assertEquals("#c:glass_blocks", values.get(0).getAsString(), "a renamed path follows the table");
        assertEquals("#forge:boh_entities", values.get(1).getAsString(),
                "a tag the table does not know is the mod's own and keeps its name");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"NEOFORGE, 1.20.4", "FORGE, 1.21.11"})
    @DisplayName("A host that still fills forge: tags keeps the references")
    void forgeTagHostsKeepReferences(MigrationContext.Loader host, String target) throws IOException {
        String tag = "{\"values\":[\"#forge:ingots/iron\"]}";
        MapResourceTree tree = new MapResourceTree(entries("data/mod/tags/items/metal.json", tag));

        migrate(tree, host, target);

        assertEquals(tag, text(tree.read("data/mod/tags/items/metal.json")));
        assertFalse(tree.exists("data/c/tags/item/ingots/iron.json"));
    }

    @Test
    @DisplayName("A mapped forge: tag the mod fills is copied into the c: tag, optional and without replace")
    void conventionTagContributionsAreCopied() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/forge/tags/items/ingots/iron.json", "{\"replace\":true,\"values\":"
                        + "[\"mod:iron_alloy\",\"#forge:ingots/iron\",\"#forge:ingots/copper\"]}",
                "data/forge/tags/entity_types/boh_entities.json", "{\"values\":[\"mod:ghost\"]}"));

        migrate(tree, NEOFORGE, "26.3");

        JsonObject copy = read(tree, "data/c/tags/item/ingots/iron.json");
        assertFalse(copy.has("replace"), "a replace would empty the shared tag for every mod");
        assertEquals(JsonParser.parseString("[{\"id\":\"mod:iron_alloy\",\"required\":false},"
                + "{\"id\":\"#c:ingots/copper\",\"required\":false}]"), copy.get("values"),
                "entries are optional, references follow the table, and the self reference is gone");
        assertTrue(tree.exists("data/forge/tags/items/ingots/iron.json"), "the mod's code still reads forge:");
        assertFalse(tree.exists("data/c/tags/entity_type/boh_entities.json"));
    }

    @ParameterizedTest(name = "NeoForge {0} -> {1}")
    @CsvSource({"1.20.6, data/c/tags/items/gravels.json", "1.21.1, data/c/tags/item/gravels.json"})
    @DisplayName("The c: copy uses the folder name the host reads")
    void conventionTagFolderFollowsTheHost(String target, String copy) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/forge/tags/items/gravel.json", "{\"values\":[\"mod:ash_gravel\"]}"));

        migrate(tree, NEOFORGE, target);

        assertTrue(tree.exists(copy), copy);
    }

    @Test
    @DisplayName("A c: tag the jar already ships is kept as it is")
    void existingCommonTagWins() throws IOException {
        String own = "{\"values\":[\"mod:iron_alloy\"]}";
        MapResourceTree tree = new MapResourceTree(entries(
                "data/forge/tags/item/ingots/iron.json", "{\"values\":[\"mod:other_alloy\"]}",
                "data/c/tags/item/ingots/iron.json", own));

        migrate(tree, NEOFORGE, "26.3");

        assertEquals(own, text(tree.read("data/c/tags/item/ingots/iron.json")));
    }

    @Test
    @DisplayName("The table maps a tag only from the version whose loader ships its c: tag")
    void tableGatesEachRow() {
        assertTrue(ForgeConventionTags.size() > 200, "the bundled table loaded");
        assertNull(ForgeConventionTags.commonPath("gravel", NEOFORGE, "1.20.5"));
        assertEquals("gravels", ForgeConventionTags.commonPath("gravel", NEOFORGE, "1.20.6"));
        assertEquals("gravels", ForgeConventionTags.commonPath("gravel", FORGE, "26.1"));
        assertNull(ForgeConventionTags.commonPath("gravel", FORGE, "1.21.11"));
        assertNull(ForgeConventionTags.commonPath("ingots/iron", MigrationContext.Loader.FABRIC, "26.3"));
        assertNull(ForgeConventionTags.commonPath("tools/knives", NEOFORGE, "26.3"),
                "no loader ships a c: knife tag, so the Farmer's Delight name stays");
    }

    @ParameterizedTest(name = "NeoForge {0}: {1}")
    @CsvSource({"1.21, #forge:tools/wrench", "1.21.1, #c:tools/wrench", "1.21.2, #forge:tools/wrench",
            "1.21.3, #forge:tools/wrench", "1.21.4, #c:tools/wrench", "26.3, #c:tools/wrench"})
    @DisplayName("A tag the newest 1.21.1 build ships, but 1.21.2 and 1.21.3 do not, maps on exactly those hosts")
    void wrenchFollowsEachHostsNewestBuild(String target, String reference) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries("data/mod/tags/items/tools.json",
                "{\"values\":[\"#forge:tools/wrench\"]}"));

        migrate(tree, NEOFORGE, target);

        assertEquals(reference, read(tree, "data/mod/tags/items/tools.json").getAsJsonArray("values")
                .get(0).getAsString());
    }

    // ------------------------------------------------------------ what it never touches

    @ParameterizedTest(name = "{0} source on {1} {2}")
    @CsvSource({"NEOFORGE, NEOFORGE, 26.3", "FORGE, FABRIC, 26.3", "FORGE, QUILT, 1.21.1"})
    @DisplayName("NeoForge jars and Fabric-family hosts get nothing from this converter")
    void otherLoadersAreLeftAlone(MigrationContext.Loader source, MigrationContext.Loader host,
                                  String target) {
        assertFalse(new ForgeDataNamespaceMigrator().appliesTo(context("1.20.1", source, host, target)));
    }

    @Test
    @DisplayName("A 1.12 Forge mod's recipe conditions belong to the 1.12 recipe converter")
    void legacyForgeSourceIsLeftAlone() {
        assertFalse(new ForgeDataNamespaceMigrator().appliesTo(context("1.12.2", FORGE, NEOFORGE, "26.3")));
        assertTrue(new ForgeDataNamespaceMigrator().appliesTo(context(null, FORGE, NEOFORGE, "26.3")),
                "an unknown version is not taken as old");
    }

    @Test
    @DisplayName("Running twice changes nothing more")
    void idempotent() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORES, ADD_ORES,
                LIST, "{\"replace\":false,\"entries\":[\"mod:hat_in_dungeons\"]}",
                "data/mod/loot_modifiers/hat_in_dungeons.json", HAT_IN_DUNGEONS,
                GUARDED_RECIPE, guarded("[{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"}]"),
                "data/forge/tags/items/ingots/iron.json", "{\"values\":[\"mod:iron_alloy\"]}"));
        migrate(tree, NEOFORGE, "26.3");
        Map<String, byte[]> once = new TreeMap<>(tree.entries());

        assertEquals(0, migrate(tree, NEOFORGE, "26.3"));
        assertEquals(digests(once), digests(tree.entries()));
    }

    private static Map<String, String> digests(Map<String, byte[]> entries) {
        Map<String, String> digests = new TreeMap<>();
        entries.forEach((name, bytes) -> digests.put(name, sha256(bytes)));
        return digests;
    }
}
