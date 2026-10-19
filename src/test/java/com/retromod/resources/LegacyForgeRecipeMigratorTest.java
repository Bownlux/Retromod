/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Forge 1.12 recipes used the ore dictionary, Forge's own recipe types, {@code #NAME} constants
 * from {@code _constants.json}, and Forge conditions. No later loader reads any of it, so each
 * copied recipe is rewritten into vanilla types, tags and the target loader's conditions.
 */
class LegacyForgeRecipeMigratorTest {

    private static final String STICKS = "{\"type\":\"forge:ore_shaped\",\"pattern\":[\"S\",\"S\"],"
            + "\"key\":{\"S\":{\"type\":\"forge:ore_dict\",\"ore\":\"%s\"}},\"mirrored\":false,"
            + "\"result\":{\"item\":\"mod:pole\"}}";

    /** Runs the layout step and the two 1.12 converters, as the standard list does. */
    private static MapResourceTree convert(MigrationContext.Loader loader, String target,
                                           String... namesAndContents) throws IOException {
        return convert("1.12.2", loader, target, namesAndContents);
    }

    private static MapResourceTree convert(String source, MigrationContext.Loader loader, String target,
                                           String... namesAndContents) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(namesAndContents));
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(loader).declaredNamespaces(Set.of("mod")).build()
                .forRun(ModResourceMigrations.rootNamespaces(tree.names()));
        for (ModResourceConverter converter : List.of(new ModDataLayoutMigrator(),
                new LegacyForgeRecipeMigrator(), new LegacyItemFlattening())) {
            if (converter.appliesTo(context)) converter.apply(tree, context);
        }
        return tree;
    }

    private static JsonObject recipe(MapResourceTree tree, String target, String path) throws IOException {
        boolean singular = RetromodVersion.compareMcVersions(target, "1.21") >= 0;
        return JsonParser.parseString(text(tree.read("data/mod/" + (singular ? "recipe/" : "recipes/")
                + path))).getAsJsonObject();
    }

    private static JsonObject tag(String id) {
        JsonObject tag = new JsonObject();
        tag.addProperty("tag", id);
        return tag;
    }

    private static JsonObject condition(String typeField, String type, Object... fieldsAndValues) {
        JsonObject condition = new JsonObject();
        condition.addProperty(typeField, type);
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            Object value = fieldsAndValues[i + 1];
            if (value instanceof JsonElement element) {
                condition.add((String) fieldsAndValues[i], element);
            } else {
                condition.addProperty((String) fieldsAndValues[i], (String) value);
            }
        }
        return condition;
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"NEOFORGE, 1.21.1, c:rods/wooden", "NEOFORGE, 26.3, c:rods/wooden",
            "FORGE, 1.20.1, forge:rods/wooden", "FORGE, 1.21.1, forge:rods/wooden",
            "FORGE, 1.21.11, forge:rods/wooden", "FORGE, 26.1, c:rods/wooden",
            "FORGE, 26.2, c:rods/wooden", "FORGE, 26.3, c:rods/wooden", "FABRIC, 1.21.11, c:rods/wooden"})
    @DisplayName("An ore shaped recipe becomes a vanilla shaped recipe naming the tag its host reads")
    void oreShapedBecomesShaped(String loader, String target, String sticks) throws IOException {
        MapResourceTree tree = convert(MigrationContext.Loader.valueOf(loader), target,
                "assets/mod/recipes/pole.json", String.format(STICKS, "stickWood"));

        JsonObject recipe = recipe(tree, target, "pole.json");
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        assertFalse(recipe.has("mirrored"), "vanilla shaped recipes always match their mirror image");
        assertEquals(tag(sticks), recipe.getAsJsonObject("key").get("S"));
        assertFalse(recipe.keySet().stream().anyMatch(key -> key.contains("condition")),
                "the loader ships this tag itself, so it is never empty");
    }

    @ParameterizedTest(name = "Forge {0}")
    @CsvSource({"1.20.2, forge:ores/iron, forge:sand, forge:netherrack",
            "1.21.11, forge:ores/iron, forge:sand, forge:netherrack",
            "26.1, c:ores/iron, c:sands, c:netherracks", "26.3, c:ores/iron, c:sands, c:netherracks"})
    @DisplayName("Forge reads its own tag family through 1.21.11, which lacks these c: tags, and c: from 26.1")
    void forgeTagFamilyFollowsTheHost(String target, String ores, String sand, String netherrack)
            throws IOException {
        MapResourceTree tree = convert(MigrationContext.Loader.FORGE, target, "assets/mod/recipes/kiln.json",
                "{\"type\":\"forge:ore_shapeless\",\"ingredients\":[{\"type\":\"forge:ore_dict\","
                        + "\"ore\":\"oreIron\"},{\"type\":\"forge:ore_dict\",\"ore\":\"sand\"},"
                        + "{\"type\":\"forge:ore_dict\",\"ore\":\"netherrack\"}],"
                        + "\"result\":{\"item\":\"mod:kiln\"}}");

        JsonArray ingredients = recipe(tree, target, "kiln.json").getAsJsonArray("ingredients");
        assertEquals(List.of(tag(ores), tag(sand), tag(netherrack)), ingredients.asList());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"NEOFORGE, 1.21.1", "NEOFORGE, 26.3", "FABRIC, 1.21.11", "FORGE, 1.20.1",
            "FORGE, 1.20.4", "FORGE, 1.21.11", "FORGE, 26.2", "FORGE, 26.3"})
    @DisplayName("A modded material tag gets the host loader's condition that skips the recipe while it is empty")
    void moddedMaterialIsGuarded(String loader, String target) throws IOException {
        MapResourceTree tree = convert(MigrationContext.Loader.valueOf(loader), target,
                "assets/mod/recipes/pole.json", String.format(STICKS, "ingotTin"));

        JsonObject recipe = recipe(tree, target, "pole.json");
        switch (loader) {
            case "NEOFORGE" -> {
                assertEquals(tag("c:ingots/tin"), recipe.getAsJsonObject("key").get("S"));
                assertEquals(condition("type", "neoforge:not", "value",
                                condition("type", "neoforge:tag_empty", "tag", "c:ingots/tin")),
                        recipe.getAsJsonArray("neoforge:conditions").get(0));
            }
            case "FABRIC" -> {
                JsonArray values = new JsonArray();
                values.add("c:ingots/tin");
                assertEquals(condition("condition", "fabric:tags_populated", "values", values),
                        recipe.getAsJsonArray("fabric:load_conditions").get(0));
            }
            default -> {
                boolean list = target.equals("1.20.1");
                String id = target.startsWith("26.") ? "c:ingots/tin" : "forge:ingots/tin";
                assertEquals(tag(id), recipe.getAsJsonObject("key").get("S"));
                JsonObject guard = condition("type", "forge:not", "value",
                        condition("type", "forge:tag_empty", "tag", id));
                assertEquals(guard, list ? recipe.getAsJsonArray("conditions").get(0)
                        : recipe.get("forge:condition"), "1.20.1 reads a list, 1.20.2 on one condition");
            }
        }
    }

    @Test
    @DisplayName("A constant is inlined, and its conditions and the recipe's own become NeoForge conditions")
    void constantsAndConditions() throws IOException {
        MapResourceTree tree = convert(MigrationContext.Loader.NEOFORGE, "1.21.1",
                "assets/mod/recipes/_constants.json", "[{\"name\":\"CHAMOMILE\",\"conditions\":"
                        + "[{\"type\":\"forge:mod_loaded\",\"modid\":\"rustic\"}],"
                        + "\"ingredient\":{\"item\":\"rustic:chamomile\"}},"
                        + "{\"name\":\"STICK\",\"ingredient\":"
                        + "{\"type\":\"forge:ore_dict\",\"ore\":\"stickWood\"}}]",
                "assets/mod/recipes/tea.json", "{\"type\":\"forge:ore_shapeless\",\"conditions\":"
                        + "[{\"type\":\"forge:not\",\"value\":"
                        + "{\"type\":\"forge:mod_loaded\",\"modid\":\"other\"}}],"
                        + "\"ingredients\":[{\"item\":\"#CHAMOMILE\"},\"#STICK\"],"
                        + "\"result\":{\"item\":\"mod:tea\"}}");

        JsonObject recipe = recipe(tree, "1.21.1", "tea.json");
        assertEquals("minecraft:crafting_shapeless", recipe.get("type").getAsString());
        JsonArray ingredients = recipe.getAsJsonArray("ingredients");
        assertEquals(JsonParser.parseString("{\"item\":\"rustic:chamomile\"}"), ingredients.get(0));
        assertEquals(tag("c:rods/wooden"), ingredients.get(1),
                "a constant already turned into a bare #NAME string is inlined too");
        assertFalse(recipe.has("conditions"), "NeoForge does not read Forge's key");
        JsonArray conditions = recipe.getAsJsonArray("neoforge:conditions");
        assertEquals(condition("type", "neoforge:not", "value",
                condition("type", "neoforge:mod_loaded", "modid", "other")), conditions.get(0));
        assertEquals(condition("type", "neoforge:mod_loaded", "modid", "rustic"), conditions.get(1),
                "Forge 1.12 skipped a recipe whose constant failed its conditions");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"NEOFORGE, 1.20.4", "NEOFORGE, 1.21.1", "NEOFORGE, 1.21.3", "NEOFORGE, 1.21.4",
            "NEOFORGE, 26.3", "FABRIC, 1.21.1", "FABRIC, 26.3", "FORGE, 1.20.1", "FORGE, 1.20.4", "FORGE, 26.3"})
    @DisplayName("A disabled recipe and an item check use the names each loader version reads")
    void falseAndItemExistsFollowTheLoader(String loader, String target) throws IOException {
        MapResourceTree tree = convert(MigrationContext.Loader.valueOf(loader), target,
                "assets/mod/recipes/off.json", "{\"type\":\"forge:ore_shaped\",\"conditions\":"
                        + "[{\"type\":\"forge:false\"}],\"pattern\":[\"S\"],\"key\":{\"S\":"
                        + "{\"item\":\"minecraft:stick\"}},\"result\":{\"item\":\"mod:pole\"}}",
                "assets/mod/recipes/gear.json", "{\"type\":\"forge:ore_shapeless\",\"conditions\":"
                        + "[{\"type\":\"forge:not\",\"value\":{\"type\":\"minecraft:item_exists\","
                        + "\"item\":\"Other:Gear\"}}],\"ingredients\":[{\"item\":\"minecraft:stick\"}],"
                        + "\"result\":{\"item\":\"mod:gear\"}}");

        JsonObject off = recipe(tree, target, "off.json");
        JsonObject gear = recipe(tree, target, "gear.json");
        JsonArray item = new JsonArray();
        item.add("other:gear");
        boolean newNeoForge = loader.equals("NEOFORGE") && !target.startsWith("1.20")
                && !target.equals("1.21.1") && !target.equals("1.21.3");
        JsonObject never;
        JsonObject exists;
        switch (loader) {
            case "NEOFORGE" -> {
                never = condition("type", newNeoForge ? "neoforge:never" : "neoforge:false");
                exists = newNeoForge
                        ? condition("type", "neoforge:registered", "registry", "minecraft:item", "value", "other:gear")
                        : condition("type", "neoforge:item_exists", "item", "other:gear");
                exists = condition("type", "neoforge:not", "value", exists);
            }
            case "FABRIC" -> {
                never = condition("condition", "fabric:not", "value", condition("condition", "fabric:true"));
                exists = condition("condition", "fabric:not", "value", condition("condition",
                        "fabric:registry_contains", "values", item, "registry", "minecraft:item"));
            }
            default -> {
                never = condition("type", "forge:false");
                exists = condition("type", "forge:not", "value",
                        condition("type", "forge:item_exists", "item", "other:gear"));
            }
        }
        String key = switch (loader) {
            case "NEOFORGE" -> "neoforge:conditions";
            case "FABRIC" -> "fabric:load_conditions";
            default -> target.equals("1.20.1") ? "conditions" : "forge:condition";
        };
        JsonElement offConditions = off.get(key);
        JsonElement gearConditions = gear.get(key);
        assertEquals(never, offConditions.isJsonArray() ? offConditions.getAsJsonArray().get(0) : offConditions,
                "a recipe the mod disabled must stay disabled once its shape parses");
        assertEquals(exists, gearConditions.isJsonArray() ? gearConditions.getAsJsonArray().get(0) : gearConditions,
                "Forge 1.12 read item ids in any case");
        if (!key.equals("conditions")) {
            assertFalse(off.has("conditions") || gear.has("conditions"), "the 1.12 list is replaced");
        }
    }

    @Test
    @DisplayName("Forge 1.20.1 reads the 1.12 condition list, so its conditions keep that shape")
    void forgeListConditions() throws IOException {
        MapResourceTree tree = convert(MigrationContext.Loader.FORGE, "1.20.1",
                "assets/mod/recipes/tea.json", "{\"type\":\"minecraft:crafting_shapeless\",\"conditions\":"
                        + "[{\"type\":\"forge:mod_loaded\",\"modid\":\"rustic\"}],"
                        + "\"ingredients\":[{\"item\":\"rustic:chamomile\"}],\"result\":{\"item\":\"mod:tea\"}}");

        assertEquals(condition("type", "forge:mod_loaded", "modid", "rustic"),
                recipe(tree, "1.20.1", "tea.json").getAsJsonArray("conditions").get(0));
    }

    @Test
    @DisplayName("A recipe or ingredient type from the mod's _factories.json is left for the gate")
    void factoryTypesAreLeft() throws IOException {
        String ammo = "{\"type\":\"techguns:ammo_change_crafting\",\"pattern\":[\"A\"],"
                + "\"key\":{\"A\":{\"type\":\"techguns:circuitElite\"}},\"result\":{\"item\":\"mod:ammo\"}}";
        MapResourceTree tree = convert(MigrationContext.Loader.NEOFORGE, "1.21.1",
                "assets/mod/recipes/_factories.json", "{\"recipes\":{\"ammo_change_crafting\":\"a.B\"}}",
                "assets/mod/recipes/ammo.json", ammo);

        assertEquals(JsonParser.parseString(ammo), recipe(tree, "1.21.1", "ammo.json"));
        assertFalse(tree.exists("data/mod/recipe/_factories.json"), "the helper file is never copied");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"NEOFORGE, 1.21.1, false", "NEOFORGE, 26.3, false", "FABRIC, 1.20.1, false",
            "FORGE, 1.20.4, false", "FORGE, 1.20.1, true", "FORGE, 1.18.2, true", "NEOFORGE, 1.20.1, true"})
    @DisplayName("A recipe whose mod condition no host can check is kept out, unless the host reads Forge's list")
    void uncheckableConditionsKeepTheRecipeOut(String loader, String target, boolean readsForgeList)
            throws IOException {
        String shield = "{\"type\":\"forge:ore_shapeless\",\"conditions\":[{\"type\":\"item_exists\","
                + "\"item\":\"mod:shield\"}],\"ingredients\":[{\"item\":\"minecraft:stick\"}],"
                + "\"result\":{\"item\":\"mod:shield\"}}";
        MapResourceTree tree = convert(MigrationContext.Loader.valueOf(loader), target,
                "assets/mod/recipes/shield.json", shield);

        boolean singular = RetromodVersion.compareMcVersions(target, "1.21") >= 0;
        String copy = "data/mod/" + (singular ? "recipe/" : "recipes/") + "shield.json";
        assertTrue(tree.exists("assets/mod/recipes/shield.json"), "the original is never touched");
        assertEquals(readsForgeList, tree.exists(copy), "an unnamespaced condition was the mod's own "
                + "factory, which nothing runs now; a host that reads Forge's list rejects it itself");
        if (readsForgeList) {
            assertEquals(JsonParser.parseString(shield).getAsJsonObject().get("conditions"),
                    recipe(tree, target, "shield.json").get("conditions"));
        }
    }

    @Test
    @DisplayName("On 26.3 an ore recipe with a constant comes through every later step and loads")
    void endToEndOn26_3() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/mod/recipes/_constants.json",
                "[{\"name\":\"STICK\",\"ingredient\":{\"type\":\"forge:ore_dict\",\"ore\":\"stickWood\"}}]",
                "assets/mod/recipes/pole.json", "{\"type\":\"forge:ore_shaped\",\"pattern\":[\"SI\"],"
                        + "\"key\":{\"S\":{\"item\":\"#STICK\"},"
                        + "\"I\":{\"type\":\"forge:ore_dict\",\"ore\":\"ingotTin\"}},"
                        + "\"result\":{\"item\":\"mod:pole\",\"count\":2}}"));
        MigrationContext context = MigrationContext.builder("26.3").sourceMcVersion("1.12.2")
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(Set.of("mod")).build();

        ModResourceMigrations.standard().run(tree, context);

        assertTrue(tree.exists("data/mod/recipe/pole.json"), "the load gate kept the copy");
        JsonObject recipe = recipe(tree, "26.3", "pole.json");
        assertEquals("#c:rods/wooden", recipe.getAsJsonObject("key").get("S").getAsString());
        assertEquals("#c:ingots/tin", recipe.getAsJsonObject("key").get("I").getAsString());
        assertEquals("neoforge:not", recipe.getAsJsonArray("neoforge:conditions").get(0)
                .getAsJsonObject().get("type").getAsString());
    }

    @Test
    @DisplayName("Constants that name each other many times are not inlined past a bound")
    void constantFanOutIsBounded() throws IOException {
        // Each of eight constants names the next five times: inlined, one reference is 5^8 items.
        StringBuilder constants = new StringBuilder("[");
        for (int level = 0; level < 8; level++) {
            if (level > 0) constants.append(',');
            constants.append("{\"name\":\"L").append(level).append("\",\"ingredient\":[");
            for (int i = 0; i < 5; i++) {
                if (i > 0) constants.append(',');
                constants.append(level < 7 ? "{\"item\":\"#L" + (level + 1) + "\"}"
                        : "{\"item\":\"minecraft:stone\",\"data\":" + i + "}");
            }
            constants.append("]}");
        }
        constants.append(']');
        String fan = "{\"type\":\"forge:ore_shapeless\",\"ingredients\":[{\"item\":\"#L0\"}],"
                + "\"result\":{\"item\":\"mod:x\"}}";
        MapResourceTree tree = new MapResourceTree(entries("assets/mod/recipes/_constants.json",
                constants.toString(), "assets/mod/recipes/fan.json", fan));
        MigrationContext context = MigrationContext.builder("1.21.1").sourceMcVersion("1.12.2")
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(Set.of("mod")).build();

        assertDoesNotThrow(() -> ModResourceMigrations.standard().run(tree, context));

        String copy = text(tree.read("data/mod/recipe/fan.json"));
        assertTrue(copy.contains("#L0"), "the recipe is left as shipped, which the host skips");
        assertTrue(copy.length() < 4096, "the copy did not grow: " + copy.length() + " characters");
    }

    @Test
    @DisplayName("NeoForge before 1.21 keeps the ore names, since no jar of it showed its tags and conditions")
    void uncheckedTargetsKeepOreNames() throws IOException {
        MapResourceTree tree = convert(MigrationContext.Loader.NEOFORGE, "1.20.4",
                "assets/mod/recipes/pole.json", String.format(STICKS, "stickWood"));

        JsonObject recipe = recipe(tree, "1.20.4", "pole.json");
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString(),
                "no host after 1.12 reads the ore types");
        assertEquals("forge:ore_dict", recipe.getAsJsonObject("key").getAsJsonObject("S")
                .get("type").getAsString());
    }

    @Test
    @DisplayName("A 1.13 source and a host that still reads the ore dictionary are left alone")
    void olderShapesAreLeftAlone() throws IOException {
        LegacyForgeRecipeMigrator migrator = new LegacyForgeRecipeMigrator();

        assertFalse(migrator.appliesTo(MigrationContext.builder("1.12.2").sourceMcVersion("1.12.2")
                .targetLoader(MigrationContext.Loader.FORGE).build()), "1.12 Forge reads all of it");
        assertFalse(migrator.appliesTo(MigrationContext.builder("26.3").sourceMcVersion("1.13")
                .targetLoader(MigrationContext.Loader.NEOFORGE).build()));
        String shipped = String.format(STICKS, "stickWood");
        MapResourceTree tree = convert("1.13.2", MigrationContext.Loader.NEOFORGE, "1.21.1",
                "data/mod/recipes/pole.json", shipped);
        assertEquals(JsonParser.parseString(shipped), recipe(tree, "1.21.1", "pole.json"));
    }

    @Test
    @DisplayName("Ore names map to the family's tag, to the vanilla item where it has none, or by material")
    void oreTable() {
        LegacyOreDictionary.Family forge = LegacyOreDictionary.Family.FORGE;
        LegacyOreDictionary.Family convention = LegacyOreDictionary.Family.CONVENTION;

        assertEquals(159, LegacyOreDictionary.listedCount());
        assertEquals(new LegacyOreDictionary.Replacement("c:strings", true, false),
                LegacyOreDictionary.replacement("string", convention));
        assertEquals(new LegacyOreDictionary.Replacement("forge:string", true, false),
                LegacyOreDictionary.replacement("string", forge));
        assertEquals(new LegacyOreDictionary.Replacement("minecraft:quartz_block", false, false),
                LegacyOreDictionary.replacement("blockQuartz", convention),
                "no loader ships a c: quartz block tag, and Forge 1.12 put only the quartz block in it");
        assertEquals(new LegacyOreDictionary.Replacement("c:ingots/liquified_coralium", true, true),
                LegacyOreDictionary.replacement("ingotLiquifiedCoralium", convention));
        assertNull(LegacyOreDictionary.replacement("listAllporkcooked", convention),
                "a mod's own list name is left rather than guessed");
        assertEquals(new LegacyOreDictionary.Replacement("minecraft:wool", true, false),
                LegacyOreDictionary.replacement("wool", forge), "Forge 1.12 put every wool color in wool");
        assertEquals(new LegacyOreDictionary.Replacement("minecraft:red_wool", false, false),
                LegacyOreDictionary.replacement("woolRed", convention));
        assertEquals(new LegacyOreDictionary.Replacement("minecraft:dirt", false, false),
                LegacyOreDictionary.replacement("dirt", forge), "Forge 1.12 registered only plain dirt");
        assertEquals(new LegacyOreDictionary.Replacement("c:storage_blocks/wool", true, true),
                LegacyOreDictionary.replacement("blockWool", convention),
                "Forge 1.12 never registered blockWool, so only another mod could have filled it");
    }

    @Test
    @DisplayName("Paper, which no loader tags, becomes the paper item")
    void untaggedNameBecomesItsItem() throws IOException {
        MapResourceTree tree = convert(MigrationContext.Loader.NEOFORGE, "26.3",
                "assets/mod/recipes/pole.json", String.format(STICKS, "paper"));

        assertEquals(JsonParser.parseString("{\"item\":\"minecraft:paper\"}"),
                recipe(tree, "26.3", "pole.json").getAsJsonObject("key").get("S"));
    }
}
