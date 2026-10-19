/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Checks that a loot table, predicate, item modifier or advancement is in the shape Minecraft 26.3 reads. */
final class RegistryDataShapes {

    /** Why a file is not in the shape the host reads. */
    static final class Problem extends Exception {
        Problem(String message) {
            super(message, null, false, false);
        }
    }

    // 26.3 loads these files with the registries, and one it cannot read stops the world from loading.
    // So a check passes only what it knows: the loot entries, functions, conditions and number
    // providers old mods use and the data conversions write, current item, entity and location
    // predicates, and the advancement triggers it reads field by field. The names below were read
    // from the 26.3 client jar's registration classes. Anything else is a problem, and the file stays out.
    private static final Pattern IDENTIFIER = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");

    private static final Set<String> LOOT_TABLE_TYPES = vanilla("empty", "generic", "chest", "command",
            "selector", "villager_trade", "fishing", "entity", "equipment", "archaeology", "gift", "barter",
            "vault", "advancement_reward", "advancement_entity", "advancement_location", "block_use", "block",
            "shearing", "entity_interact", "block_interact", "container_process", "enchanted_damage",
            "enchanted_item", "enchanted_location", "enchanted_entity", "hit_block");

    private static final Set<String> COMPOSITE_ENTRIES = vanilla("alternatives", "sequence", "group");
    /** Fields any entry may have, with Forge 1.12's {@code entryName} label, which nothing reads now. */
    private static final Set<String> ENTRY_COMMON = Set.of("type", "condition", "modifier", "weight", "quality",
            "entryName");

    private static final Set<String> BONUS_FORMULAS = vanilla("ore_drops", "uniform_bonus_count",
            "binomial_with_bonus_count");

    private static final Set<String> ENTITY_TARGETS = Set.of("this", "attacker", "direct_attacker",
            "attacking_player", "target_entity", "interacting_entity");

    private static final Set<String> ENTITY_FLAGS = Set.of("is_on_ground", "is_on_fire", "is_sneaking",
            "is_sprinting", "is_swimming", "is_flying", "is_baby", "is_in_water", "is_fall_flying");

    private static final Set<String> EQUIPMENT_SLOTS = Set.of("head", "chest", "legs", "feet", "body",
            "mainhand", "offhand");

    private static final Set<String> DIMENSIONS_AXES = Set.of("x", "y", "z", "horizontal", "absolute");

    private final Set<String> registeredNamespaces;
    private final String loaderConditionKey;

    // What the checked files refer to by id, so the gate can keep out a file whose reference is missing.
    final Set<String> recipes = new TreeSet<>();
    final Set<String> lootTables = new TreeSet<>();
    final Set<String> advancements = new TreeSet<>();
    final Set<String> predicates = new TreeSet<>();
    final Set<String> structures = new TreeSet<>();
    final Set<String> biomes = new TreeSet<>();

    /** Takes the namespaces the mod registers itself, and the target loader's load condition key or null. */
    RegistryDataShapes(Set<String> registeredNamespaces, String loaderConditionKey) {
        this.registeredNamespaces = registeredNamespaces;
        this.loaderConditionKey = loaderConditionKey;
    }

    // ---- files --------------------------------------------------------------------------------

    // The four file kinds DataLoadGate checks before it lets a converted copy into a world. Each
    // throws a Problem at the first thing 26.3 would not read.
    void lootTable(JsonElement value) throws Problem {
        JsonObject table = object(value, "the loot table");
        allowLoaderKey(table, "type", "pools", "modifier", "random_sequence");
        // A typed table must use a loot context type the host has.
        String type = optionalString(table, "type");
        if (type != null && !LOOT_TABLE_TYPES.contains(namespaced(type))) {
            throw new Problem("the loot table type " + type + " is not one the host has");
        }
        if (table.has("pools")) {
            for (JsonElement pool : array(table.get("pools"), "pools")) pool(pool);
        }
        if (table.has("modifier")) functions(table.get("modifier"));
        if (table.has("random_sequence")) identifier(table.get("random_sequence"));
    }

    void predicate(JsonElement value) throws Problem {
        // A predicate file holds one condition or a list of them.
        if (value instanceof JsonArray list) {
            for (JsonElement condition : list) condition(condition);
        } else {
            condition(value);
        }
    }

    void itemModifier(JsonElement value) throws Problem {
        functions(value);
    }

    void advancement(JsonElement value) throws Problem {
        JsonObject advancement = object(value, "the advancement");
        allowLoaderKey(advancement, "parent", "display", "criteria", "requirements", "rewards",
                "sends_telemetry_event");
        if (advancement.has("parent")) advancements.add(identifier(advancement.get("parent")));
        if (advancement.has("display")) display(advancement.get("display"));
        // It needs at least one criterion, and every requirement must name one of them.
        JsonObject criteria = object(advancement.get("criteria"), "criteria");
        if (criteria.isEmpty()) throw new Problem("the advancement has no criteria");
        for (Map.Entry<String, JsonElement> criterion : criteria.entrySet()) criterion(criterion.getValue());
        if (advancement.has("requirements")) {
            for (JsonElement group : array(advancement.get("requirements"), "requirements")) {
                for (JsonElement name : array(group, "a requirement")) {
                    if (!criteria.has(string(name, "a requirement"))) {
                        throw new Problem("a requirement names no criterion");
                    }
                }
            }
        }
        if (advancement.has("rewards")) rewards(advancement.get("rewards"));
        if (advancement.has("sends_telemetry_event")) bool(advancement.get("sends_telemetry_event"));
    }

    // ---- loot tables --------------------------------------------------------------------------

    private void pool(JsonElement value) throws Problem {
        JsonObject pool = object(value, "a pool");
        allow(pool, "rolls", "bonus_rolls", "entries", "condition", "modifier", "name");
        // A pool must roll. 26.3 reads rolls as a whole number and bonus rolls as a fraction.
        if (!pool.has("rolls")) throw new Problem("a pool has no rolls");
        intProvider(pool.get("rolls"));
        if (pool.has("bonus_rolls")) floatProvider(pool.get("bonus_rolls"));
        if (pool.has("entries")) {
            for (JsonElement entry : array(pool.get("entries"), "entries")) entry(entry);
        }
        if (pool.has("condition")) condition(pool.get("condition"));
        if (pool.has("modifier")) functions(pool.get("modifier"));
        if (pool.has("name")) string(pool.get("name"), "a pool name");
    }

    private void entry(JsonElement value) throws Problem {
        JsonObject entry = object(value, "an entry");
        String type = namespaced(requiredString(entry, "type"));
        // Each entry type allows its own fields on top of the common ones.
        switch (type) {
            case "minecraft:item" -> {
                allowWith(entry, ENTRY_COMMON, "name");
                registryId("item", requiredString(entry, "name"));
            }
            case "minecraft:tag" -> {
                allowWith(entry, ENTRY_COMMON, "items", "expand");
                String tag = requiredString(entry, "items");
                if (!tag.startsWith("#")) throw new Problem("a tag entry names no tag");
                tag(tag.substring(1));
                if (entry.has("expand")) bool(entry.get("expand"));
            }
            case "minecraft:loot_table" -> {
                allowWith(entry, ENTRY_COMMON, "value");
                // An inline table is checked in place. An id is a reference the gate looks for.
                JsonElement table = entry.get("value");
                if (table instanceof JsonObject inline) {
                    lootTable(inline);
                } else {
                    lootTables.add(identifier(table));
                }
            }
            case "minecraft:dynamic" -> {
                allowWith(entry, ENTRY_COMMON, "name");
                requiredString(entry, "name");
            }
            case "minecraft:empty" -> allowWith(entry, ENTRY_COMMON);
            default -> {
                // Alternatives, sequences and groups hold child entries, checked the same way.
                if (!COMPOSITE_ENTRIES.contains(type)) throw new Problem("the entry type " + type + " is not checked");
                allowWith(entry, ENTRY_COMMON, "children");
                for (JsonElement child : array(entry.get("children"), "children")) entry(child);
            }
        }
        // Then the fields every entry shares.
        if (entry.has("entryName")) string(entry.get("entryName"), "an entry name");
        if (entry.has("weight")) integer(entry.get("weight"));
        if (entry.has("quality")) integer(entry.get("quality"));
        if (entry.has("condition")) condition(entry.get("condition"));
        if (entry.has("modifier")) functions(entry.get("modifier"));
    }

    /** One loot function or a list of them. */
    private void functions(JsonElement value) throws Problem {
        if (value instanceof JsonArray list) {
            for (JsonElement function : list) function(function);
        } else {
            function(value);
        }
    }

    private void function(JsonElement value) throws Problem {
        JsonObject function = object(value, "a loot function");
        String type = namespaced(requiredString(function, "type"));
        // Each function type allows only the fields 26.3 reads for it, plus type and condition.
        switch (type) {
            case "minecraft:set_count" -> {
                allowFunction(function, "count", "add");
                intProvider(required(function, "count"));
                if (function.has("add")) bool(function.get("add"));
            }
            case "minecraft:set_damage" -> {
                allowFunction(function, "damage", "add");
                floatProvider(required(function, "damage"));
                if (function.has("add")) bool(function.get("add"));
            }
            case "minecraft:explosion_decay" -> allowFunction(function);
            case "minecraft:furnace_smelt" -> {
                allowFunction(function, "use_input_count");
                if (function.has("use_input_count")) bool(function.get("use_input_count"));
            }
            case "minecraft:apply_bonus" -> {
                allowFunction(function, "enchantment", "formula", "parameters");
                registryId("enchantment", requiredString(function, "enchantment"));
                if (!BONUS_FORMULAS.contains(namespaced(requiredString(function, "formula")))) {
                    throw new Problem("apply_bonus names a formula the host does not have");
                }
                if (function.has("parameters")) object(function.get("parameters"), "parameters");
            }
            case "minecraft:enchanted_count_increase" -> {
                allowFunction(function, "enchantment", "count", "limit");
                registryId("enchantment", requiredString(function, "enchantment"));
                floatProvider(required(function, "count"));
                if (function.has("limit")) integer(function.get("limit"));
            }
            case "minecraft:enchant_randomly", "minecraft:enchant_with_levels" -> {
                boolean levels = type.equals("minecraft:enchant_with_levels");
                allowFunction(function, "options", "only_compatible", "include_additional_cost_component",
                        "levels");
                if (levels) intProvider(required(function, "levels"));
                else if (function.has("levels")) throw new Problem("enchant_randomly has levels");
                if (function.has("options")) holderSet("enchantment", function.get("options"));
                if (function.has("only_compatible")) bool(function.get("only_compatible"));
                if (function.has("include_additional_cost_component")) {
                    bool(function.get("include_additional_cost_component"));
                }
            }
            case "minecraft:limit_count" -> {
                allowFunction(function, "limit");
                JsonObject limit = object(required(function, "limit"), "a limit");
                allow(limit, "min", "max");
                for (JsonElement bound : limit.asMap().values()) intProvider(bound);
            }
            case "minecraft:copy_name" -> {
                allowFunction(function, "source");
                requiredString(function, "source");
            }
            case "minecraft:set_name" -> {
                allowFunction(function, "name", "target", "entity");
                text(required(function, "name"));
            }
            case "minecraft:set_lore" -> {
                allowFunction(function, "lore", "entity", "mode", "offset", "size");
                for (JsonElement line : array(required(function, "lore"), "lore")) text(line);
            }
            case "minecraft:copy_state" -> {
                allowFunction(function, "block", "properties");
                registryId("block", requiredString(function, "block"));
                for (JsonElement property : array(required(function, "properties"), "properties")) {
                    string(property, "a block state property");
                }
            }
            case "minecraft:set_custom_data" -> {
                allowFunction(function, "tag");
                // The tag is NBT, written as an object or as SNBT text.
                JsonElement tag = required(function, "tag");
                if (!(tag instanceof JsonObject) && !isString(tag)) throw new Problem("set_custom_data has no tag");
            }
            case "minecraft:set_components" -> {
                allowFunction(function, "components");
                object(required(function, "components"), "components");
            }
            case "minecraft:copy_custom_data" -> {
                allowFunction(function, "source", "ops");
                required(function, "source");
                for (JsonElement op : array(required(function, "ops"), "ops")) {
                    allow(object(op, "a copy operation"), "source", "target", "op");
                }
            }
            case "minecraft:set_potion" -> {
                allowFunction(function, "id");
                registryId("potion", requiredString(function, "id"));
            }
            default -> throw new Problem("the loot function " + type + " is not checked");
        }
        if (function.has("condition")) condition(function.get("condition"));
    }

    // ---- conditions and predicates ------------------------------------------------------------

    private void condition(JsonElement value) throws Problem {
        // A plain string names a predicate file by id, which the gate looks for.
        if (isString(value)) {
            predicates.add(identifier(value));
            return;
        }
        JsonObject condition = object(value, "a condition");
        String type = namespaced(requiredString(condition, "type"));
        // Each condition type allows only the fields 26.3 reads for it.
        switch (type) {
            case "minecraft:inverted" -> {
                allow(condition, "type", "term");
                condition(required(condition, "term"));
            }
            case "minecraft:any_of", "minecraft:all_of" -> {
                allow(condition, "type", "terms");
                for (JsonElement term : array(required(condition, "terms"), "terms")) condition(term);
            }
            case "minecraft:random_chance" -> {
                allow(condition, "type", "chance");
                floatProvider(required(condition, "chance"));
            }
            case "minecraft:random_chance_with_enchanted_bonus" -> {
                allow(condition, "type", "enchantment", "unenchanted_chance", "enchanted_chance");
                registryId("enchantment", requiredString(condition, "enchantment"));
                plainNumber(required(condition, "unenchanted_chance"));
                levelBasedValue(required(condition, "enchanted_chance"));
            }
            case "minecraft:killed_by_player", "minecraft:survives_explosion" -> allow(condition, "type");
            case "minecraft:table_bonus" -> {
                allow(condition, "type", "enchantment", "chances");
                registryId("enchantment", requiredString(condition, "enchantment"));
                for (JsonElement chance : array(required(condition, "chances"), "chances")) plainNumber(chance);
            }
            case "minecraft:match_tool" -> {
                allow(condition, "type", "predicate");
                if (condition.has("predicate")) itemPredicate(condition.get("predicate"));
            }
            case "minecraft:entity_properties" -> {
                allow(condition, "type", "entity", "predicate");
                if (!ENTITY_TARGETS.contains(requiredString(condition, "entity"))) {
                    throw new Problem("entity_properties names an entity the host does not have");
                }
                if (condition.has("predicate")) entityPredicate(condition.get("predicate"));
            }
            case "minecraft:match_block" -> {
                allow(condition, "type", "blocks", "state");
                holderSet("block", required(condition, "blocks"));
                if (condition.has("state")) object(condition.get("state"), "a block state");
            }
            case "minecraft:location_check" -> {
                allow(condition, "type", "predicate", "offsetX", "offsetY", "offsetZ");
                if (condition.has("predicate")) locationPredicate(condition.get("predicate"));
                for (String offset : new String[] {"offsetX", "offsetY", "offsetZ"}) {
                    if (condition.has(offset)) integer(condition.get(offset));
                }
            }
            case "minecraft:weather_check" -> {
                allow(condition, "type", "raining", "thundering");
                for (String flag : new String[] {"raining", "thundering"}) {
                    if (condition.has(flag)) bool(condition.get(flag));
                }
            }
            case "minecraft:damage_source_properties" -> {
                allow(condition, "type", "predicate");
                if (condition.has("predicate")) damageSourcePredicate(condition.get("predicate"));
            }
            default -> throw new Problem("the loot condition " + type + " is not checked");
        }
    }

    private void itemPredicate(JsonElement value) throws Problem {
        JsonObject predicate = object(value, "an item predicate");
        allow(predicate, "items", "count", "components", "predicates");
        if (predicate.has("items")) holderSet("item", predicate.get("items"));
        if (predicate.has("count")) range(predicate.get("count"));
        if (predicate.has("components")) object(predicate.get("components"), "components");
        // Sub-predicates are checked by type. One this class does not know is a problem.
        if (!predicate.has("predicates")) return;
        for (Map.Entry<String, JsonElement> sub : object(predicate.get("predicates"), "predicates").entrySet()) {
            switch (namespaced(sub.getKey())) {
                case "minecraft:custom_data" -> {
                    // Custom data is NBT, written as an object or as SNBT text.
                    if (!(sub.getValue() instanceof JsonObject) && !isString(sub.getValue())) {
                        throw new Problem("a custom data predicate is not NBT");
                    }
                }
                case "minecraft:enchantments", "minecraft:stored_enchantments" -> {
                    for (JsonElement enchantment : array(sub.getValue(), "enchantment predicates")) {
                        JsonObject entry = object(enchantment, "an enchantment predicate");
                        allow(entry, "enchantments", "levels");
                        if (entry.has("enchantments")) holderSet("enchantment", entry.get("enchantments"));
                        if (entry.has("levels")) range(entry.get("levels"));
                    }
                }
                case "minecraft:potion_contents" -> holderSet("potion", sub.getValue());
                case "minecraft:damage" -> {
                    JsonObject damage = object(sub.getValue(), "a damage predicate");
                    allow(damage, "durability", "damage");
                    for (JsonElement bound : damage.asMap().values()) range(bound);
                }
                default -> throw new Problem("the item sub-predicate " + sub.getKey() + " is not checked");
            }
        }
    }

    private void entityPredicate(JsonElement value) throws Problem {
        JsonObject predicate = object(value, "an entity predicate");
        // Every field is a sub-predicate, checked by its type. Flags, slots and distance axes must
        // be ones the host has.
        for (Map.Entry<String, JsonElement> sub : predicate.entrySet()) {
            JsonElement part = sub.getValue();
            switch (namespaced(sub.getKey())) {
                case "minecraft:entity_type" -> holderSet("entity_type", part);
                case "minecraft:flags" -> {
                    JsonObject flags = object(part, "entity flags");
                    for (Map.Entry<String, JsonElement> flag : flags.entrySet()) {
                        if (!ENTITY_FLAGS.contains(flag.getKey())) throw new Problem("unknown entity flag " + flag.getKey());
                        bool(flag.getValue());
                    }
                }
                case "minecraft:location", "minecraft:stepping_on", "minecraft:movement_affected_by" ->
                        locationPredicate(part);
                case "minecraft:equipment" -> {
                    for (Map.Entry<String, JsonElement> slot : object(part, "equipment").entrySet()) {
                        if (!EQUIPMENT_SLOTS.contains(slot.getKey())) throw new Problem("unknown slot " + slot.getKey());
                        itemPredicate(slot.getValue());
                    }
                }
                case "minecraft:distance" -> {
                    JsonObject distance = object(part, "a distance");
                    for (Map.Entry<String, JsonElement> axis : distance.entrySet()) {
                        if (!DIMENSIONS_AXES.contains(axis.getKey())) throw new Problem("unknown distance " + axis.getKey());
                        range(axis.getValue());
                    }
                }
                case "minecraft:vehicle", "minecraft:passenger", "minecraft:targeted_entity" -> entityPredicate(part);
                case "minecraft:periodic_tick" -> integer(part);
                case "minecraft:nbt", "minecraft:team" -> string(part, sub.getKey());
                default -> throw new Problem("the entity sub-predicate " + sub.getKey() + " is not checked");
            }
        }
    }

    private void locationPredicate(JsonElement value) throws Problem {
        JsonObject location = object(value, "a location predicate");
        allow(location, "position", "biomes", "structures", "dimension", "smokey", "light", "block",
                "can_see_sky");
        if (location.has("position")) {
            JsonObject position = object(location.get("position"), "a position");
            allow(position, "x", "y", "z");
            for (JsonElement bound : position.asMap().values()) range(bound);
        }
        if (location.has("biomes")) holderSet("biome", location.get("biomes"));
        if (location.has("structures")) holderSet("structure", location.get("structures"));
        if (location.has("dimension")) identifier(location.get("dimension"));
        for (String flag : new String[] {"smokey", "can_see_sky"}) {
            if (location.has(flag)) bool(location.get(flag));
        }
        if (location.has("light")) {
            JsonObject light = object(location.get("light"), "a light predicate");
            allow(light, "light");
            if (light.has("light")) range(light.get("light"));
        }
        if (location.has("block")) {
            JsonObject block = object(location.get("block"), "a block predicate");
            allow(block, "blocks", "state", "nbt");
            if (block.has("blocks")) holderSet("block", block.get("blocks"));
            if (block.has("state")) object(block.get("state"), "a block state");
            if (block.has("nbt")) string(block.get("nbt"), "block NBT");
        }
    }

    private void damageSourcePredicate(JsonElement value) throws Problem {
        JsonObject predicate = object(value, "a damage source predicate");
        allow(predicate, "tags", "direct_entity", "source_entity", "is_direct");
        // Each tag check names a damage type tag with a leading #, and whether it is expected.
        if (predicate.has("tags")) {
            for (JsonElement tag : array(predicate.get("tags"), "damage tags")) {
                JsonObject check = object(tag, "a damage tag");
                allow(check, "id", "expected");
                String id = requiredString(check, "id");
                if (!id.startsWith("#")) throw new Problem("a damage tag names no tag");
                tag(id.substring(1));
                bool(required(check, "expected"));
            }
        }
        for (String entity : new String[] {"direct_entity", "source_entity"}) {
            if (predicate.has(entity)) entityPredicate(predicate.get(entity));
        }
        if (predicate.has("is_direct")) bool(predicate.get("is_direct"));
    }

    // ---- advancements -------------------------------------------------------------------------

    private void display(JsonElement value) throws Problem {
        JsonObject display = object(value, "the display");
        allow(display, "icon", "title", "description", "background", "frame", "show_toast",
                "announce_to_chat", "hidden");
        // The icon is an item stack: an item id, with an optional count and components.
        JsonObject icon = object(required(display, "icon"), "the icon");
        allow(icon, "id", "count", "components");
        registryId("item", requiredString(icon, "id"));
        if (icon.has("count")) integer(icon.get("count"));
        if (icon.has("components")) object(icon.get("components"), "components");
        text(required(display, "title"));
        text(required(display, "description"));
        // 26.3 reads the background as a texture id, not a file path, so no textures/ folder or .png.
        if (display.has("background")) {
            String background = identifier(display.get("background"));
            if (background.contains(":textures/") || background.endsWith(".png")) {
                throw new Problem("the background is a texture path");
            }
        }
        if (display.has("frame") && !Set.of("task", "goal", "challenge").contains(
                requiredString(display, "frame"))) {
            throw new Problem("the frame is not one the host has");
        }
        for (String flag : new String[] {"show_toast", "announce_to_chat", "hidden"}) {
            if (display.has(flag)) bool(display.get(flag));
        }
    }

    private void criterion(JsonElement value) throws Problem {
        JsonObject criterion = object(value, "a criterion");
        allow(criterion, "trigger", "conditions");
        String trigger = namespaced(requiredString(criterion, "trigger"));
        // Each trigger allows only the condition fields 26.3 reads for it. No conditions counts as none set.
        JsonObject conditions = criterion.has("conditions")
                ? object(criterion.get("conditions"), "conditions") : new JsonObject();
        switch (trigger) {
            case "minecraft:impossible" -> allow(conditions);
            case "minecraft:tick", "minecraft:location", "minecraft:slept_in_bed",
                 "minecraft:hero_of_the_village", "minecraft:voluntary_exile" -> allow(conditions, "player");
            case "minecraft:inventory_changed" -> {
                allow(conditions, "player", "items", "slots");
                if (conditions.has("items")) {
                    for (JsonElement item : array(conditions.get("items"), "items")) itemPredicate(item);
                }
                if (conditions.has("slots")) {
                    JsonObject slots = object(conditions.get("slots"), "slots");
                    allow(slots, "occupied", "full", "empty");
                    for (JsonElement bound : slots.asMap().values()) range(bound);
                }
            }
            // Recipes and loot tables a trigger names are references the gate looks for.
            case "minecraft:recipe_unlocked" -> {
                allow(conditions, "player", "recipes");
                references(recipes, required(conditions, "recipes"));
            }
            case "minecraft:brewed_potion" -> {
                allow(conditions, "player", "potion");
                if (conditions.has("potion")) registryId("potion", requiredString(conditions, "potion"));
            }
            case "minecraft:consume_item", "minecraft:using_item", "minecraft:filled_bucket" -> {
                allow(conditions, "player", "item");
                if (conditions.has("item")) itemPredicate(conditions.get("item"));
            }
            case "minecraft:enchanted_item" -> {
                allow(conditions, "player", "item", "levels");
                if (conditions.has("item")) itemPredicate(conditions.get("item"));
                if (conditions.has("levels")) range(conditions.get("levels"));
            }
            case "minecraft:changed_dimension" -> {
                allow(conditions, "player", "from", "to");
                if (conditions.has("from")) identifier(conditions.get("from"));
                if (conditions.has("to")) identifier(conditions.get("to"));
            }
            case "minecraft:enter_block", "minecraft:slide_down_block" -> {
                allow(conditions, "player", "blocks", "state");
                if (conditions.has("blocks")) holderSet("block", conditions.get("blocks"));
                if (conditions.has("state")) object(conditions.get("state"), "a block state");
            }
            case "minecraft:bee_nest_destroyed" -> {
                allow(conditions, "player", "blocks", "state", "item", "num_bees_inside");
                if (conditions.has("blocks")) holderSet("block", conditions.get("blocks"));
                if (conditions.has("state")) object(conditions.get("state"), "a block state");
                if (conditions.has("item")) itemPredicate(conditions.get("item"));
                if (conditions.has("num_bees_inside")) range(conditions.get("num_bees_inside"));
            }
            case "minecraft:player_generates_container_loot" -> {
                allow(conditions, "player", "loot_tables");
                references(lootTables, required(conditions, "loot_tables"));
            }
            case "minecraft:player_killed_entity", "minecraft:entity_killed_player" -> {
                allow(conditions, "player", "entity", "killing_blow");
                if (conditions.has("entity")) condition(conditions.get("entity"));
                if (conditions.has("killing_blow")) damageSourcePredicate(conditions.get("killing_blow"));
            }
            case "minecraft:tame_animal", "minecraft:summoned_entity" -> {
                allow(conditions, "player", "entity");
                if (conditions.has("entity")) condition(conditions.get("entity"));
            }
            case "minecraft:bred_animals" -> {
                allow(conditions, "player", "parent", "partner", "child");
                for (String animal : new String[] {"parent", "partner", "child"}) {
                    if (conditions.has(animal)) condition(conditions.get(animal));
                }
            }
            case "minecraft:placed_block", "minecraft:item_used_on_block", "minecraft:default_block_use",
                 "minecraft:any_block_use" -> {
                allow(conditions, "player", "location");
                if (conditions.has("location")) condition(conditions.get("location"));
            }
            case "minecraft:recipe_crafted", "minecraft:crafter_recipe_crafted" -> {
                allow(conditions, "player", "recipes", "ingredients");
                references(recipes, required(conditions, "recipes"));
                if (conditions.has("ingredients")) {
                    for (JsonElement item : array(conditions.get("ingredients"), "ingredients")) itemPredicate(item);
                }
            }
            default -> throw new Problem("the trigger " + trigger + " is not checked");
        }
        if (conditions.has("player")) condition(conditions.get("player"));
    }

    private void rewards(JsonElement value) throws Problem {
        JsonObject rewards = object(value, "rewards");
        allow(rewards, "recipes", "loot", "experience", "function");
        // Rewarded recipes and loot tables are references the gate looks for.
        if (rewards.has("recipes")) {
            for (JsonElement recipe : array(rewards.get("recipes"), "recipes")) recipes.add(identifier(recipe));
        }
        if (rewards.has("loot")) {
            for (JsonElement table : array(rewards.get("loot"), "loot")) lootTables.add(identifier(table));
        }
        if (rewards.has("experience")) integer(rewards.get("experience"));
        if (rewards.has("function")) identifier(rewards.get("function"));
    }

    /** Adds the recipe or loot table ids a trigger reads, one or a list. A tag is not checked. */
    private static void references(Set<String> into, JsonElement value) throws Problem {
        if (value instanceof JsonArray list) {
            if (list.isEmpty()) throw new Problem("a trigger names an empty list");
            for (JsonElement id : list) into.add(identifier(id));
        } else {
            into.add(identifier(value));
        }
    }

    // ---- values -------------------------------------------------------------------------------

    /** A field 26.3 reads as a whole number: a number, or a constant, uniform or binomial one. */
    private void intProvider(JsonElement value) throws Problem {
        numberProvider(value, true);
    }

    /** A field 26.3 reads as a fraction: a number, or a constant or uniform one. */
    private void floatProvider(JsonElement value) throws Problem {
        numberProvider(value, false);
    }

    private void numberProvider(JsonElement value, boolean ints) throws Problem {
        if (value instanceof JsonPrimitive primitive && primitive.isNumber()) return;
        JsonObject provider = object(value, "a number");
        switch (namespaced(requiredString(provider, "type"))) {
            case "minecraft:constant" -> {
                allow(provider, "type", "value");
                plainNumber(required(provider, "value"));
            }
            case "minecraft:uniform" -> {
                allow(provider, "type", "min", "max");
                numberProvider(required(provider, "min"), ints);
                numberProvider(required(provider, "max"), ints);
            }
            case "minecraft:binomial" -> {
                // 26.3 split number providers into int and float kinds, and only the int kind has binomial.
                if (!ints) throw new Problem("a binomial provider sits in a field the host reads as a fraction");
                allow(provider, "type", "n", "p");
                intProvider(required(provider, "n"));
                floatProvider(required(provider, "p"));
            }
            default -> throw new Problem("the number provider " + provider.get("type") + " is not checked");
        }
    }

    /** An enchantment-level value: a number, or a linear one, as 1.21's looting rewrite writes. */
    private void levelBasedValue(JsonElement value) throws Problem {
        if (value instanceof JsonPrimitive primitive && primitive.isNumber()) return;
        JsonObject linear = object(value, "a level-based value");
        if (!"minecraft:linear".equals(namespaced(requiredString(linear, "type")))) {
            throw new Problem("the level-based value " + linear.get("type") + " is not checked");
        }
        allow(linear, "type", "base", "per_level_above_first");
        plainNumber(required(linear, "base"));
        plainNumber(required(linear, "per_level_above_first"));
    }

    /** A number or a {@code {min, max}} range of numbers. */
    private void range(JsonElement value) throws Problem {
        if (value instanceof JsonPrimitive primitive && primitive.isNumber()) return;
        JsonObject range = object(value, "a range");
        allow(range, "min", "max");
        for (JsonElement bound : range.asMap().values()) plainNumber(bound);
    }

    private static void plainNumber(JsonElement value) throws Problem {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) throw new Problem("a number is missing");
    }

    private static void integer(JsonElement value) throws Problem {
        plainNumber(value);
        if (value.getAsBigDecimal().stripTrailingZeros().scale() > 0) throw new Problem("a whole number has a fraction");
    }

    private static void bool(JsonElement value) throws Problem {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isBoolean()) throw new Problem("a flag is not true or false");
    }

    /** Text: a plain string, a component object, or a list of them. */
    private static void text(JsonElement value) throws Problem {
        if (!isString(value) && !(value instanceof JsonObject) && !(value instanceof JsonArray)) {
            throw new Problem("text is not a string or a component");
        }
    }

    /** One id, a {@code #tag}, or a list of ids, in {@code registry}. */
    private void holderSet(String registry, JsonElement value) throws Problem {
        if (isString(value)) {
            String id = value.getAsString();
            if (id.startsWith("#")) tag(id.substring(1));
            else registryId(registry, id);
            return;
        }
        JsonArray ids = array(value, "a list of " + registry + " ids");
        if (ids.isEmpty()) throw new Problem("an empty list of " + registry + " ids");
        for (JsonElement id : ids) registryId(registry, string(id, "a " + registry + " id"));
    }

    /** An id the host has in {@code registry} ({@link VanillaRegistryIds}), or one the mod adds. */
    private void registryId(String registry, String id) throws Problem {
        if (!IDENTIFIER.matcher(id).matches()) throw new Problem("the id " + id + " is not a valid identifier");
        String full = namespaced(id);
        String namespace = full.substring(0, full.indexOf(':'));
        if (namespace.equals("minecraft")) {
            if (!VanillaRegistryIds.contains(registry, full)) {
                throw new Problem("Minecraft has no " + registry + " " + full);
            }
        } else if (!registeredNamespaces.contains(namespace)) {
            throw new Problem("it names the " + registry + " " + full + " from a mod that may be missing");
        } else if (registry.equals("structure")) {
            // The mod's own worldgen data must be there, so the gate looks for it.
            structures.add(full);
        } else if (registry.equals("biome")) {
            biomes.add(full);
        }
    }

    /** A tag from Minecraft, the shared {@code c} namespace, or the mod; a missing tag is empty. */
    private void tag(String id) throws Problem {
        if (!IDENTIFIER.matcher(id).matches()) throw new Problem("the tag " + id + " is not a valid identifier");
        String namespace = namespaced(id).substring(0, namespaced(id).indexOf(':'));
        if (!namespace.equals("minecraft") && !namespace.equals("c") && !registeredNamespaces.contains(namespace)) {
            throw new Problem("it names the tag #" + id + " from a mod that may be missing");
        }
    }

    private static String identifier(JsonElement value) throws Problem {
        String id = string(value, "an id");
        if (!IDENTIFIER.matcher(id).matches()) throw new Problem("the id " + id + " is not a valid identifier");
        return namespaced(id);
    }

    // ---- structure ----------------------------------------------------------------------------

    /** Allows only {@code keys}, plus the key the target loader reads load conditions from. */
    private void allowLoaderKey(JsonObject object, String... keys) throws Problem {
        for (String key : object.keySet()) {
            if (key.equals(loaderConditionKey)) continue;
            if (!Set.of(keys).contains(key)) throw new Problem("it has the field " + key + ", which the host does not read");
        }
    }

    /** Allows only {@code keys}, plus the type and condition every loot function has. */
    private void allowFunction(JsonObject function, String... keys) throws Problem {
        Set<String> allowed = new TreeSet<>(Set.of(keys));
        allowed.add("type");
        allowed.add("condition");
        for (String key : function.keySet()) {
            if (!allowed.contains(key)) {
                throw new Problem("the loot function " + function.get("type") + " has the field " + key);
            }
        }
    }

    private static void allowWith(JsonObject object, Set<String> common, String... keys) throws Problem {
        for (String key : object.keySet()) {
            if (!common.contains(key) && !Set.of(keys).contains(key)) {
                throw new Problem("it has the field " + key + ", which the host does not read");
            }
        }
    }

    private static void allow(JsonObject object, String... keys) throws Problem {
        allowWith(object, Set.of(), keys);
    }

    private static JsonObject object(JsonElement value, String what) throws Problem {
        if (!(value instanceof JsonObject object)) throw new Problem(what + " is not an object");
        return object;
    }

    private static JsonArray array(JsonElement value, String what) throws Problem {
        if (!(value instanceof JsonArray array)) throw new Problem(what + " is not a list");
        return array;
    }

    private static JsonElement required(JsonObject object, String key) throws Problem {
        JsonElement value = object.get(key);
        if (value == null) throw new Problem("the field " + key + " is missing");
        return value;
    }

    private static String requiredString(JsonObject object, String key) throws Problem {
        return string(required(object, key), key);
    }

    private static String optionalString(JsonObject object, String key) throws Problem {
        return object.has(key) ? string(object.get(key), key) : null;
    }

    private static String string(JsonElement value, String what) throws Problem {
        if (!isString(value)) throw new Problem(what + " is not a string");
        return value.getAsString();
    }

    private static boolean isString(JsonElement value) {
        return value instanceof JsonPrimitive primitive && primitive.isString();
    }

    private static String namespaced(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    private static Set<String> vanilla(String... paths) {
        Set<String> ids = new TreeSet<>();
        for (String path : paths) ids.add("minecraft:" + path);
        return Set.copyOf(ids);
    }

    /** The loot condition types 26.3 registers, as its LootItemConditionTypes lists them. */
    private static final Set<String> CONDITION_TYPES = vanilla("inverted", "any_of", "all_of",
            "random_chance", "random_chance_with_enchanted_bonus", "entity_properties", "killed_by_player",
            "entity_scores", "match_block", "match_tool", "table_bonus", "survives_explosion",
            "damage_source_properties", "location_check", "weather_check", "time_check", "int_value_check",
            "float_value_check", "enchantment_active_check", "environment_attribute_check");

    /** Whether {@code type} names a loot condition 26.3 registers. */
    static boolean isLootConditionType(String type) {
        return type != null && CONDITION_TYPES.contains(namespaced(type));
    }
}
