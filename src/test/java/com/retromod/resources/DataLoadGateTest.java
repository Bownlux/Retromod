/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Copied data reaches a loader that never saw it. On 26.3, which loads recipes, loot tables and
 * advancements with the registries, one bad copy stops the world, so the gate keeps a copy only
 * when it can show the host reads it. The original always stays where it was.
 */
class DataLoadGateTest {

    private static final String SHAPELESS = "{\"type\":\"minecraft:crafting_shapeless\","
            + "\"ingredients\":[\"%s\"],\"result\":{\"id\":\"%s\",\"count\":1}}";

    /** Runs the layout step and then the gate, as the orchestrator does. */
    private static MapResourceTree run(Map<String, byte[]> entries, String source, String target,
                                       MigrationContext.Loader loader, Set<String> declared)
            throws IOException {
        MapResourceTree tree = new MapResourceTree(entries);
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(loader).declaredNamespaces(declared).build()
                .forRun(ModResourceMigrations.rootNamespaces(tree.names()));
        new ModDataLayoutMigrator().apply(tree, context);
        DataLoadGate gate = new DataLoadGate();
        if (gate.appliesTo(context)) gate.apply(tree, context);
        return tree;
    }

    private static MapResourceTree runNeoForge(String target, String... namesAndContents)
            throws IOException {
        return run(entries(namesAndContents), "1.20.1", target, MigrationContext.Loader.NEOFORGE,
                Set.of("mod"));
    }

    @Test
    @DisplayName("A copied recipe in the current shape loads on 26.3")
    void keepsACheckedRecipe() throws IOException {
        MapResourceTree tree = runNeoForge("26.3", "data/mod/recipes/ruby.json",
                String.format(SHAPELESS, "minecraft:stick", "mod:ruby"));

        assertTrue(tree.exists("data/mod/recipe/ruby.json"));
    }

    @Test
    @DisplayName("A Forge recipe type is kept out of 26.3 and left in on 1.21.1")
    void forgeRecipeTypeIsGatedOn26_3() throws IOException {
        String oreShaped = "{\"type\":\"forge:ore_shaped\",\"pattern\":[\"#\"],"
                + "\"key\":{\"#\":{\"ore\":\"ingotIron\"}},\"result\":{\"item\":\"mod:ruby\"}}";

        assertFalse(runNeoForge("26.3", "data/mod/recipes/x.json", oreShaped)
                .exists("data/mod/recipe/x.json"), "26.3 would stop the world on it");
        assertTrue(runNeoForge("1.21.1", "data/mod/recipes/x.json", oreShaped)
                .exists("data/mod/recipe/x.json"), "1.21.1 skips it with a log line");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.20.1", "1.21.1", "26.3"})
    @DisplayName("An item named by pre-1.13 metadata is kept out on every host")
    void itemMetadataIsAlwaysKeptOut(String target) throws IOException {
        String withData = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":"
                + "[{\"item\":\"mod:dust\",\"data\":3}],\"result\":{\"item\":\"mod:ruby\"}}";
        MapResourceTree tree = run(entries("assets/mod/recipes/x.json", withData), "1.12.2",
                target, MigrationContext.Loader.FORGE, Set.of("mod"));

        assertFalse(tree.names().stream().anyMatch(name -> name.startsWith("data/")),
                "it would parse and then silently match the wrong item");
        assertTrue(tree.exists("assets/mod/recipes/x.json"), "the original stays");
    }

    @Test
    @DisplayName("An uppercase result id is kept out of 26.3")
    void uppercaseIdIsKeptOut() throws IOException {
        MapResourceTree tree = runNeoForge("26.3", "data/mod/recipes/compass.json",
                String.format(SHAPELESS, "minecraft:compass", "mod:NaturesCompass"));

        assertFalse(tree.exists("data/mod/recipe/compass.json"));
    }

    @Test
    @DisplayName("A recipe naming another mod's item gets the host loader's mod-loaded condition")
    void foreignItemsGetALoadCondition() throws IOException {
        String recipe = String.format(SHAPELESS, "botania:manasteel_ingot", "mod:shield");

        JsonObject neo = JsonParser.parseString(text(runNeoForge("26.3",
                "data/mod/recipes/shield.json", recipe).read("data/mod/recipe/shield.json")))
                .getAsJsonObject();
        JsonObject condition = neo.getAsJsonArray("neoforge:conditions").get(0).getAsJsonObject();
        assertEquals("neoforge:mod_loaded", condition.get("type").getAsString());
        assertEquals("botania", condition.get("modid").getAsString());

        JsonObject fabric = JsonParser.parseString(text(run(entries(
                "data/mod/recipes/shield.json", recipe), "1.20.1", "26.3",
                MigrationContext.Loader.FABRIC, Set.of("mod")).read("data/mod/recipe/shield.json")))
                .getAsJsonObject();
        JsonObject fabricCondition = fabric.getAsJsonArray("fabric:load_conditions").get(0)
                .getAsJsonObject();
        assertEquals("fabric:all_mods_loaded", fabricCondition.get("condition").getAsString());
        assertEquals("botania", fabricCondition.getAsJsonArray("values").get(0).getAsString());

        assertFalse(run(entries("data/mod/recipes/shield.json", recipe), "1.20.1", "26.3",
                MigrationContext.Loader.FORGE, Set.of("mod")).exists("data/mod/recipe/shield.json"),
                "with no condition Retromod can write, the copy stays out");
    }

    @Test
    @DisplayName("A data folder for another mod does not make that mod's items this mod's own")
    void dataFolderIsNotOwnership() throws IOException {
        String recipe = String.format(SHAPELESS, "create:zinc_ingot", "create:zinc_block");
        MapResourceTree tree = runNeoForge("26.3", "data/create/recipes/zinc.json", recipe);

        JsonObject copy = JsonParser.parseString(text(tree.read("data/create/recipe/zinc.json")))
                .getAsJsonObject();
        assertTrue(copy.has("neoforge:conditions"), "compat recipes still need create installed");
    }

    @Test
    @DisplayName("A converted loot table and advancement load on 26.3, and an old shape stays out")
    void checkedLootTablesAndAdvancements() throws IOException {
        String loot = "{\"type\":\"minecraft:block\",\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":"
                + "\"minecraft:item\",\"name\":\"mod:ruby\",\"modifier\":{\"type\":\"minecraft:set_count\","
                + "\"count\":{\"type\":\"minecraft:uniform\",\"min\":1,\"max\":2}}}]}]}";
        String advancement = "{\"parent\":\"minecraft:story/root\",\"criteria\":{\"has\":{\"trigger\":"
                + "\"minecraft:inventory_changed\",\"conditions\":{\"items\":[{\"items\":\"mod:ruby\"}]}}},"
                + "\"rewards\":{\"recipes\":[\"mod:ruby_block\"]}}";
        String setNbt = loot.replace("\"type\":\"minecraft:set_count\",\"count\":{\"type\":\"minecraft:uniform\","
                + "\"min\":1,\"max\":2}", "\"type\":\"minecraft:set_nbt\",\"tag\":\"{CanDestroy:[]}\"");

        MapResourceTree modern = runNeoForge("26.3",
                "data/mod/loot_tables/blocks/ruby_ore.json", loot,
                "data/mod/loot_tables/blocks/old.json", setNbt,
                "data/mod/recipes/ruby_block.json", String.format(SHAPELESS, "mod:ruby", "mod:ruby_block"),
                "data/mod/advancements/ruby.json", advancement);

        assertTrue(modern.exists("data/mod/loot_table/blocks/ruby_ore.json"));
        assertTrue(modern.exists("data/mod/advancement/ruby.json"));
        assertFalse(modern.exists("data/mod/loot_table/blocks/old.json"), "26.3 has no set_nbt");

        MapResourceTree older = runNeoForge("1.21.1", "data/mod/loot_tables/blocks/old.json", setNbt);
        assertTrue(older.exists("data/mod/loot_table/blocks/old.json"), "1.21.1 skips it with a log line");
    }

    @Test
    @DisplayName("An MCreator mob table with a stray function name on its entries loads on 26.3")
    void strayFunctionNameOnAnEntryLoads() throws IOException {
        // Cake Cow (#346): MCreator wrote "function" beside each smelt-on-fire drop's "type".
        String mob = "{\"type\":\"minecraft:entity\",\"pools\":[{\"rolls\":{\"min\":1,\"max\":3},"
                + "\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:beef\",\"weight\":1,"
                + "\"functions\":[{\"function\":\"set_count\",\"count\":{\"min\":1,\"max\":1}}],"
                + "\"function\":\"minecraft:furnace_smelt\",\"conditions\":[{\"condition\":"
                + "\"minecraft:entity_properties\",\"predicate\":{\"flags\":{\"is_on_fire\":false}},"
                + "\"entity\":\"this\"}]}]}]}";

        MapResourceTree tree = new MapResourceTree(entries("data/mod/loot_tables/entities/cake_cow.json", mob));
        MigrationContext context = MigrationContext.builder("26.3").sourceMcVersion("1.20.1")
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(Set.of("mod")).build();

        ModResourceMigrations.standard().run(tree, context);

        assertTrue(tree.exists("data/mod/loot_table/entities/cake_cow.json"),
                "the game never read that key, so the table must not be kept out for it");
        String copy = text(tree.read("data/mod/loot_table/entities/cake_cow.json"));
        assertFalse(copy.contains("\"function\""), copy);
        assertTrue(copy.contains("\"modifier\""), "the count still applies: " + copy);
    }

    /**
     * The criteria of vanilla 26.2 advancements whose trigger fields 26.3 renamed: slide_down_block,
     * enter_block, crafter_recipe_crafted, recipe_crafted, bee_nest_destroyed,
     * player_generates_container_loot and recipe_unlocked.
     */
    static final String VANILLA_26_2_CRITERIA = "{\"criteria\":{"
            + "\"honey_block_slide\":{\"conditions\":{\"block\":\"minecraft:honey_block\"},"
            + "\"trigger\":\"minecraft:slide_down_block\"},"
            + "\"entered_end_gateway\":{\"conditions\":{\"block\":\"minecraft:end_gateway\"},"
            + "\"trigger\":\"minecraft:enter_block\"},"
            + "\"crafter_crafted_crafter\":{\"conditions\":{\"recipe_id\":\"minecraft:crafter\"},"
            + "\"trigger\":\"minecraft:crafter_recipe_crafted\"},"
            + "\"pot_crafted_using_only_sherds\":{\"conditions\":{\"ingredients\":[{\"items\":"
            + "\"#minecraft:decorated_pot_sherds\"}],\"recipe_id\":\"minecraft:decorated_pot\"},"
            + "\"trigger\":\"minecraft:recipe_crafted\"},"
            + "\"silk_touch_nest\":{\"conditions\":{\"block\":\"minecraft:bee_nest\",\"item\":{\"predicates\":"
            + "{\"minecraft:enchantments\":[{\"enchantments\":\"minecraft:silk_touch\",\"levels\":{\"min\":1}}]}},"
            + "\"num_bees_inside\":3},\"trigger\":\"minecraft:bee_nest_destroyed\"},"
            + "\"loot_bastion_bridge\":{\"conditions\":{\"loot_table\":\"minecraft:chests/bastion_bridge\"},"
            + "\"trigger\":\"minecraft:player_generates_container_loot\"},"
            + "\"has_the_recipe\":{\"conditions\":{\"recipe\":\"minecraft:crafter\"},"
            + "\"trigger\":\"minecraft:recipe_unlocked\"}}}";

    /** The same criteria as vanilla 26.3 writes them. */
    static final String VANILLA_26_3_CRITERIA = VANILLA_26_2_CRITERIA
            .replace("\"block\":", "\"blocks\":").replace("\"recipe_id\":", "\"recipes\":")
            .replace("\"loot_table\":", "\"loot_tables\":").replace("\"recipe\":", "\"recipes\":");

    @Test
    @DisplayName("Advancements load on 26.3 with its renamed trigger fields, and each of 26.2's names stays out")
    void renamedTriggerFieldsOn26_3() throws IOException {
        MapResourceTree current = runNeoForge("26.3", "data/mod/advancements/current.json", VANILLA_26_3_CRITERIA);
        assertTrue(current.exists("data/mod/advancement/current.json"), "26.3's own trigger fields");

        JsonObject criteria = JsonParser.parseString(VANILLA_26_2_CRITERIA).getAsJsonObject()
                .getAsJsonObject("criteria");
        for (String name : criteria.keySet()) {
            JsonObject single = new JsonObject();
            JsonObject only = new JsonObject();
            only.add(name, criteria.get(name));
            single.add("criteria", only);
            MapResourceTree old = runNeoForge("26.3", "data/mod/advancements/old.json", single.toString());
            assertFalse(old.exists("data/mod/advancement/old.json"),
                    name + ": 26.3 requires or reads only the new field");
        }
    }

    @Test
    @DisplayName("A 1.20.1 advancement converted on the way loads on 26.3 with the renamed trigger fields")
    void convertedTriggerFieldsThroughEveryStep() throws IOException {
        String advancement = "{\"criteria\":{\"crafted\":{\"trigger\":\"minecraft:recipe_crafted\",\"conditions\":"
                + "{\"recipe_id\":\"mod:ruby_block\"}},\"swim\":{\"trigger\":\"minecraft:enter_block\","
                + "\"conditions\":{\"block\":\"minecraft:water\"}}}}";
        MapResourceTree tree = new MapResourceTree(entries(
                "data/mod/advancements/a.json", advancement,
                "data/mod/recipes/ruby_block.json", String.format(SHAPELESS, "mod:ruby", "mod:ruby_block")));
        MigrationContext context = MigrationContext.builder("26.3").sourceMcVersion("1.20.1")
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(Set.of("mod")).build();

        ModResourceMigrations.standard().run(tree, context);

        JsonObject conditions = JsonParser.parseString(text(tree.read("data/mod/advancement/a.json")))
                .getAsJsonObject().getAsJsonObject("criteria");
        assertEquals("mod:ruby_block", conditions.getAsJsonObject("crafted").getAsJsonObject("conditions")
                .get("recipes").getAsString());
        assertEquals("minecraft:water", conditions.getAsJsonObject("swim").getAsJsonObject("conditions")
                .get("blocks").getAsString(), "the old key would match any block");
    }

    @Test
    @DisplayName("1.12's grass becomes grass_block, never short_grass, so its recipe loads on every host")
    void twelveGrassThroughEveryStep() throws IOException {
        String recipe = "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"##\",\"##\"],\"key\":{\"#\":"
                + "{\"item\":\"minecraft:dirt\"}},\"result\":{\"item\":\"minecraft:grass\",\"count\":4}}";
        for (String target : new String[] {"1.21.1", "26.3"}) {
            MapResourceTree tree = new MapResourceTree(entries("assets/mod/recipes/grass_block.json", recipe));
            MigrationContext context = MigrationContext.builder(target).sourceMcVersion("1.12.2")
                    .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(Set.of("mod")).build();

            ModResourceMigrations.standard().run(tree, context);

            String copy = "data/mod/recipe/grass_block.json";
            assertTrue(tree.exists(copy), target + ": the flattened recipe names an item the host has");
            String migrated = text(tree.read(copy));
            assertFalse(migrated.contains("short_grass"), target + ": " + migrated);
            assertTrue(migrated.contains("\"minecraft:grass_block\""),
                    target + ": 1.12 grass with no data value is the grass block: " + migrated);
        }
    }

    /** A loot table whose int fields all hold binomial providers, which 26.3 reads. */
    private static final String BINOMIAL = "{\"type\":\"minecraft:binomial\",\"n\":3,\"p\":0.5}";
    private static final String INT_BINOMIALS = "{\"pools\":[{\"rolls\":" + BINOMIAL + ",\"entries\":[{\"type\":"
            + "\"minecraft:item\",\"name\":\"mod:ruby\",\"modifier\":[{\"type\":\"minecraft:set_count\",\"count\":"
            + BINOMIAL + "},{\"type\":\"minecraft:limit_count\",\"limit\":{\"min\":" + BINOMIAL + "}},"
            + "{\"type\":\"minecraft:enchant_with_levels\",\"levels\":" + BINOMIAL + "}]}]}]}";

    @ParameterizedTest
    @ValueSource(strings = {"bonus_rolls", "set_damage", "enchanted_count_increase", "random_chance", "uniform"})
    @DisplayName("26.3 reads binomial only where it reads an int, so one in a float field keeps the table out")
    void binomialOnlyInIntFields(String floatField) throws IOException {
        String floatBinomial = switch (floatField) {
            case "bonus_rolls" -> INT_BINOMIALS.replace("{\"rolls\":", "{\"bonus_rolls\":" + BINOMIAL + ",\"rolls\":");
            case "set_damage" -> INT_BINOMIALS.replace("\"modifier\":[",
                    "\"modifier\":[{\"type\":\"minecraft:set_damage\",\"damage\":" + BINOMIAL + "},");
            case "enchanted_count_increase" -> INT_BINOMIALS.replace("\"modifier\":[",
                    "\"modifier\":[{\"type\":\"minecraft:enchanted_count_increase\",\"enchantment\":"
                            + "\"minecraft:looting\",\"count\":" + BINOMIAL + "},");
            case "random_chance" -> INT_BINOMIALS.replace("{\"rolls\":",
                    "{\"condition\":{\"type\":\"minecraft:random_chance\",\"chance\":" + BINOMIAL + "},\"rolls\":");
            default -> INT_BINOMIALS.replace("{\"rolls\":", "{\"bonus_rolls\":{\"type\":\"minecraft:uniform\","
                    + "\"min\":" + BINOMIAL + ",\"max\":2},\"rolls\":");
        };

        MapResourceTree tree = runNeoForge("26.3", "data/mod/loot_tables/blocks/ints.json", INT_BINOMIALS,
                "data/mod/loot_tables/blocks/floats.json", floatBinomial);

        assertTrue(tree.exists("data/mod/loot_table/blocks/ints.json"), "rolls, counts and levels are ints");
        assertFalse(tree.exists("data/mod/loot_table/blocks/floats.json"), floatField + " is a float field");
    }

    @Test
    @DisplayName("A copy naming a recipe the gate kept out goes too, since 26.3 binds the reference")
    void danglingReferencesStayOut() throws IOException {
        String advancement = "{\"criteria\":{\"has\":{\"trigger\":\"minecraft:impossible\"}},"
                + "\"rewards\":{\"recipes\":[\"mod:x\"]}}";
        String forgeRecipe = "{\"type\":\"forge:ore_shaped\",\"pattern\":[\"#\"],"
                + "\"key\":{\"#\":{\"ore\":\"ingotIron\"}},\"result\":{\"item\":\"mod:ruby\"}}";

        MapResourceTree tree = runNeoForge("26.3", "data/mod/recipes/x.json", forgeRecipe,
                "data/mod/advancements/x.json", advancement);

        assertFalse(tree.exists("data/mod/recipe/x.json"));
        assertFalse(tree.exists("data/mod/advancement/x.json"));
    }

    @Test
    @DisplayName("A copy naming a recipe or loot table of Minecraft's that 26.3 does not ship stays out")
    void missingVanillaReferencesStayOut() throws IOException {
        String nested = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:loot_table\","
                + "\"value\":\"%s\"}]}]}";
        String unlock = "{\"criteria\":{\"has\":{\"trigger\":\"minecraft:recipe_unlocked\","
                + "\"conditions\":{\"recipes\":\"%s\"}}}}";
        String containerLoot = "{\"criteria\":{\"looted\":{\"trigger\":\"minecraft:player_generates_container_loot\","
                + "\"conditions\":{\"loot_tables\":\"%s\"}}}}";

        MapResourceTree tree = runNeoForge("26.3",
                "data/mod/loot_tables/chests/dungeon.json", String.format(nested, "minecraft:chests/simple_dungeon"),
                "data/mod/loot_tables/chests/smithy.json", String.format(nested, "minecraft:chests/village_blacksmith"),
                "data/mod/advancements/table.json", String.format(unlock, "minecraft:crafting_table"),
                "data/mod/advancements/chain.json", String.format(unlock, "minecraft:chain"),
                "data/mod/advancements/weaponsmith.json",
                String.format(containerLoot, "minecraft:chests/village/village_weaponsmith"),
                "data/mod/advancements/blacksmith.json", String.format(containerLoot, "minecraft:chests/village_blacksmith"));

        assertTrue(tree.exists("data/mod/loot_table/chests/dungeon.json"));
        assertTrue(tree.exists("data/mod/advancement/table.json"));
        assertTrue(tree.exists("data/mod/advancement/weaponsmith.json"));
        assertFalse(tree.exists("data/mod/loot_table/chests/smithy.json"),
                "1.14 renamed the blacksmith chest, and 26.3 binds a nested table as a registry reference");
        assertFalse(tree.exists("data/mod/advancement/chain.json"), "1.21.9 renamed the chain recipe to iron_chain");
        assertFalse(tree.exists("data/mod/advancement/blacksmith.json"));
    }

    @Test
    @DisplayName("A copy naming a vanilla item that 26.3 no longer has stays out")
    void removedVanillaItemsStayOut() throws IOException {
        MapResourceTree tree = runNeoForge("26.3", "data/mod/recipes/fish.json",
                String.format(SHAPELESS, "minecraft:fish", "mod:sushi"));

        assertFalse(tree.exists("data/mod/recipe/fish.json"), "1.12's fish is cod since the Flattening");
    }

    @Test
    @DisplayName("Copied tag entries become optional, so one missing entry cannot empty a vanilla tag")
    void copiedTagEntriesAreOptional() throws IOException {
        MapResourceTree tree = runNeoForge("1.21.1", "data/minecraft/tags/blocks/mineable/pickaxe.json",
                "{\"values\":[\"mod:ruby_ore\",{\"id\":\"#mod:ores\"}]}");

        JsonObject tag = JsonParser.parseString(text(tree.read(
                "data/minecraft/tags/block/mineable/pickaxe.json"))).getAsJsonObject();
        for (var value : tag.getAsJsonArray("values")) {
            assertFalse(value.getAsJsonObject().get("required").getAsBoolean(), value.toString());
        }
        assertEquals("mod:ruby_ore", tag.getAsJsonArray("values").get(0).getAsJsonObject()
                .get("id").getAsString());
    }

    @Test
    @DisplayName("The gate does nothing when nothing was copied")
    void idleWithoutCopies() {
        MigrationContext context = MigrationContext.builder("26.3").build().forRun(List.of());

        assertFalse(new DataLoadGate().appliesTo(context));
    }
}
