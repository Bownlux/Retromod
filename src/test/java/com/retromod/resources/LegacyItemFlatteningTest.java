/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A 1.12 mod named vanilla items by an id and a data value. Once its recipes, loot tables and
 * advancements are copied where a newer game reads them, each reference has to name the item the
 * target calls it, or the file fails to parse.
 */
class LegacyItemFlatteningTest {

    private static final MigrationContext.Loader NEOFORGE = MigrationContext.Loader.NEOFORGE;

    /** Runs the layout step and the two 1.12 converters, as the standard list does. */
    private static MapResourceTree convert(String source, String target, String... namesAndContents)
            throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(namesAndContents));
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(NEOFORGE).declaredNamespaces(Set.of("mod")).build()
                .forRun(ModResourceMigrations.rootNamespaces(tree.names()));
        for (ModResourceConverter converter : List.of(new ModDataLayoutMigrator(),
                new LegacyForgeRecipeMigrator(), new LegacyItemFlattening())) {
            if (converter.appliesTo(context)) converter.apply(tree, context);
        }
        return tree;
    }

    /** The data folder a target reads: plural before 1.21, singular from 1.21. */
    private static String data(String target, String kind, String path) {
        boolean singular = RetromodVersion.compareMcVersions(target, "1.21") >= 0;
        return "data/mod/" + (singular ? kind : kind + "s") + "/" + path;
    }

    private static JsonObject json(MapResourceTree tree, String name) throws IOException {
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject();
    }

    private static String shapeless(String ingredient, String result) {
        return "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[" + ingredient + "],"
                + "\"result\":" + result + "}";
    }

    /** A 1.12 item reference: an id and, when given, a data value. */
    private static String stack(String id, int data) {
        return "{\"item\":\"" + id + "\",\"data\":" + data + "}";
    }

    private static String stack(String id) {
        return "{\"item\":\"" + id + "\"}";
    }

    private static JsonElement firstIngredient(MapResourceTree tree, String name) throws IOException {
        return json(tree, name).getAsJsonArray("ingredients").get(0);
    }

    private static JsonObject item(String id) {
        JsonObject item = new JsonObject();
        item.addProperty("item", id);
        return item;
    }

    @Test
    @DisplayName("Wool with data 14 becomes red wool")
    void woolValueBecomesItsColor() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1", "assets/mod/recipes/rug.json",
                shapeless(stack("minecraft:wool", 14), stack("mod:rug")));

        assertEquals(item("minecraft:red_wool"), firstIngredient(tree, "data/mod/recipe/rug.json"));
        assertTrue(text(tree.read("assets/mod/recipes/rug.json")).contains("\"data\":14"),
                "the original is left as the mod shipped it");
    }

    @Test
    @DisplayName("Any log becomes the logs tag, and any dye the list of the sixteen dyes")
    void wildcardBecomesTheFamily() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1",
                "assets/mod/recipes/torch.json", shapeless(stack("minecraft:log", 32767),
                        stack("mod:torch")),
                "assets/mod/recipes/paint.json", shapeless(stack("minecraft:dye", 32767),
                        stack("mod:paint")));

        JsonObject logs = new JsonObject();
        logs.addProperty("tag", "minecraft:logs");
        assertEquals(logs, firstIngredient(tree, "data/mod/recipe/torch.json"));
        JsonArray dyes = firstIngredient(tree, "data/mod/recipe/paint.json").getAsJsonArray();
        assertEquals(16, dyes.size(), "no vanilla tag holds exactly the 1.12 dyes");
        assertTrue(dyes.contains(item("minecraft:lapis_lazuli")));
        assertTrue(dyes.contains(item("minecraft:red_dye")), "1.14 renamed rose_red");
    }

    @Test
    @DisplayName("In a list of alternatives a family joins item by item, since a list cannot hold a tag")
    void wildcardInAListIsSpliced() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1", "assets/mod/recipes/fuel.json",
                shapeless("[{\"item\":\"minecraft:coal\",\"data\":32767},{\"item\":\"minecraft:stick\"}]",
                        stack("mod:fuel")));

        JsonArray alternatives = firstIngredient(tree, "data/mod/recipe/fuel.json").getAsJsonArray();
        assertEquals(List.of(item("minecraft:coal"), item("minecraft:charcoal"), item("minecraft:stick")),
                alternatives.asList());
    }

    @Test
    @DisplayName("A dye result with data 4 becomes lapis lazuli and keeps its count")
    void resultValueBecomesItsItem() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1", "assets/mod/recipes/lapis.json",
                shapeless(stack("mod:blue_dust"), "{\"item\":\"minecraft:dye\",\"data\":4,\"count\":2}"));

        JsonObject result = json(tree, "data/mod/recipe/lapis.json").getAsJsonObject("result");
        assertEquals("minecraft:lapis_lazuli", result.get("item").getAsString());
        assertEquals(2, result.get("count").getAsInt());
        assertFalse(result.has("data"));
    }

    @Test
    @DisplayName("Coal with data 1 is charcoal, and coal without one is coal")
    void coalValueOne() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1",
                "assets/mod/recipes/a.json", shapeless(stack("minecraft:coal", 1), stack("mod:a")),
                "assets/mod/recipes/b.json", shapeless(stack("minecraft:coal"), stack("mod:b")));

        assertEquals(item("minecraft:charcoal"), firstIngredient(tree, "data/mod/recipe/a.json"));
        assertEquals(item("minecraft:coal"), firstIngredient(tree, "data/mod/recipe/b.json"),
                "1.12's recipe parser read a missing value as 0");
    }

    @Test
    @DisplayName("A set_data drop of fish folds into the cod id, keeping its other functions")
    void setDataFoldsIntoTheId() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1", "assets/mod/loot_tables/chests/hut.json",
                "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"item\",\"name\":\"minecraft:fish\","
                        + "\"weight\":1,\"functions\":[{\"function\":\"set_data\",\"data\":0},"
                        + "{\"function\":\"set_count\",\"count\":{\"min\":1,\"max\":3}}]}]}]}");

        JsonObject entry = json(tree, "data/mod/loot_table/chests/hut.json").getAsJsonArray("pools")
                .get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("minecraft:cod", entry.get("name").getAsString());
        assertEquals(1, entry.getAsJsonArray("functions").size());
        assertEquals("set_count", entry.getAsJsonArray("functions").get(0).getAsJsonObject()
                .get("function").getAsString());
    }

    @Test
    @DisplayName("A bare recipe id names the mod's own item, as Forge 1.12 read it, even when it matches a vanilla id")
    void bareRecipeIdsGetTheModNamespace() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1",
                "assets/mod/models/item/mossy_plate.json", "{\"parent\":\"item/generated\"}",
                "assets/mod/recipes/plate.json", shapeless(stack("stick"), stack("mossy_plate")),
                "assets/mod/recipes/sticks.json", shapeless(stack("mod:dust"),
                        "{\"item\":\"Stick\",\"count\":4}"));

        JsonObject plate = json(tree, "data/mod/recipe/plate.json");
        assertEquals("mod:mossy_plate", plate.getAsJsonObject("result").get("item").getAsString());
        assertEquals(item("mod:stick"), plate.getAsJsonArray("ingredients").get(0),
                "Forge 1.12 prefixed the mod id whether or not the mod had the item, never minecraft");
        assertEquals("mod:stick", json(tree, "data/mod/recipe/sticks.json").getAsJsonObject("result")
                .get("item").getAsString(), "a result without a model of its name is the mod's too");
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.13", "1.21.1"})
    @DisplayName("A 1.12 pumpkin ingredient takes either 1.13 pumpkin, and a dropped or made pumpkin is uncarved")
    void pumpkinFollowsVanillaRecipes(String target) throws IOException {
        MapResourceTree tree = convert("1.12.2", target,
                "assets/mod/recipes/pie.json", shapeless(stack("minecraft:pumpkin"), stack("mod:pie")),
                "assets/mod/recipes/back.json", shapeless(stack("mod:pie"), stack("minecraft:pumpkin")),
                "assets/mod/loot_tables/patch.json", "{\"pools\":[{\"rolls\":1,\"entries\":"
                        + "[{\"type\":\"item\",\"name\":\"minecraft:pumpkin\"}]}]}");

        assertEquals(JsonParser.parseString("[{\"item\":\"minecraft:pumpkin\"},"
                        + "{\"item\":\"minecraft:carved_pumpkin\"}]"),
                firstIngredient(tree, data(target, "recipe", "pie.json")),
                "1.13's pumpkin_pie takes the uncarved pumpkin and its jack_o_lantern the carved one");
        assertEquals("minecraft:pumpkin", json(tree, data(target, "recipe", "back.json"))
                .getAsJsonObject("result").get("item").getAsString());
        assertEquals("minecraft:pumpkin", json(tree, data(target, "loot_table", "patch.json"))
                .getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0)
                .getAsJsonObject().get("name").getAsString());
    }

    @Test
    @DisplayName("A data value the 1.13 table does not know is left, so the gate keeps the copy out")
    void unknownValueIsLeft() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1", "assets/mod/recipes/x.json",
                shapeless(stack("minecraft:wool", 16), stack("mod:x")));

        assertTrue(DataLoadGate.namesItemMetadata(json(tree, "data/mod/recipe/x.json")));
    }

    @Test
    @DisplayName("Any value of a mod's own item is that item, and one value stays for the gate")
    void modItemValues() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1",
                "assets/mod/recipes/any.json", shapeless(stack("mod:gem", 32767), stack("mod:a")),
                "assets/mod/recipes/one.json", shapeless(stack("mod:gem", 3), stack("mod:b")));

        assertEquals(item("mod:gem"), firstIngredient(tree, "data/mod/recipe/any.json"));
        assertTrue(DataLoadGate.namesItemMetadata(json(tree, "data/mod/recipe/one.json")),
                "the mod registers one item for every value, so no id says which one");
    }

    @ParameterizedTest(name = "on {0}")
    @CsvSource({"1.20.1, minecraft:grass", "1.20.2, minecraft:grass", "1.20.3, minecraft:short_grass",
            "1.21.1, minecraft:short_grass", "26.3, minecraft:short_grass"})
    @DisplayName("Tall grass value 1 follows the 1.20.3 rename, and 1.12 grass is always the grass block")
    void grassFollowsItsTarget(String target, String shortGrass) throws IOException {
        MapResourceTree tree = convert("1.12.2", target,
                "assets/mod/recipes/hay.json", shapeless(stack("minecraft:tallgrass", 1), stack("mod:hay")),
                "assets/mod/recipes/turf.json", shapeless(stack("minecraft:grass", 0), stack("mod:turf")));

        assertEquals(item(shortGrass), firstIngredient(tree, data(target, "recipe", "hay.json")));
        assertEquals(item("minecraft:grass_block"), firstIngredient(tree, data(target, "recipe", "turf.json")));
    }

    @ParameterizedTest(name = "on {0}")
    @CsvSource({"1.13, minecraft:rose_red, minecraft:stone_slab",
            "1.14, minecraft:red_dye, minecraft:smooth_stone_slab",
            "26.3, minecraft:red_dye, minecraft:smooth_stone_slab"})
    @DisplayName("Ids 1.14 renamed are carried to the name each target reads")
    void laterRenamesFollowTheTarget(String target, String redDye, String smoothSlab) throws IOException {
        MapResourceTree tree = convert("1.12.2", target,
                "assets/mod/loot_tables/a.json", "{\"pools\":[{\"rolls\":1,\"entries\":["
                        + "{\"type\":\"item\",\"name\":\"minecraft:dye\",\"functions\":"
                        + "[{\"function\":\"set_data\",\"data\":1}]},"
                        + "{\"type\":\"item\",\"name\":\"minecraft:stone_slab\"}]}]}");

        JsonArray entries = json(tree, data(target, "loot_table", "a.json")).getAsJsonArray("pools")
                .get(0).getAsJsonObject().getAsJsonArray("entries");
        assertEquals(redDye, entries.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(smoothSlab, entries.get(1).getAsJsonObject().get("name").getAsString(),
                "1.12's stone slab is the smooth one; 1.14 gave its old id to a new plain slab");
    }

    @ParameterizedTest(name = "on {0}")
    @CsvSource({"1.13, minecraft:zombie_pigman", "1.16, minecraft:zombified_piglin",
            "26.3, minecraft:zombified_piglin"})
    @DisplayName("Entity ids in advancement criteria are renamed for the target")
    void entityIdsInAdvancements(String target, String pigman) throws IOException {
        MapResourceTree tree = convert("1.12.2", target, "assets/mod/advancements/hunt.json",
                "{\"criteria\":{\"golem\":{\"trigger\":\"minecraft:player_killed_entity\",\"conditions\":"
                        + "{\"entity\":{\"type\":\"villager_golem\"}}},\"pigman\":{\"trigger\":"
                        + "\"minecraft:player_killed_entity\",\"conditions\":{\"entity\":{\"type\":"
                        + "\"minecraft:zombie_pigman\"}}}}}");

        JsonObject criteria = json(tree, data(target, "advancement", "hunt.json")).getAsJsonObject("criteria");
        assertEquals("minecraft:iron_golem", criteria.getAsJsonObject("golem").getAsJsonObject("conditions")
                .getAsJsonObject("entity").get("type").getAsString());
        assertEquals(pigman, criteria.getAsJsonObject("pigman").getAsJsonObject("conditions")
                .getAsJsonObject("entity").get("type").getAsString());
    }

    @ParameterizedTest(name = "on {0}")
    @CsvSource({"1.16, item", "1.21.1, items", "26.3, items"})
    @DisplayName("An advancement icon with a value and a predicate on a renamed block are rewritten")
    void advancementIconAndPredicate(String target, String field) throws IOException {
        MapResourceTree tree = convert("1.12.2", target, "assets/mod/advancements/slime.json",
                "{\"display\":{\"icon\":{\"item\":\"minecraft:skull\",\"data\":4}},\"criteria\":{\"has\":"
                        + "{\"trigger\":\"minecraft:inventory_changed\",\"conditions\":{\"items\":"
                        + "[{\"item\":\"minecraft:slime\"}]}}}}");

        JsonObject advancement = json(tree, data(target, "advancement", "slime.json"));
        assertEquals("minecraft:creeper_head", advancement.getAsJsonObject("display")
                .getAsJsonObject("icon").get("item").getAsString());
        JsonObject predicate = advancement.getAsJsonObject("criteria").getAsJsonObject("has")
                .getAsJsonObject("conditions").getAsJsonArray("items").get(0).getAsJsonObject();
        JsonElement expected = field.equals("items") ? JsonParser.parseString("[\"minecraft:slime_block\"]")
                : new JsonPrimitive("minecraft:slime_block");
        assertEquals(expected, predicate.get(field),
                "1.17 hosts read a list and ignore a single item, which would match every item");
        assertEquals(1, predicate.size());
    }

    @Test
    @DisplayName("A predicate on any value of a family lists every item of it")
    void familyPredicate() throws IOException {
        MapResourceTree tree = convert("1.12.2", "1.21.1", "assets/mod/advancements/coal.json",
                "{\"criteria\":{\"has\":{\"trigger\":\"minecraft:inventory_changed\",\"conditions\":"
                        + "{\"items\":[{\"item\":\"minecraft:coal\",\"data\":32767,\"count\":{\"min\":2}}]}}}}");

        JsonObject predicate = json(tree, data("1.21.1", "advancement", "coal.json")).getAsJsonObject("criteria")
                .getAsJsonObject("has").getAsJsonObject("conditions").getAsJsonArray("items").get(0)
                .getAsJsonObject();
        assertEquals(JsonParser.parseString("[\"minecraft:coal\",\"minecraft:charcoal\"]"), predicate.get("items"));
        assertFalse(predicate.has("data"));
        assertTrue(predicate.has("count"), "the predicate's other fields are kept");
    }

    @Test
    @DisplayName("On 26.3 a flattened recipe comes through every later step and loads")
    void endToEndOn26_3() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/mod/lang/en_us.lang", "item.rug.name=Rug",
                "assets/mod/recipes/rug.json", "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"WL\"],"
                        + "\"key\":{\"W\":{\"item\":\"minecraft:wool\",\"data\":14},"
                        + "\"L\":{\"item\":\"minecraft:log\",\"data\":32767}},"
                        + "\"result\":{\"item\":\"minecraft:dye\",\"data\":4,\"count\":2}}"));
        MigrationContext context = MigrationContext.builder("26.3").sourceMcVersion("1.12.2")
                .targetLoader(NEOFORGE).declaredNamespaces(Set.of("mod")).build();

        ModResourceMigrations.standard().run(tree, context);

        assertTrue(tree.exists("data/mod/recipe/rug.json"), "the load gate kept the copy");
        JsonObject recipe = json(tree, "data/mod/recipe/rug.json");
        assertEquals("minecraft:red_wool", recipe.getAsJsonObject("key").get("W").getAsString());
        assertEquals("#minecraft:logs", recipe.getAsJsonObject("key").get("L").getAsString());
        assertEquals("minecraft:lapis_lazuli", recipe.getAsJsonObject("result").get("id").getAsString());
    }

    @Test
    @DisplayName("On 26.3 a pumpkin recipe loads with both pumpkins in one ingredient")
    void pumpkinEndToEndOn26_3() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/mod/lang/en_us.lang", "item.pie.name=Pie",
                "assets/mod/recipes/pie.json", shapeless(stack("minecraft:pumpkin"), stack("mod:pie"))));
        MigrationContext context = MigrationContext.builder("26.3").sourceMcVersion("1.12.2")
                .targetLoader(NEOFORGE).declaredNamespaces(Set.of("mod")).build();

        ModResourceMigrations.standard().run(tree, context);

        assertTrue(tree.exists("data/mod/recipe/pie.json"), "the load gate kept the copy");
        assertEquals(JsonParser.parseString("[\"minecraft:pumpkin\",\"minecraft:carved_pumpkin\"]"),
                firstIngredient(tree, "data/mod/recipe/pie.json"));
    }

    @Test
    @DisplayName("A 1.12 mod's own data/ copy is already 1.13 data and is not flattened again")
    void jarDataFolderIsLeftAlone() throws IOException {
        String modern = shapeless(stack("minecraft:grass"), stack("mod:hay"));
        MapResourceTree tree = convert("1.12.2", "1.21.1",
                "assets/mod/recipes/hay.json", shapeless(stack("minecraft:tallgrass", 1), stack("mod:hay")),
                "data/mod/recipes/hay.json", modern);

        assertFalse(text(tree.read("data/mod/recipe/hay.json")).contains("grass_block"),
                "in 1.13 data minecraft:grass is the plant, not the 1.12 grass block");
    }

    @Test
    @DisplayName("A 1.13 source and a host that still reads data values are left alone")
    void olderShapesAreLeftAlone() throws IOException {
        String recipe = shapeless(stack("minecraft:wool", 14), stack("mod:rug"));
        LegacyItemFlattening flattening = new LegacyItemFlattening();

        assertFalse(flattening.appliesTo(MigrationContext.builder("1.12.2").sourceMcVersion("1.12.2").build()),
                "1.12 itself reads data values");
        assertFalse(flattening.appliesTo(MigrationContext.builder("26.3").sourceMcVersion("1.13").build()));
        assertFalse(flattening.appliesTo(MigrationContext.builder("26.3").build()),
                "an unknown source is never taken as old");
        MapResourceTree tree = convert("1.13.2", "1.21.1", "data/mod/recipes/rug.json", recipe);
        assertTrue(text(tree.read("data/mod/recipe/rug.json")).contains("\"data\":14"));
    }

    @Test
    @DisplayName("Running the migration twice changes nothing more")
    void idempotent() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries("assets/mod/recipes/rug.json",
                shapeless(stack("minecraft:wool", 14), stack("mod:rug"))));
        MigrationContext context = MigrationContext.builder("26.3").sourceMcVersion("1.12.2")
                .targetLoader(NEOFORGE).declaredNamespaces(Set.of("mod")).build();
        ModResourceMigrations.standard().run(tree, context);
        Map<String, String> once = gameResourceDigests(tree.entries());

        ModResourceMigrations.standard().run(tree, context);

        assertEquals(once, gameResourceDigests(tree.entries()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"flattened", "renamed", "entities", "later items", "later entities", "families",
            "also accepted"})
    @DisplayName("The id table loads every kind of row, with no id listed twice")
    void tableLoads(String kind) {
        Map<String, Integer> expected = Map.of("flattened", 320, "renamed", 8, "entities", 12,
                "later items", 7, "later entities", 1, "families", 9, "also accepted", 1);

        assertEquals(expected.get(kind), LegacyItemIds.rowCounts().get(kind));
    }
}
