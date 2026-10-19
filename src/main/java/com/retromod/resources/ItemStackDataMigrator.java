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
import com.retromod.resources.LegacyItemNbt.Compound;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rewrites the item stacks, item predicates and loot functions in a mod's data to the host's shapes. */
final class ItemStackDataMigrator {

    // The first host of each change, each read from the client jars on both sides of it.
    static final String FIRST_NAMESPACED_DIMENSION_HOST = "1.14";
    static final String FIRST_WRAPPED_LOCATION_HOST = "1.16";
    static final String FIRST_ITEMS_LIST_HOST = "1.17";
    static final String FIRST_PLAYER_LOCATION_HOST = "1.19";
    static final String FIRST_STRUCTURE_LOCATION_HOST = "1.19";
    static final String FIRST_ENCHANTMENT_OPTIONS_HOST = "1.21";
    static final String FIRST_TEXTURE_ID_BACKGROUND_HOST = "1.21.5";
    // 26.2's EntityPredicate still had the advancement codec that took a bare entity predicate.
    // 26.3's triggers read LootItemCondition.CODEC, so an entity field takes only a loot condition.
    static final String FIRST_CONDITION_ONLY_CRITERIA_HOST = "26.3";

    /** The killer targets 1.21 renamed, checked in the 1.20.5 and 1.21 client jars. */
    private static final Map<String, String> RENAMED_ENTITY_TARGETS = Map.of("killer", "attacker",
            "direct_killer", "direct_attacker", "killer_player", "attacking_player");

    /** Loot functions that name an entity target, by the field they name it in. */
    private static final Map<String, String> ENTITY_TARGET_FUNCTIONS = Map.of(
            "minecraft:copy_name", "source", "minecraft:copy_nbt", "source", "minecraft:set_name", "entity",
            "minecraft:set_lore", "entity", "minecraft:fill_player_head", "entity");

    /** Minecraft's structure features whose 1.19 structure has the same name. */
    private static final Set<String> SAME_NAME_STRUCTURES = Set.of("fortress", "mansion", "monument",
            "stronghold", "igloo", "swamp_hut", "desert_pyramid", "jungle_pyramid", "pillager_outpost",
            "buried_treasure", "nether_fossil", "bastion_remnant");

    /** Minecraft's structure features that 1.19 split, each with a structure tag of its name. */
    private static final Set<String> SPLIT_STRUCTURES = Set.of("village", "mineshaft", "ruined_portal",
            "shipwreck", "ocean_ruin");

    /** The trigger fields 26.3 reads as one entity condition, as its trigger classes name them. */
    private static final Set<String> ENTITY_CONDITION_FIELDS = Set.of("player", "entity", "parent",
            "partner", "child", "zombie", "villager", "projectile", "cause", "bystander", "lightning",
            "source");

    /** Vanilla recipe types whose result is read as an item stack since 1.20.5. */
    private static final Set<String> ITEM_STACK_RESULT_RECIPES = Set.of(
            "minecraft:crafting_shaped", "minecraft:crafting_shapeless",
            "minecraft:smithing_transform", "minecraft:smelting", "minecraft:blasting",
            "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:stonecutting");

    /** Of those, the types whose result was a bare item id before 1.20.5. */
    private static final Set<String> BARE_ID_RESULT_RECIPES = Set.of(
            "minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
            "minecraft:campfire_cooking", "minecraft:stonecutting");

    /** Every key an item predicate read up to 1.20.4; {@code data} is 1.12's metadata. */
    private static final Set<String> ITEM_PREDICATE_KEYS = Set.of("item", "items", "tag", "count",
            "durability", "nbt", "enchantments", "stored_enchantments", "potion", "data");

    /** Loot entries that hold child entries, one or all of which produce the drop. */
    private static final Set<String> COMPOSITE_ENTRIES = Set.of("minecraft:alternatives",
            "minecraft:group", "minecraft:sequence");

    /** Advancement trigger condition fields that hold one item predicate. */
    private static final Set<String> ITEM_PREDICATE_FIELDS = Set.of("item", "rod", "tool");

    // Trigger condition fields that hold a list of item predicates: inventory_changed's items, and
    // the ingredients of recipe_crafted and crafter_recipe_crafted.
    private static final List<String> ITEM_PREDICATE_LISTS = List.of("items", "ingredients");

    /** Entity predicate equipment slots, each an item predicate. */
    private static final Set<String> EQUIPMENT_SLOTS = Set.of("head", "chest", "legs", "feet",
            "mainhand", "offhand", "body", "saddle");

    /** Every key a location predicate has read. */
    private static final Set<String> LOCATION_KEYS = Set.of("position", "biome", "biomes", "feature",
            "structure", "structures", "dimension", "smokey", "light", "block", "fluid", "can_see_sky");

    private static final Map<String, String> VANILLA_DIMENSIONS = Map.of(
            "overworld", "minecraft:overworld", "the_nether", "minecraft:the_nether",
            "the_end", "minecraft:the_end");

    private ItemStackDataMigrator() {
    }

    /** Rewrites one data file for the host, or returns the same array when nothing applied. */
    static byte[] migrate(String entryName, byte[] json, String sourceMcVersion, String targetMcVersion) {
        // No rule applies before 1.14, the first change these rules cover.
        if (!atLeast(targetMcVersion, FIRST_NAMESPACED_DIMENSION_HOST)) return json;
        Kind kind = Kind.of(entryName);
        if (kind == null) return json;
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        Rewriter rewriter = new Rewriter(sourceMcVersion, targetMcVersion);
        JsonElement migrated;
        try {
            migrated = rewriter.file(kind, root);
        } catch (RuntimeException unexpectedShape) {
            return json; // one odd file stays as shipped rather than failing the whole mod
        }
        // Every rule fires only on the old shape, so data already written for the host comes back
        // unchanged and a second run changes nothing.
        if (!rewriter.changed) return json;
        return migrated.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The kinds of data file these rules read. */
    enum Kind {
        RECIPE, ADVANCEMENT, LOOT;

        static Kind of(String entryName) {
            if (!entryName.endsWith(".json")) return null;
            // Both the old plural folder names and the singular ones 1.21 reads.
            if (entryName.contains("/advancement/") || entryName.contains("/advancements/")) {
                return ADVANCEMENT;
            }
            if (entryName.contains("/recipe/") || entryName.contains("/recipes/")) return RECIPE;
            if (LegacyLootConditionMigrator.isLootFile(entryName)
                    || entryName.contains("/predicate/") || entryName.contains("/predicates/")
                    || LegacyLootConditionMigrator.isLootModifier(entryName)) {
                return LOOT;
            }
            return null;
        }
    }

    /** One file's rewrite, recording whether anything changed. */
    private static final class Rewriter {
        private final String source;
        private final String target;
        boolean changed;
        /** The item of the loot entry being walked, so its functions know whose NBT they set. */
        private String entryItem;

        Rewriter(String source, String target) {
            this.source = source;
            this.target = target;
        }

        /** Whether the data is known to come from a release before {@code version}. */
        private boolean sourceBefore(String version) {
            return source != null && source.matches("\\d+\\.\\d+.*")
                    && RetromodVersion.compareMcVersions(source, version) < 0;
        }

        JsonElement file(Kind kind, JsonElement root) {
            if (kind == Kind.RECIPE && root instanceof JsonObject recipe) recipe(recipe);
            if (kind == Kind.ADVANCEMENT && root instanceof JsonObject advancement) advancement(advancement);
            // Every kind gets the loot walk too, since conditions and predicates nest in advancements.
            return loot(root);
        }

        // ---- recipes ------------------------------------------------------------------------

        /** 1.20.5 reads a vanilla recipe's result as an item stack that names its item by {@code id}. */
        private void recipe(JsonObject recipe) {
            if (!atLeast(target, ItemStackComponents.FIRST_COMPONENT_HOST)) return;
            // Only Minecraft's types. A mod's own recipe types are read by its code in the shape it expects.
            String type = namespaced(string(recipe.get("type")));
            if (type == null || !ITEM_STACK_RESULT_RECIPES.contains(type)) return;
            JsonElement result = recipe.get("result");
            if (result instanceof JsonObject stack) {
                JsonObject converted = itemStack(stack);
                if (converted != null) {
                    recipe.add("result", converted);
                    changed = true;
                }
            } else if (string(result) != null && BARE_ID_RESULT_RECIPES.contains(type)) {
                // A bare item id becomes a stack. Stonecutting kept its count beside the result.
                JsonObject stack = new JsonObject();
                stack.addProperty("id", string(result));
                if (type.equals("minecraft:stonecutting") && recipe.get("count") instanceof JsonPrimitive count
                        && count.isNumber()) {
                    stack.add("count", count);
                    recipe.remove("count");
                }
                recipe.add("result", stack);
                changed = true;
            }
        }

        /** An old stack with an {@code item} key as a 1.20.5 stack, or null if it cannot carry over. */
        private JsonObject itemStack(JsonObject stack) {
            String itemKey = "item";
            // Components beside an old item key are a half-converted stack whose intent is unclear.
            if (stack.has("id") || stack.has("data") || stack.has("components")
                    || string(stack.get(itemKey)) == null) {
                return null;
            }
            String item = string(stack.get(itemKey));
            // 1.20.5 stacks carry components instead of NBT. NBT that cannot carry over leaves the
            // stack alone.
            JsonObject components = null;
            if (stack.has("nbt")) {
                Compound tag = LegacyItemNbt.parseField(stack.get("nbt"));
                if (tag == null) return null;
                components = ItemStackComponents.componentize(item, tag, source, target);
                if (components == null) return null;
            }
            // The other fields keep their place, so a stack with nothing but an item reads {"id": ...}.
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> field : stack.entrySet()) {
                String key = field.getKey();
                if (key.equals(itemKey)) {
                    out.add("id", field.getValue());
                } else if (key.equals("nbt")) {
                    if (!components.isEmpty()) out.add("components", components);
                } else {
                    out.add(key, field.getValue());
                }
            }
            return out;
        }

        // ---- advancements -------------------------------------------------------------------

        /** Rewrites the display icon and background, then each criterion. */
        private void advancement(JsonObject advancement) {
            if (advancement.get("display") instanceof JsonObject display) {
                // 1.20.5 reads the icon as an item stack too.
                if (atLeast(target, ItemStackComponents.FIRST_COMPONENT_HOST)
                        && display.get("icon") instanceof JsonObject icon) {
                    JsonObject converted = itemStack(icon);
                    if (converted != null) {
                        display.add("icon", converted);
                        changed = true;
                    }
                }
                // 1.21.5 names the background by texture id instead of file path.
                String background = string(display.get("background"));
                if (background != null && atLeast(target, FIRST_TEXTURE_ID_BACKGROUND_HOST)) {
                    String id = textureId(background);
                    if (id != null) {
                        display.addProperty("background", id);
                        changed = true;
                    }
                }
            }
            if (advancement.get("criteria") instanceof JsonObject criteria) {
                for (Map.Entry<String, JsonElement> criterion : criteria.entrySet()) {
                    if (criterion.getValue() instanceof JsonObject object) criterion(object);
                }
            }
        }

        /** {@code "<ns>:textures/<path>.png"} as the texture id {@code "<ns>:<path>"}. */
        private static String textureId(String path) {
            int colon = path.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : path.substring(0, colon);
            String rest = colon < 0 ? path : path.substring(colon + 1);
            if (!rest.startsWith("textures/") || !rest.endsWith(".png")) return null;
            return namespace + ":" + rest.substring("textures/".length(), rest.length() - ".png".length());
        }

        /** Rewrites one criterion's conditions for its trigger. */
        private void criterion(JsonObject criterion) {
            String trigger = namespaced(string(criterion.get("trigger")));
            if (!(criterion.get("conditions") instanceof JsonObject conditions)) return;
            // The location trigger's conditions moved twice, so the whole object is replaced.
            if ("minecraft:location".equals(trigger)) {
                JsonObject wrapped = locationTrigger(conditions);
                if (wrapped != null) {
                    criterion.add("conditions", wrapped);
                    changed = true;
                    return;
                }
            }
            // 1.14 namespaced the dimensions a changed_dimension trigger names.
            if ("minecraft:changed_dimension".equals(trigger)) {
                for (String key : List.of("from", "to")) {
                    String dimension = dimension(string(conditions.get(key)));
                    if (dimension != null) {
                        conditions.addProperty(key, dimension);
                        changed = true;
                    }
                }
            }
            // Item predicates, alone or in lists.
            for (String field : ITEM_PREDICATE_FIELDS) {
                if (conditions.get(field) instanceof JsonObject predicate) itemPredicate(predicate);
            }
            for (String list : ITEM_PREDICATE_LISTS) {
                if (conditions.get(list) instanceof JsonArray items) {
                    for (JsonElement item : items) {
                        if (item instanceof JsonObject predicate) itemPredicate(predicate);
                    }
                }
            }
            // 26.3 reads each entity field, and each of a kill's victims, only as a loot condition.
            if (atLeast(target, FIRST_CONDITION_ONLY_CRITERIA_HOST)) {
                for (String field : ENTITY_CONDITION_FIELDS) {
                    JsonObject wrapped = entityCondition(conditions.get(field));
                    if (wrapped != null) {
                        conditions.add(field, wrapped);
                        changed = true;
                    }
                }
                if (conditions.get("victims") instanceof JsonArray victims) {
                    for (int i = 0; i < victims.size(); i++) {
                        JsonObject wrapped = entityCondition(victims.get(i));
                        if (wrapped != null) {
                            victims.set(i, wrapped);
                            changed = true;
                        }
                    }
                }
            }
        }

        /** A bare entity predicate wrapped in the entity_properties condition 26.3 reads, or null. */
        private static JsonObject entityCondition(JsonElement value) {
            // A condition, a list of them, or anything else that is not an entity predicate stays as it is.
            if (!(value instanceof JsonObject predicate) || predicate.has("condition")
                    || !ModDataMigrator.isEntityPredicateVocabulary(predicate.keySet())
                    || RegistryDataShapes.isLootConditionType(string(predicate.get("type")))) {
                return null;
            }
            // Written the old way, so the later condition rewrites bring it to 26.3's syntax.
            JsonObject condition = new JsonObject();
            condition.addProperty("condition", "minecraft:entity_properties");
            condition.addProperty("entity", "this");
            condition.add("predicate", predicate);
            return condition;
        }

        /** A location trigger's bare 1.12 to 1.15 location predicate in the host's shape, or null. */
        private JsonObject locationTrigger(JsonObject conditions) {
            if (conditions.size() == 1 && conditions.get("location") instanceof JsonObject wrapped
                    && atLeast(target, FIRST_PLAYER_LOCATION_HOST)) {
                conditions = wrapped; // the 1.16 to 1.18 shape, which 1.19 moved under "player"
            }
            if (conditions.isEmpty() || !LOCATION_KEYS.containsAll(conditions.keySet())
                    || !atLeast(target, FIRST_WRAPPED_LOCATION_HOST)) {
                return null;
            }
            // 1.14 namespaced the dimension. A dimension or structure that cannot carry over leaves it alone.
            JsonObject location = conditions.deepCopy();
            String rawDimension = string(location.get("dimension"));
            if (rawDimension != null) {
                String dimension = rawDimension.indexOf(':') >= 0 ? rawDimension : dimension(rawDimension);
                if (dimension == null) return null; // a mod's dimension name says nothing of its id
                location.addProperty("dimension", dimension);
            }
            if (!locationPredicate(location)) return null;
            // 1.16 to 1.18 read the predicate under "location".
            JsonObject wrapped = new JsonObject();
            if (!atLeast(target, FIRST_PLAYER_LOCATION_HOST)) {
                wrapped.add("location", location);
                return wrapped;
            }
            // 1.19 reads it from an entity_properties condition on the player.
            JsonObject predicate = new JsonObject();
            predicate.add("location", location);
            JsonObject condition = new JsonObject();
            condition.addProperty("condition", "minecraft:entity_properties");
            condition.addProperty("entity", "this");
            condition.add("predicate", predicate);
            JsonArray player = new JsonArray();
            player.add(condition);
            wrapped.add("player", player);
            return wrapped;
        }

        /** A vanilla dimension name as its namespaced id, or null for anything else. */
        private String dimension(String name) {
            if (name == null || name.indexOf(':') >= 0
                    || !atLeast(target, FIRST_NAMESPACED_DIMENSION_HOST)) {
                return null;
            }
            return VANILLA_DIMENSIONS.get(name);
        }

        // ---- loot tables, predicates, item modifiers and every condition --------------------

        /** Walks the tree, rewriting loot functions, conditions, entries and nested predicates. */
        private JsonElement loot(JsonElement element) {
            if (element instanceof JsonArray array) {
                JsonArray out = new JsonArray();
                for (JsonElement item : array) {
                    JsonElement converted = loot(item);
                    if (converted instanceof JsonArray split && item instanceof JsonObject
                            && isFunction(item.getAsJsonObject())) {
                        split.forEach(out::add);   // set_nbt became two functions
                    } else {
                        out.add(converted);
                    }
                }
                return out;
            }
            if (!(element instanceof JsonObject object)) return element;
            // Children first, with this object's item in scope, then the enclosing one restored.
            String enclosingItem = entryItem;
            entryItem = scopeItem(object, entryItem);
            for (Map.Entry<String, JsonElement> field : object.entrySet()) {
                field.setValue(loot(field.getValue()));
            }
            entryItem = enclosingItem;
            // Loot functions. Only Minecraft's are rewritten; a mod's own are read by its code as they are.
            String function = namespaced(string(object.get("function")));
            if (function != null && !object.has("type")) {
                renameEntityTarget(object, ENTITY_TARGET_FUNCTIONS.get(function));
                JsonElement converted = function(function, object);
                if (converted != null) return converted;
            }
            // Loot conditions, the entity ones with 1.21's renamed targets.
            String condition = namespaced(string(object.get("condition")));
            if (condition != null && !object.has("type") && !object.has("entries")) {
                if (condition.equals("minecraft:entity_properties") || condition.equals("minecraft:entity_scores")) {
                    renameEntityTarget(object, "entity");
                }
                condition(condition, object);
            }
            // 1.20.5 names a loot_table entry's table by "value" instead of "name".
            if ("minecraft:loot_table".equals(namespaced(string(object.get("type"))))
                    && string(object.get("name")) != null && !object.has("value")
                    && atLeast(target, ItemStackComponents.FIRST_COMPONENT_HOST)) {
                renameKey(object, "name", "value");
                changed = true;
            }
            // An entity predicate's equipment slots each hold an item predicate.
            for (String slots : List.of("equipment", "minecraft:equipment")) {
                if (object.get(slots) instanceof JsonObject equipment) {
                    for (String slot : EQUIPMENT_SLOTS) {
                        if (equipment.get(slot) instanceof JsonObject predicate) itemPredicate(predicate);
                    }
                }
            }
            // The location predicates an entity predicate holds.
            for (String key : List.of("location", "stepping_on", "movement_affected_by",
                    "minecraft:location", "minecraft:stepping_on", "minecraft:movement_affected_by")) {
                if (object.get(key) instanceof JsonObject location
                        && LOCATION_KEYS.containsAll(location.keySet())) {
                    locationPredicate(location);
                }
            }
            return object;
        }

        /** The item whose NBT a function inside {@code object} sets, or null when that is not one item. */
        private static String scopeItem(JsonObject object, String enclosing) {
            // An item entry names its own item.
            String type = namespaced(string(object.get("type")));
            if ("minecraft:item".equals(type) && string(object.get("name")) != null) {
                return namespaced(string(object.get("name")));
            }
            // A pool, a composite entry or a whole table needs the one item every entry under it
            // names. Several items, a tag or another table give null.
            if (object.has("entries")) return commonItem(object.get("entries"));
            if (object.has("children")) return commonItem(object.get("children"));
            if (object.get("pools") instanceof JsonArray pools) {
                String common = null;
                for (JsonElement pool : pools) {
                    String item = pool instanceof JsonObject entries ? commonItem(entries.get("entries")) : null;
                    if (item == null || common != null && !common.equals(item)) return null;
                    common = item;
                }
                return common;
            }
            return enclosing;
        }

        /** The one item a list of loot entries can produce, or null. An empty entry produces none. */
        private static String commonItem(JsonElement entries) {
            if (!(entries instanceof JsonArray list)) return null;
            String common = null;
            for (JsonElement element : list) {
                if (!(element instanceof JsonObject entry)) return null;
                String type = namespaced(string(entry.get("type")));
                if ("minecraft:empty".equals(type)) continue;
                String item = "minecraft:item".equals(type) ? namespaced(string(entry.get("name")))
                        : COMPOSITE_ENTRIES.contains(type) ? commonItem(entry.get("children")) : null;
                if (item == null || common != null && !common.equals(item)) return null;
                common = item;
            }
            return common;
        }

        /** Whether every copy operation writes under a key of the mod's own, never Minecraft's. */
        private static boolean copiesIntoModKeysOnly(JsonElement ops) {
            if (!(ops instanceof JsonArray list) || list.isEmpty()) return false;
            for (JsonElement op : list) {
                String target = op instanceof JsonObject copy ? string(copy.get("target")) : null;
                String root = target == null ? null : rootKey(target);
                if (root == null || ItemStackComponents.ownedByMinecraft(root)) return false;
            }
            return true;
        }

        /** The first key of an NBT path, or null for a path that starts at the root compound. */
        private static String rootKey(String path) {
            // A quoted key runs to its closing quote, a plain one to the first separator.
            if (path.startsWith("\"")) {
                int end = path.indexOf('"', 1);
                return end > 1 ? path.substring(1, end) : null;
            }
            int end = 0;
            while (end < path.length() && ".[{".indexOf(path.charAt(end)) < 0) end++;
            return end > 0 ? path.substring(0, end) : null;
        }

        /** 1.21 renamed the loot context's killer targets, and reads only the new names. */
        private void renameEntityTarget(JsonObject object, String field) {
            if (field == null || !atLeast(target, FIRST_ENCHANTMENT_OPTIONS_HOST)) return;
            String old = string(object.get(field));
            String renamed = old == null ? null : RENAMED_ENTITY_TARGETS.get(old);
            if (renamed == null) return;
            object.addProperty(field, renamed);
            changed = true;
        }

        private static boolean isFunction(JsonObject object) {
            return string(object.get("function")) != null && !object.has("type");
        }

        /** The rewritten function, an array of two for a split {@code set_nbt}, or null if unchanged. */
        private JsonElement function(String function, JsonObject object) {
            switch (function) {
                case "minecraft:set_nbt" -> {
                    return atLeast(target, ItemStackComponents.FIRST_COMPONENT_HOST) ? setNbt(object) : null;
                }
                case "minecraft:copy_nbt" -> {
                    // 1.20.5 made it copy_custom_data, which writes into custom data, so a copy aimed
                    // at a key Minecraft owns, such as BlockEntityTag, would land where nothing reads it.
                    if (!atLeast(target, ItemStackComponents.FIRST_COMPONENT_HOST)
                            || !copiesIntoModKeysOnly(object.get("ops"))) {
                        return null;
                    }
                    object.addProperty("function", "minecraft:copy_custom_data");
                    changed = true;
                    return object;
                }
                case "minecraft:looting_enchant" -> {
                    // 1.21 made looting an enchantment like any other.
                    if (!atLeast(target, FIRST_ENCHANTMENT_OPTIONS_HOST)) return null;
                    object.addProperty("function", "minecraft:enchanted_count_increase");
                    object.addProperty("enchantment", "minecraft:looting");
                    changed = true;
                    return object;
                }
                case "minecraft:enchant_randomly" -> {
                    // 1.21 renamed the choices to options. Its own shape also leaves options out,
                    // meaning every enchantment, so only the old key or data from before 1.21 marks
                    // the old shape.
                    if (!atLeast(target, FIRST_ENCHANTMENT_OPTIONS_HOST) || object.has("options")
                            || !object.has("enchantments") && !sourceBefore(FIRST_ENCHANTMENT_OPTIONS_HOST)) {
                        return null;
                    }
                    JsonElement list = object.remove("enchantments");
                    object.add("options", list instanceof JsonArray ids && !ids.isEmpty()
                            ? (ids.size() == 1 ? ids.get(0) : ids)
                            : new JsonPrimitive("#minecraft:on_random_loot"));
                    changed = true;
                    return object;
                }
                case "minecraft:enchant_with_levels" -> {
                    if (!atLeast(target, FIRST_ENCHANTMENT_OPTIONS_HOST) || object.has("options")
                            || !object.has("treasure") && !sourceBefore(FIRST_ENCHANTMENT_OPTIONS_HOST)) {
                        return null;
                    }
                    JsonElement treasure = object.remove("treasure");
                    boolean anyEnchantment = treasure instanceof JsonPrimitive flag && flag.isBoolean()
                            && flag.getAsBoolean();
                    // 1.21 replaced the treasure flag with options. Without treasure only the
                    // enchanting table's enchantments could roll.
                    object.addProperty("options", anyEnchantment ? "#minecraft:on_random_loot"
                            : "#minecraft:in_enchanting_table");
                    changed = true;
                    return object;
                }
                default -> {
                    return null;
                }
            }
        }

        /** Turns {@code set_nbt} into {@code set_components} and {@code set_custom_data}, by key owner. */
        private JsonElement setNbt(JsonObject function) {
            Compound tag = LegacyItemNbt.parseField(function.get("tag"));
            if (tag == null) return null;
            // With no single item in scope, a key only some of Minecraft's items read, such as
            // Potion, may be either Minecraft's or the mod's, and the split refuses it. A refused
            // key leaves set_nbt alone, since the item would lose what made it special.
            ItemStackComponents.Split split = ItemStackComponents.split(entryItem, tag, source, target);
            if (split == null) return null;
            JsonObject rest = withoutKeys(function, "function", "tag");
            JsonObject components = split.components();
            boolean customData = !split.customData().entries().isEmpty();
            if (customData && !components.isEmpty() && (rest.has("conditions") || rest.has("condition"))) {
                // Two functions would each roll the conditions on their own, so the mod's keys
                // join the components. That replaces custom data an earlier function set, where
                // set_nbt merged into it.
                JsonElement value = ItemStackComponents.customDataValue(split.customData(), target);
                if (value == null) return null;
                components.add("minecraft:custom_data", value);
                customData = false;
            }
            JsonArray functions = new JsonArray();
            if (!components.isEmpty()) {
                functions.add(withFields("minecraft:set_components", "components", components, rest));
            }
            // set_custom_data merges into the item's custom data, as set_nbt merged into its NBT.
            if (customData || functions.isEmpty()) {
                functions.add(withFields("minecraft:set_custom_data", "tag",
                        new JsonPrimitive(LegacyItemNbt.write(split.customData())), rest));
            }
            changed = true;
            return functions.size() == 1 ? functions.get(0) : functions;
        }

        /** A function of {@code type} with its one value, then the old function's other fields. */
        private static JsonObject withFields(String type, String key, JsonElement value, JsonObject rest) {
            JsonObject out = new JsonObject();
            out.addProperty("function", type);
            out.add(key, value);
            rest.entrySet().forEach(field -> out.add(field.getKey(), field.getValue().deepCopy()));
            return out;
        }

        /** Rewrites one of Minecraft's loot conditions in place. */
        private void condition(String condition, JsonObject object) {
            switch (condition) {
                case "minecraft:random_chance_with_looting" -> {
                    // 1.21 made looting an enchantment. The old chance plus the multiplier per
                    // looting level becomes a linear chance on the enchantment's level.
                    if (!atLeast(target, FIRST_ENCHANTMENT_OPTIONS_HOST)) return;
                    BigDecimal chance = number(object.get("chance"));
                    BigDecimal multiplier = number(object.get("looting_multiplier"));
                    if (chance == null || multiplier == null) return;
                    object.remove("chance");
                    object.remove("looting_multiplier");
                    object.addProperty("condition", "minecraft:random_chance_with_enchanted_bonus");
                    object.addProperty("enchantment", "minecraft:looting");
                    object.add("unenchanted_chance", new JsonPrimitive(chance));
                    JsonObject enchanted = new JsonObject();
                    enchanted.addProperty("type", "minecraft:linear");
                    enchanted.add("base", new JsonPrimitive(chance.add(multiplier)));
                    enchanted.add("per_level_above_first", new JsonPrimitive(multiplier));
                    object.add("enchanted_chance", enchanted);
                    changed = true;
                }
                case "minecraft:match_tool" -> {
                    if (object.get("predicate") instanceof JsonObject predicate) itemPredicate(predicate);
                }
                case "minecraft:entity_properties" -> {
                    // 1.12 tested the entity's "properties", such as on_fire, which 1.13 read as a predicate.
                    if (object.get("properties") instanceof JsonObject properties && !object.has("predicate")
                            && properties.size() == 1 && properties.get("on_fire") instanceof JsonPrimitive flag
                            && flag.isBoolean()) {
                        JsonObject flags = new JsonObject();
                        flags.add("is_on_fire", flag);
                        JsonObject predicate = new JsonObject();
                        predicate.add("flags", flags);
                        object.remove("properties");
                        object.add("predicate", predicate);
                        changed = true;
                    }
                }
                case "minecraft:location_check" -> {
                    if (object.get("predicate") instanceof JsonObject location
                            && LOCATION_KEYS.containsAll(location.keySet())) {
                        locationPredicate(location);
                    }
                }
                default -> {
                }
            }
        }

        /** Rewrites a location predicate in place. False when its feature has no single host structure. */
        private boolean locationPredicate(JsonObject location) {
            // 1.19 replaced the structure "feature" with a "structure" id.
            if (location.has("feature") && atLeast(target, FIRST_STRUCTURE_LOCATION_HOST)) {
                String structure = structureForFeature(string(location.get("feature")));
                if (structure == null || location.has("structure") || location.has("structures")) return false;
                replace(location, "feature", "structure", new JsonPrimitive(structure));
            }
            // 1.20.5 renamed "biome" and "structure" to the lists "biomes" and "structures".
            if (!atLeast(target, ItemStackComponents.FIRST_COMPONENT_HOST)) return true;
            if (string(location.get("biome")) != null && !location.has("biomes")) {
                renameKey(location, "biome", "biomes");
                changed = true;
            }
            if (string(location.get("structure")) != null && !location.has("structures")) {
                renameKey(location, "structure", "structures");
                changed = true;
            }
            return true;
        }

        /** The structure an old feature name stands for, or null when it names none the host has. */
        private String structureForFeature(String feature) {
            if (feature == null) return null;
            String id = namespaced(feature.toLowerCase(java.util.Locale.ROOT));
            // A mod's feature and the structure its data defines share an id.
            if (!id.startsWith("minecraft:")) return id;
            String path = id.substring("minecraft:".length());
            // Minecraft's features map to the structure of the same name.
            if (path.equals("endcity")) return "minecraft:end_city";
            if (SAME_NAME_STRUCTURES.contains(path)) return id;
            // Where 1.19 split one feature into several structures, the structure tag of its name
            // stands in, which only a 1.20.5 list can hold. 1.12's Temple covered four with no tag.
            if (SPLIT_STRUCTURES.contains(path) && atLeast(target, ItemStackComponents.FIRST_COMPONENT_HOST)) {
                return "#" + id;
            }
            return null;
        }

        // ---- item predicates ----------------------------------------------------------------

        /** Rewrites an item predicate in place to the host's shape, when every old key carries over. */
        private void itemPredicate(JsonObject predicate) {
            if (predicate.get("predicates") instanceof JsonObject subPredicates) {
                pluralEnchantmentKeys(subPredicates); // already in 1.20.5's shape
                return;
            }
            // A key the host ignores would widen the predicate to more items, so an unknown key or
            // 1.12's data leaves it alone.
            if (!ITEM_PREDICATE_KEYS.containsAll(predicate.keySet()) || predicate.has("data")
                    || !atLeast(target, FIRST_ITEMS_LIST_HOST)) {
                return;
            }
            String item = string(predicate.get("item"));
            if (predicate.has("item") && (item == null || predicate.has("items"))) return;
            if (!atLeast(target, ItemStackComponents.FIRST_COMPONENT_HOST)) {
                // 1.17 to 1.20.4 read every other key as before; only the item moved to a list.
                if (item != null) {
                    JsonArray items = new JsonArray();
                    items.add(item);
                    replace(predicate, "item", "items", items);
                }
                return;
            }
            String tag = string(predicate.get("tag"));
            if (predicate.has("tag") && (tag == null || item != null || predicate.has("items"))) {
                return; // an item and a tag both had to match, which "items" cannot say
            }
            // 1.20.5 moved the NBT, enchantment, potion and durability checks under "predicates".
            JsonObject predicates = new JsonObject();
            if (predicate.has("nbt")) {
                Compound nbt = LegacyItemNbt.parseField(predicate.get("nbt"));
                if (nbt == null) return;
                ItemStackComponents.Split split = ItemStackComponents.split(predicateItem(predicate), nbt,
                        source, target);
                if (split == null) return;
                // A Minecraft key in the NBT is now a component, which custom data never matches.
                // Only a potion has a sub-predicate that tests what the old NBT match tested.
                String potion = potionOnly(split.components());
                if (potion != null && !predicate.has("potion")) {
                    predicates.addProperty("minecraft:potion_contents", potion);
                } else if (!split.components().isEmpty()) {
                    return;
                }
                if (potion == null || !split.customData().entries().isEmpty()) {
                    predicates.addProperty("minecraft:custom_data", LegacyItemNbt.write(split.customData()));
                }
            }
            for (String key : List.of("enchantments", "stored_enchantments")) {
                if (!predicate.has(key)) continue;
                JsonArray converted = enchantmentPredicates(predicate.get(key));
                if (converted == null) return;
                predicates.add("minecraft:" + key, converted);
            }
            if (predicate.has("potion")) {
                if (string(predicate.get("potion")) == null) return;
                predicates.add("minecraft:potion_contents", predicate.get("potion"));
            }
            if (predicate.has("durability")) {
                JsonObject damage = new JsonObject();
                damage.add("durability", predicate.get("durability"));
                predicates.add("minecraft:damage", damage);
            }
            // "items" now takes one id or tag, and count stays where it was.
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> field : predicate.entrySet()) {
                switch (field.getKey()) {
                    case "item" -> out.addProperty("items", item);
                    case "tag" -> out.addProperty("items", tag.startsWith("#") ? tag : "#" + tag);
                    case "items", "count" -> out.add(field.getKey(), field.getValue());
                    default -> { }
                }
            }
            if (!predicates.isEmpty()) out.add("predicates", predicates);
            if (out.equals(predicate)) return;
            predicate.entrySet().clear();
            out.entrySet().forEach(field -> predicate.add(field.getKey(), field.getValue()));
            changed = true;
        }

        /** The potion id when the components hold nothing but a potion, or null. */
        private static String potionOnly(JsonObject components) {
            if (components.size() != 1 || !(components.get("minecraft:potion_contents") instanceof JsonObject contents)
                    || contents.size() != 1) {
                return null;
            }
            return string(contents.get("potion"));
        }

        /** The one item an old predicate names, or null for a tag, a list or no item. */
        private static String predicateItem(JsonObject predicate) {
            if (string(predicate.get("item")) != null) return string(predicate.get("item"));
            if (predicate.get("items") instanceof JsonArray items && items.size() == 1) return string(items.get(0));
            return null;
        }

        /** 1.21 renamed an enchantment predicate's {@code enchantment} key and ignores the old one. */
        private void pluralEnchantmentKeys(JsonObject subPredicates) {
            // The key is now "enchantments". Left as it was, a silk touch check written for 1.20.5
            // or 1.20.6 matched any enchanted tool.
            if (!atLeast(target, FIRST_ENCHANTMENT_OPTIONS_HOST)) return;
            for (String key : List.of("minecraft:enchantments", "minecraft:stored_enchantments",
                    "enchantments", "stored_enchantments")) {
                if (!(subPredicates.get(key) instanceof JsonArray list)) continue;
                for (JsonElement item : list) {
                    if (item instanceof JsonObject entry && entry.has("enchantment") && !entry.has("enchantments")) {
                        renameKey(entry, "enchantment", "enchantments");
                        changed = true;
                    }
                }
            }
        }

        /** Old enchantment predicates in the host's shape, or null when a key does not carry over. */
        private JsonArray enchantmentPredicates(JsonElement value) {
            if (!(value instanceof JsonArray list)) return null;
            // Each named one "enchantment" until 1.21 made it "enchantments", which also takes a list
            // or a tag.
            boolean plural = atLeast(target, FIRST_ENCHANTMENT_OPTIONS_HOST);
            JsonArray out = new JsonArray();
            for (JsonElement item : list) {
                if (!(item instanceof JsonObject old)) return null;
                JsonObject entry = new JsonObject();
                for (Map.Entry<String, JsonElement> field : old.entrySet()) {
                    String key = field.getKey();
                    if (key.equals("enchantment") && plural) key = "enchantments";
                    else if (!key.equals("enchantment") && !key.equals("levels")
                            && !key.equals("enchantments")) {
                        return null;
                    }
                    entry.add(key, field.getValue());
                }
                out.add(entry);
            }
            return out;
        }

        /** Swaps one key for a new key and value, keeping the order of the others. */
        private void replace(JsonObject object, String oldKey, String newKey, JsonElement value) {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> field : object.entrySet()) {
                if (field.getKey().equals(oldKey)) copy.add(newKey, value);
                else copy.add(field.getKey(), field.getValue());
            }
            object.entrySet().clear();
            copy.entrySet().forEach(field -> object.add(field.getKey(), field.getValue()));
            changed = true;
        }
    }

    /** Renames one key in place, keeping the order of the others. */
    private static void renameKey(JsonObject object, String oldKey, String newKey) {
        List<Map.Entry<String, JsonElement>> fields = new ArrayList<>(object.entrySet());
        object.entrySet().clear();
        for (Map.Entry<String, JsonElement> field : fields) {
            object.add(field.getKey().equals(oldKey) ? newKey : field.getKey(), field.getValue());
        }
    }

    private static JsonObject withoutKeys(JsonObject object, String... keys) {
        JsonObject copy = object.deepCopy();
        for (String key : keys) copy.remove(key);
        return copy;
    }

    private static BigDecimal number(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isNumber()
                ? primitive.getAsBigDecimal() : null;
    }

    private static String string(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString() : null;
    }

    private static String namespaced(String id) {
        if (id == null) return null;
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** Whether {@code version} is a release at or after {@code floor}; an unknown version is not. */
    private static boolean atLeast(String version, String floor) {
        return version != null && version.matches("\\d+\\.\\d+.*")
                && RetromodVersion.compareMcVersions(version, floor) >= 0;
    }
}
