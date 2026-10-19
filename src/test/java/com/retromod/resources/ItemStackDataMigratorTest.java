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
import org.junit.jupiter.params.provider.ValueSource;

import static com.retromod.resources.ResourceFixtures.bytes;
import static com.retromod.resources.ResourceFixtures.text;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Old item stacks, item predicates and loot functions in mod data, each rewritten from the release
 * that changed its format and left alone on a host that still reads the old shape.
 */
class ItemStackDataMigratorTest {

    private static final String RECIPE = "data/mod/recipe/wand.json";
    private static final String LOOT = "data/mod/loot_table/chests/camp.json";
    private static final String ADVANCEMENT = "data/mod/advancement/explore.json";

    /** The rule pass alone, as {@link ModDataMigrator} runs it before the later rewrites. */
    private static String rules(String entry, String json, String source, String target) {
        return text(ItemStackDataMigrator.migrate(entry, bytes(json), source, target));
    }

    /** Every data rewrite, as a transform runs it. */
    private static JsonObject migrated(String entry, String json, String source, String target,
                                       MigrationContext.Loader loader) {
        return JsonParser.parseString(text(ModDataMigrator.migrate(entry, bytes(json), source, target,
                loader))).getAsJsonObject();
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---- item stacks --------------------------------------------------------------------------

    @Test
    @DisplayName("A result's NBT becomes components: the mod's keys as custom data, Minecraft's as their own")
    void resultNbtBecomesComponents() {
        String recipe = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:stick\"],"
                + "\"result\":{\"item\":\"mod:wand\",\"count\":2,\"nbt\":\"{Charge:3b,Damage:7}\"}}";

        JsonObject result = parse(rules(RECIPE, recipe, "1.20.1", "1.21.1")).getAsJsonObject("result");

        assertEquals("mod:wand", result.get("id").getAsString());
        assertEquals(2, result.get("count").getAsInt());
        JsonObject components = result.getAsJsonObject("components");
        assertEquals("{Charge:3b}", components.get("minecraft:custom_data").getAsString(),
                "the byte keeps its type");
        assertEquals(7, components.get("minecraft:damage").getAsInt());
        assertFalse(result.has("nbt"));
    }

    @Test
    @DisplayName("1.20.4 still reads a result's item key and its NBT")
    void olderHostKeepsTheItemKey() {
        String recipe = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:stick\"],"
                + "\"result\":{\"item\":\"mod:wand\",\"nbt\":\"{Charge:3b}\"}}";

        assertEquals(recipe, rules(RECIPE, recipe, "1.20.1", "1.20.4"));
    }

    @Test
    @DisplayName("A potion result keeps its potion on 26.3 instead of becoming a water bottle")
    void potionNbtBecomesPotionContents() {
        String antidote = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"mod:herb\"],"
                + "\"result\":{\"item\":\"minecraft:potion\",\"nbt\":\"{Potion:\\\"minecraft:water\\\"}\"}}";

        JsonObject result = migrated(RECIPE, antidote, "1.12.2", "26.3", MigrationContext.Loader.NEOFORGE)
                .getAsJsonObject("result");

        assertEquals("minecraft:potion", result.get("id").getAsString());
        assertEquals("minecraft:water", result.getAsJsonObject("components")
                .getAsJsonObject("minecraft:potion_contents").get("potion").getAsString());
    }

    @Test
    @DisplayName("1.20.5 and 1.20.6 read custom data only as an object, so a narrowing value stays NBT")
    void customDataOnTheFirstComponentHost() {
        String fits = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:stick\"],"
                + "\"result\":{\"item\":\"mod:wand\",\"nbt\":\"{Charge:3b}\"}}";
        assertEquals("{\"Charge\":3}", parse(rules(RECIPE, fits, "1.20.1", "1.20.5")).getAsJsonObject("result")
                .getAsJsonObject("components").get("minecraft:custom_data").toString());

        String narrows = fits.replace("3b", "3");
        assertEquals(narrows, rules(RECIPE, narrows, "1.20.1", "1.20.5"),
                "an int 3 would come back a byte, so the result is left for the host to skip");
    }

    @Test
    @DisplayName("Cooking and stonecutting results given as a bare id become stacks")
    void bareResultsBecomeStacks() {
        String stonecutting = "{\"type\":\"minecraft:stonecutting\",\"count\":2,\"ingredient\":\"mod:stone\","
                + "\"result\":\"mod:slab\"}";

        JsonObject recipe = parse(rules(RECIPE, stonecutting, "1.20.1", "26.3"));

        assertFalse(recipe.has("count"));
        assertEquals("{\"id\":\"mod:slab\",\"count\":2}", recipe.get("result").toString());
    }

    @Test
    @DisplayName("An advancement icon names its item by id from 1.20.5")
    void iconsNameTheirItemById() {
        String advancement = "{\"display\":{\"icon\":{\"item\":\"mod:map\",\"nbt\":\"{Zoom:2}\"},"
                + "\"title\":\"x\",\"description\":\"y\"},\"criteria\":{}}";

        JsonObject icon = parse(rules(ADVANCEMENT, advancement, "1.18.2", "1.21.1"))
                .getAsJsonObject("display").getAsJsonObject("icon");

        assertEquals("mod:map", icon.get("id").getAsString());
        assertEquals("{Zoom:2}", icon.getAsJsonObject("components").get("minecraft:custom_data").getAsString());
        assertEquals(advancement, rules(ADVANCEMENT, advancement, "1.18.2", "1.20.4"));
    }

    // ---- NBT components -----------------------------------------------------------------------

    @Test
    @DisplayName("set_nbt splits into set_components for Minecraft's keys and set_custom_data for the mod's")
    void setNbtSplits() {
        String loot = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"mod:axe\","
                + "\"functions\":[{\"function\":\"minecraft:set_nbt\",\"tag\":\"{CustomModelData:201013,Rune:1b}\"}]}]}]}";

        JsonArray functions = parse(rules(LOOT, loot, "1.18.2", "1.21.4")).getAsJsonArray("pools").get(0)
                .getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("functions");

        assertEquals(2, functions.size());
        JsonObject components = functions.get(0).getAsJsonObject();
        assertEquals("minecraft:set_components", components.get("function").getAsString());
        assertEquals("{\"floats\":[201013]}", components.getAsJsonObject("components")
                .get("minecraft:custom_model_data").toString(), "1.21.4 reads model data as floats");
        assertEquals("{Rune:1b}", functions.get(1).getAsJsonObject().get("tag").getAsString());

        JsonArray onOlderHost = parse(rules(LOOT, loot, "1.18.2", "1.21.3")).getAsJsonArray("pools").get(0)
                .getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("functions");
        assertEquals(201013, onOlderHost.get(0).getAsJsonObject().getAsJsonObject("components")
                .get("minecraft:custom_model_data").getAsInt(), "1.21.3 still reads a plain int");
    }

    @Test
    @DisplayName("set_nbt on a vanilla item entry gives that item's own keys their components")
    void setNbtKnowsItsEntryItem() {
        String loot = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"item\",\"name\":\"minecraft:tipped_arrow\","
                + "\"functions\":[{\"function\":\"set_nbt\",\"tag\":\"{Potion:\\\"minecraft:slowness\\\"}\"}]}]}]}";

        JsonObject function = parse(rules(LOOT, loot, "1.12.2", "26.3")).getAsJsonArray("pools").get(0)
                .getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject()
                .getAsJsonArray("functions").get(0).getAsJsonObject();

        assertEquals("minecraft:set_components", function.get("function").getAsString());
        assertEquals("minecraft:slowness", function.getAsJsonObject("components")
                .getAsJsonObject("minecraft:potion_contents").get("potion").getAsString(),
                "the arrow keeps its potion rather than carrying it as custom data");
    }

    @Test
    @DisplayName("A conditional set_nbt stays one function, so its condition is rolled once")
    void conditionalSetNbtRollsOnce() {
        String loot = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"mod:charm\","
                + "\"functions\":[{\"function\":\"minecraft:set_nbt\",\"tag\":\"{Unbreakable:1b,Owner:\\\"x\\\"}\","
                + "\"conditions\":[{\"condition\":\"minecraft:random_chance\",\"chance\":0.5}]}]}]}]}";

        JsonArray functions = parse(rules(LOOT, loot, "1.20.1", "1.21.1")).getAsJsonArray("pools").get(0)
                .getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("functions");
        assertEquals(1, functions.size(), "two functions would each roll the chance on their own");
        JsonObject function = functions.get(0).getAsJsonObject();
        assertEquals("minecraft:set_components", function.get("function").getAsString());
        assertEquals("{Owner:\"x\"}", function.getAsJsonObject("components").get("minecraft:custom_data")
                .getAsString());
        assertTrue(function.getAsJsonObject("components").has("minecraft:unbreakable"));
        assertEquals(1, function.getAsJsonArray("conditions").size());

        JsonObject on263 = migrated(LOOT, loot, "1.20.1", "26.3", MigrationContext.Loader.NEOFORGE)
                .getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0)
                .getAsJsonObject().getAsJsonObject("modifier");
        assertEquals("minecraft:set_components", on263.get("type").getAsString());
        assertEquals("minecraft:random_chance", on263.getAsJsonObject("condition").get("type").getAsString());
    }

    @Test
    @DisplayName("A pool or table set_nbt uses the item every entry names, and leaves an item's own keys alone otherwise")
    void scopedSetNbtKnowsItsItems() {
        String setPotion = "{\"function\":\"minecraft:set_nbt\",\"tag\":\"{Potion:\\\"minecraft:healing\\\"}\"}";
        String pool = "{\"pools\":[{\"rolls\":1,\"functions\":[" + setPotion + "],\"entries\":[{\"type\":"
                + "\"minecraft:item\",\"name\":\"minecraft:potion\"}]}]}";
        String table = "{\"functions\":[" + setPotion + "],\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":"
                + "\"minecraft:item\",\"name\":\"minecraft:potion\"}]}]}";
        String mixed = pool.replace("\"name\":\"minecraft:potion\"}",
                "\"name\":\"minecraft:potion\"},{\"type\":\"minecraft:item\",\"name\":\"mod:flask\"}");

        JsonObject poolFunction = parse(rules(LOOT, pool, "1.20.1", "1.21.1")).getAsJsonArray("pools").get(0)
                .getAsJsonObject().getAsJsonArray("functions").get(0).getAsJsonObject();
        assertEquals("minecraft:healing", poolFunction.getAsJsonObject("components")
                .getAsJsonObject("minecraft:potion_contents").get("potion").getAsString(),
                "a water bottle would carry the potion as custom data");
        JsonObject tableFunction = parse(rules(LOOT, table, "1.20.1", "1.21.1")).getAsJsonArray("functions")
                .get(0).getAsJsonObject();
        assertEquals("minecraft:set_components", tableFunction.get("function").getAsString());
        assertEquals(mixed, rules(LOOT, mixed, "1.20.1", "1.21.1"),
                "Potion is Minecraft's on the potion and the mod's own on the flask");

        JsonObject on263 = migrated(LOOT, pool, "1.20.1", "26.3", MigrationContext.Loader.NEOFORGE)
                .getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonObject("modifier");
        assertEquals("minecraft:set_components", on263.get("type").getAsString());
        assertTrue(on263.getAsJsonObject("components").has("minecraft:potion_contents"));
    }

    @Test
    @DisplayName("Attribute modifiers carry over with their ids in each host's spelling")
    void attributeModifiersFollowEachHost() {
        String tag = "{AttributeModifiers:[{AttributeName:\\\"generic.attackDamage\\\",Name:\\\"Blade\\\","
                + "Amount:7.0d,Operation:0,UUID:[I;1,2,3,4],Slot:\\\"mainhand\\\"}]}";
        String loot = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"mod:axe\","
                + "\"functions\":[{\"function\":\"minecraft:set_nbt\",\"tag\":\"" + tag + "\"}]}]}]}";

        assertEquals("{\"type\":\"minecraft:generic.attack_damage\",\"uuid\":[1,2,3,4],\"name\":\"Blade\","
                        + "\"amount\":7.0,\"operation\":\"add_value\",\"slot\":\"mainhand\"}",
                modifier(rules(LOOT, loot, "1.12.2", "1.20.5")), "1.20.5 names a modifier by UUID");
        assertEquals("{\"type\":\"minecraft:generic.attack_damage\",\"id\":\"minecraft:00000001-0000-0002-"
                        + "0000-000300000004\",\"amount\":7.0,\"operation\":\"add_value\",\"slot\":\"mainhand\"}",
                modifier(rules(LOOT, loot, "1.12.2", "1.21.1")), "1.21 names it by id");
        assertEquals("minecraft:attack_damage", parse(modifier(rules(LOOT, loot, "1.12.2", "26.3")))
                .get("type").getAsString(), "1.21.2 dropped the generic. prefix");
    }

    private static String modifier(String loot) {
        return parse(loot).getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0)
                .getAsJsonObject().getAsJsonArray("functions").get(0).getAsJsonObject()
                .getAsJsonObject("components").getAsJsonArray("minecraft:attribute_modifiers").get(0).toString();
    }

    @Test
    @DisplayName("NBT with a Minecraft key that cannot be carried over is left whole")
    void unconvertibleNbtIsLeftWhole() {
        String loot = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"mod:pick\","
                + "\"functions\":[{\"function\":\"minecraft:set_nbt\",\"tag\":\"{CanDestroy:[\\\"minecraft:stone\\\"]}\"}]}]}]}";

        assertEquals(loot, rules(LOOT, loot, "1.18.2", "26.3"),
                "the old function stays, so the host skips the file or the gate keeps it out");
    }

    @Test
    @DisplayName("copy_nbt becomes copy_custom_data only when it copies into the mod's own keys")
    void copyNbtIntoModKeys() {
        String into = "{\"function\":\"minecraft:copy_nbt\",\"source\":\"block_entity\","
                + "\"ops\":[{\"source\":\"Energy\",\"target\":\"Energy\",\"op\":\"replace\"}]}";
        String block = into.replace("\"target\":\"Energy\"", "\"target\":\"BlockEntityTag.Energy\"");

        assertTrue(rules("data/mod/item_modifier/copy.json", into, "1.20.1", "1.21.1")
                .contains("minecraft:copy_custom_data"));
        assertEquals(block, rules("data/mod/item_modifier/copy.json", block, "1.20.1", "1.21.1"),
                "custom data is not where a placed block reads its data");
    }

    // ---- item predicates ----------------------------------------------------------------------

    @Test
    @DisplayName("An item predicate's item becomes a list on 1.17, then one id or tag on 1.20.5")
    void itemPredicateItems() {
        String tool = "{\"condition\":\"minecraft:match_tool\",\"predicate\":{\"item\":\"minecraft:shears\"}}";

        assertTrue(rules("data/mod/predicate/shears.json", tool, "1.16.5", "1.17.1")
                .contains("\"items\":[\"minecraft:shears\"]"));
        assertTrue(rules("data/mod/predicate/shears.json", tool, "1.16.5", "1.20.5")
                .contains("\"items\":\"minecraft:shears\""));
        assertEquals(tool, rules("data/mod/predicate/shears.json", tool, "1.16.5", "1.16.5"));

        String tag = "{\"condition\":\"minecraft:match_tool\",\"predicate\":{\"tag\":\"forge:shears\"}}";
        assertTrue(rules("data/mod/predicate/shears.json", tag, "1.18.2", "1.21.1")
                .contains("\"items\":\"#forge:shears\""));
    }

    @Test
    @DisplayName("NBT, enchantment and potion checks move under predicates, with 1.21's enchantments key")
    void itemSubPredicates() {
        String advancement = "{\"criteria\":{\"get\":{\"trigger\":\"minecraft:inventory_changed\","
                + "\"conditions\":{\"items\":[{\"item\":\"mod:sword\",\"nbt\":\"{Forged:1b}\","
                + "\"enchantments\":[{\"enchantment\":\"minecraft:sharpness\",\"levels\":{\"min\":3}}]}]}}}}";

        JsonObject on1205 = itemPredicate(rules(ADVANCEMENT, advancement, "1.20.1", "1.20.5"));
        assertEquals("mod:sword", on1205.get("items").getAsString());
        assertEquals("{Forged:1b}", on1205.getAsJsonObject("predicates").get("minecraft:custom_data").getAsString());
        assertEquals("minecraft:sharpness", on1205.getAsJsonObject("predicates")
                .getAsJsonArray("minecraft:enchantments").get(0).getAsJsonObject().get("enchantment").getAsString());

        JsonObject on121 = itemPredicate(rules(ADVANCEMENT, advancement, "1.20.1", "1.21"));
        assertEquals("minecraft:sharpness", on121.getAsJsonObject("predicates")
                .getAsJsonArray("minecraft:enchantments").get(0).getAsJsonObject().get("enchantments").getAsString());
    }

    @Test
    @DisplayName("recipe_crafted's ingredient predicates take 1.20.5's items, as vanilla's decorated pot advancement did")
    void recipeCraftedIngredients() {
        // Vanilla 1.20.1's adventure/craft_decorated_pot_using_only_sherds, with a crafter criterion
        // in the same shape beside it.
        String advancement = "{\"criteria\":{\"pot_crafted_using_only_sherds\":{\"conditions\":{\"ingredients\":"
                + "[{\"tag\":\"minecraft:decorated_pot_sherds\"},{\"tag\":\"minecraft:decorated_pot_sherds\"}],"
                + "\"recipe_id\":\"minecraft:decorated_pot\"},\"trigger\":\"minecraft:recipe_crafted\"},"
                + "\"crafted\":{\"conditions\":{\"ingredients\":[{\"tag\":\"c:rods\"}],"
                + "\"recipe_id\":\"mod:wand\"},\"trigger\":\"minecraft:crafter_recipe_crafted\"}}}";

        String on1205 = rules(ADVANCEMENT, advancement, "1.20.1", "1.20.5");
        assertTrue(on1205.contains("\"ingredients\":[{\"items\":\"#minecraft:decorated_pot_sherds\"},"
                + "{\"items\":\"#minecraft:decorated_pot_sherds\"}]"), on1205);
        assertTrue(on1205.contains("\"ingredients\":[{\"items\":\"#c:rods\"}]"), on1205);
        assertEquals(advancement, rules(ADVANCEMENT, advancement, "1.20.1", "1.20.4"),
                "1.20.4 still reads the tag key");

        JsonObject on263 = migrated(ADVANCEMENT, advancement, "1.20.1", "26.3", MigrationContext.Loader.NEOFORGE)
                .getAsJsonObject("criteria").getAsJsonObject("pot_crafted_using_only_sherds")
                .getAsJsonObject("conditions");
        assertEquals("#minecraft:decorated_pot_sherds", on263.getAsJsonArray("ingredients").get(0)
                .getAsJsonObject().get("items").getAsString());
        assertEquals("minecraft:decorated_pot", on263.get("recipes").getAsString(), "26.3 renamed recipe_id");
    }

    @Test
    @DisplayName("1.21 names an enchantment predicate's enchantments, also in data already in 1.20.5's shape")
    void enchantmentKeyOfThe1_20_5Shape() {
        String silkTouch = "{\"condition\":\"minecraft:match_tool\",\"predicate\":{\"predicates\":"
                + "{\"minecraft:enchantments\":[{\"enchantment\":\"minecraft:silk_touch\",\"levels\":{\"min\":1}}]}}}";
        String file = "data/mod/predicate/silk_touch.json";
        String current = silkTouch.replace("\"enchantment\":", "\"enchantments\":");

        assertEquals(current, rules(file, silkTouch, "1.20.6", "1.21.1"),
                "1.21 ignores the old key, so any enchanted tool would match");
        assertEquals(current, rules(file, silkTouch, "1.20.6", "26.2"));
        assertEquals(current, rules(file, silkTouch, "1.20.6", "26.3"));
        assertEquals(silkTouch, rules(file, silkTouch, "1.20.6", "1.20.6"));
        assertEquals(current, rules(file, current, "1.21.1", "26.3"));
    }

    @Test
    @DisplayName("An item predicate whose item is unknown keeps a check only some of Minecraft's items read")
    void unknownItemKeepsItsPotionCheck() {
        String tag = "{\"condition\":\"minecraft:match_tool\",\"predicate\":{\"tag\":\"c:potions\","
                + "\"nbt\":\"{Potion:\\\"minecraft:healing\\\"}\"}}";
        String modItem = tag.replace("\"tag\":\"c:potions\"", "\"item\":\"mod:flask\"");
        String file = "data/mod/predicate/healing.json";

        assertEquals(tag, rules(file, tag, "1.20.1", "1.21.1"),
                "custom data would never match a potion's potion_contents");
        assertTrue(rules(file, modItem, "1.20.1", "1.21.1").contains("\"minecraft:custom_data\""),
                "on the mod's own item Potion is the mod's key");
    }

    @Test
    @DisplayName("A Potion NBT check on Minecraft's own item becomes the potion_contents predicate")
    void potionNbtCheckOnAVanillaItem() {
        String advancement = "{\"criteria\":{\"get\":{\"trigger\":\"minecraft:inventory_changed\",\"conditions\":"
                + "{\"items\":[{\"items\":[\"minecraft:tipped_arrow\"],\"nbt\":\"{Potion:\\\"mod:dragon_root\\\"}\"}]}}}}";

        for (String target : new String[] {"1.20.5", "26.3"}) {
            JsonObject predicate = itemPredicate(rules(ADVANCEMENT, advancement, "1.18.2", target));
            assertEquals("{\"minecraft:potion_contents\":\"mod:dragon_root\"}", predicate.get("predicates").toString(),
                    target + ": custom data would never match the arrow's potion");
        }
        assertEquals(advancement, rules(ADVANCEMENT, advancement, "1.18.2", "1.20.4"), "1.20.4 still reads nbt");
    }

    private static JsonObject itemPredicate(String advancement) {
        return parse(advancement).getAsJsonObject("criteria").getAsJsonObject("get")
                .getAsJsonObject("conditions").getAsJsonArray("items").get(0).getAsJsonObject();
    }

    // ---- loot ---------------------------------------------------------------------------------

    @Test
    @DisplayName("Looting functions and conditions become enchantment ones on 1.21 and stay on 1.20.6")
    void lootingBecomesAnEnchantment() {
        String loot = "{\"pools\":[{\"rolls\":1,\"conditions\":[{\"condition\":\"minecraft:random_chance_with_looting\","
                + "\"chance\":0.025,\"looting_multiplier\":0.01}],\"entries\":[{\"type\":\"minecraft:item\","
                + "\"name\":\"mod:fang\",\"functions\":[{\"function\":\"minecraft:looting_enchant\","
                + "\"count\":{\"type\":\"minecraft:uniform\",\"min\":0,\"max\":1}}]}]}]}";

        assertEquals(loot, rules(LOOT, loot, "1.20.1", "1.20.6"));

        JsonObject pool = parse(rules(LOOT, loot, "1.20.1", "1.21")).getAsJsonArray("pools").get(0).getAsJsonObject();
        JsonObject chance = pool.getAsJsonArray("conditions").get(0).getAsJsonObject();
        assertEquals("minecraft:random_chance_with_enchanted_bonus", chance.get("condition").getAsString());
        assertEquals(0.025, chance.get("unenchanted_chance").getAsDouble());
        assertEquals("{\"type\":\"minecraft:linear\",\"base\":0.035,\"per_level_above_first\":0.01}",
                chance.get("enchanted_chance").toString(), "the old chance at looting I, as vanilla wrote it");
        JsonObject function = pool.getAsJsonArray("entries").get(0).getAsJsonObject()
                .getAsJsonArray("functions").get(0).getAsJsonObject();
        assertEquals("minecraft:enchanted_count_increase", function.get("function").getAsString());
        assertEquals("minecraft:looting", function.get("enchantment").getAsString());
    }

    @Test
    @DisplayName("Enchanting choices become options on 1.21")
    void enchantingChoicesBecomeOptions() {
        String randomly = "{\"function\":\"minecraft:enchant_randomly\",\"enchantments\":[\"minecraft:binding_curse\"]}";
        String levels = "{\"function\":\"minecraft:enchant_with_levels\",\"levels\":30,\"treasure\":true}";

        assertTrue(rules("data/mod/item_modifier/a.json", randomly, "1.12.2", "1.21.1")
                .contains("\"options\":\"minecraft:binding_curse\""));
        assertTrue(rules("data/mod/item_modifier/b.json", levels, "1.20.1", "1.21.1")
                .contains("\"options\":\"#minecraft:on_random_loot\""));
        assertEquals(levels, rules("data/mod/item_modifier/b.json", levels, "1.20.1", "1.20.6"));
    }

    @Test
    @DisplayName("An enchanting function already written for 1.21 or later keeps its meaning")
    void currentEnchantingFunctionsStay() {
        String randomly = "{\"function\":\"minecraft:enchant_randomly\"}";
        String levels = "{\"function\":\"minecraft:enchant_with_levels\",\"levels\":30}";
        String file = "data/mod/item_modifier/a.json";

        for (String function : new String[] {randomly, levels}) {
            assertEquals(function, rules(file, function, "1.21.1", "1.21.1"),
                    "without options 1.21 picks from every enchantment, mod ones included");
            assertEquals(function, rules(file, function, "1.21.1", "26.3"));
            assertEquals(function, rules(file, function, null, "26.3"), "an unknown age is not the old shape");
        }
        byte[] current = bytes(randomly);
        assertSame(current, ModDataMigrator.migrate(file, current, "1.21.1", "1.21.1",
                MigrationContext.Loader.NEOFORGE));

        assertTrue(rules(file, randomly, "1.20.6", "1.21.1").contains("\"options\":\"#minecraft:on_random_loot\""),
                "vanilla 1.21 gave its own option-less functions this tag");
        assertTrue(rules(file, "{\"function\":\"minecraft:enchant_randomly\",\"enchantments\":[]}", null, "1.21.1")
                .contains("\"options\":\"#minecraft:on_random_loot\""), "the old key is the old shape");
    }

    @Test
    @DisplayName("A nested loot table names its table by value from 1.20.5")
    void nestedLootTableValue() {
        String loot = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:loot_table\","
                + "\"name\":\"mod:chests/extra\"}]}]}";

        assertTrue(rules(LOOT, loot, "1.20.1", "1.20.5").contains("\"value\":\"mod:chests/extra\""));
        assertEquals(loot, rules(LOOT, loot, "1.20.1", "1.20.4"));
    }

    @Test
    @DisplayName("1.21 renamed the killer entity targets, which it reads only by the new names")
    void killerTargetsBecomeAttackers() {
        String loot = "{\"pools\":[{\"rolls\":1,\"conditions\":[{\"condition\":\"minecraft:entity_properties\","
                + "\"entity\":\"killer\",\"predicate\":{\"type\":\"#mod:dragons\"}}],\"entries\":[]}]}";

        assertTrue(rules(LOOT, loot, "1.18.2", "1.21").contains("\"entity\":\"attacker\""));
        assertEquals(loot, rules(LOOT, loot, "1.18.2", "1.20.6"));
    }

    @Test
    @DisplayName("A 1.12 entity_properties check of on_fire becomes the flags predicate")
    void legacyEntityProperties() {
        String loot = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"item\",\"name\":\"mod:pork\","
                + "\"functions\":[{\"function\":\"furnace_smelt\",\"conditions\":[{\"condition\":"
                + "\"entity_properties\",\"entity\":\"this\",\"properties\":{\"on_fire\":true}}]}]}]}]}";

        assertTrue(rules(LOOT, loot, "1.12.2", "1.21.1")
                .contains("\"predicate\":{\"flags\":{\"is_on_fire\":true}}"));
    }

    // ---- advancements -------------------------------------------------------------------------

    @Test
    @DisplayName("A bare 1.12 location trigger becomes a player condition with a namespaced dimension")
    void legacyLocationTrigger() {
        String advancement = "{\"criteria\":{\"home\":{\"trigger\":\"minecraft:location\","
                + "\"conditions\":{\"dimension\":\"overworld\"}}}}";

        JsonObject conditions = parse(rules(ADVANCEMENT, advancement, "1.12.2", "1.21.1"))
                .getAsJsonObject("criteria").getAsJsonObject("home").getAsJsonObject("conditions");

        JsonObject player = conditions.getAsJsonArray("player").get(0).getAsJsonObject();
        assertEquals("minecraft:entity_properties", player.get("condition").getAsString());
        assertEquals("minecraft:overworld", player.getAsJsonObject("predicate").getAsJsonObject("location")
                .get("dimension").getAsString());
    }

    @Test
    @DisplayName("A structure feature becomes the structure 1.19 named, and a list on 1.20.5")
    void featureBecomesStructure() {
        String advancement = "{\"criteria\":{\"in\":{\"trigger\":\"minecraft:location\","
                + "\"conditions\":{\"feature\":\"mod:temple\"}}}}";

        assertTrue(rules(ADVANCEMENT, advancement, "1.18.2", "1.19.2").contains("\"structure\":\"mod:temple\""));
        assertTrue(rules(ADVANCEMENT, advancement, "1.18.2", "26.3").contains("\"structures\":\"mod:temple\""));

        String village = advancement.replace("mod:temple", "village");
        assertTrue(rules(ADVANCEMENT, village, "1.18.2", "1.21.1").contains("\"structures\":\"#minecraft:village\""),
                "1.19 split the village into one structure per biome, which its tag gathers");
        assertEquals(village, rules(ADVANCEMENT, village, "1.18.2", "1.19.2"),
                "1.19 to 1.20.4 take one structure, which cannot hold the tag");
    }

    @Test
    @DisplayName("Backgrounds name a texture id from 1.21.5")
    void backgroundsBecomeTextureIds() {
        String advancement = "{\"display\":{\"icon\":{\"id\":\"mod:map\"},\"title\":\"x\",\"description\":\"y\","
                + "\"background\":\"mod:textures/gui/stone.png\"},\"criteria\":{}}";

        assertTrue(rules(ADVANCEMENT, advancement, "1.20.1", "1.21.5").contains("\"background\":\"mod:gui/stone\""));
        assertEquals(advancement, rules(ADVANCEMENT, advancement, "1.20.1", "1.21.4"));
    }

    @Test
    @DisplayName("On 26.3 a bare entity predicate in a trigger becomes an entity_properties condition")
    void bareEntityPredicatesAreWrappedOn26_3() {
        String advancement = "{\"criteria\":{\"kill\":{\"trigger\":\"minecraft:player_killed_entity\","
                + "\"conditions\":{\"entity\":{\"type\":\"mod:dragon\"}}}}}";

        JsonObject entity = migrated(ADVANCEMENT, advancement, "1.20.1", "26.3", MigrationContext.Loader.NEOFORGE)
                .getAsJsonObject("criteria").getAsJsonObject("kill").getAsJsonObject("conditions")
                .getAsJsonObject("entity");
        assertEquals("minecraft:entity_properties", entity.get("type").getAsString());
        assertEquals("mod:dragon", entity.getAsJsonObject("predicate").get("minecraft:entity_type").getAsString());

        JsonObject on262 = migrated(ADVANCEMENT, advancement, "1.20.1", "26.2", MigrationContext.Loader.NEOFORGE)
                .getAsJsonObject("criteria").getAsJsonObject("kill").getAsJsonObject("conditions")
                .getAsJsonObject("entity");
        assertEquals("{\"minecraft:entity_type\":\"mod:dragon\"}", on262.toString(),
                "26.2 still reads the bare predicate, with 26.2's keys");
    }

    // ---- every rule ---------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1.20.5", "1.21.1", "1.21.5", "26.3"})
    @DisplayName("Running twice changes nothing more")
    void idempotent(String target) {
        String loot = "{\"pools\":[{\"rolls\":1,\"conditions\":[{\"condition\":\"minecraft:random_chance_with_looting\","
                + "\"chance\":0.5,\"looting_multiplier\":0.1}],\"entries\":[{\"type\":\"minecraft:item\","
                + "\"name\":\"mod:fang\",\"functions\":[{\"function\":\"minecraft:set_nbt\","
                + "\"tag\":\"{Unbreakable:1b,Owner:\\\"x\\\"}\"},{\"function\":\"minecraft:looting_enchant\","
                + "\"count\":1}]},{\"type\":\"minecraft:loot_table\",\"name\":\"mod:extra\"}]}]}";

        byte[] once = ModDataMigrator.migrate(LOOT, bytes(loot), "1.18.2", target, MigrationContext.Loader.FABRIC);
        byte[] twice = ModDataMigrator.migrate(LOOT, once, "1.18.2", target, MigrationContext.Loader.FABRIC);

        assertEquals(text(once), text(twice));
    }

    @Test
    @DisplayName("A loot table already written for 26.3 comes back byte for byte")
    void currentDataIsUntouched() {
        String modern = "{\n  \"type\": \"minecraft:entity\",\n  \"pools\": [\n    {\n      \"condition\": {\n"
                + "        \"type\": \"minecraft:random_chance_with_enchanted_bonus\",\n        \"enchanted_chance\": "
                + "{\"type\": \"minecraft:linear\", \"base\": 0.035, \"per_level_above_first\": 0.01},\n"
                + "        \"enchantment\": \"minecraft:looting\",\n        \"unenchanted_chance\": 0.025\n      },\n"
                + "      \"entries\": [{\"type\": \"minecraft:item\", \"name\": \"minecraft:wither_skeleton_skull\","
                + " \"modifier\": [{\"type\": \"minecraft:set_components\", \"components\": "
                + "{\"minecraft:custom_data\": \"{a:1b}\"}}]}],\n      \"rolls\": 1\n    }\n  ]\n}\n";
        byte[] in = bytes(modern);

        assertSame(in, ModDataMigrator.migrate("data/mod/loot_table/entities/skull.json", in, "26.3", "26.3",
                MigrationContext.Loader.NEOFORGE));
    }
}
