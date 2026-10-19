/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Translates the Forge 1.12 recipe features no later loader reads, in a 1.12 mod's copied recipes. */
final class LegacyForgeRecipeMigrator implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataLayout");

    /** The first host without the ore dictionary, the ore recipe types and recipe constants. */
    static final String FIRST_TAG_HOST = "1.13";
    /** The first host whose NeoForge checks an item with {@code registered} and has {@code never}. */
    static final String NEOFORGE_REGISTERED_HOST = "1.21.4";
    /** Constants could name other constants; this bounds a file that names itself. */
    private static final int MAX_CONSTANT_DEPTH = 8;
    /** The most JSON values one recipe may take in from constants. Real recipes stay far below it. */
    private static final int MAX_INLINED_VALUES = 10_000;

    @Override
    public String name() {
        return "Forge 1.12 recipes";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.sourceBefore(FIRST_TAG_HOST) && context.targetAtLeast(FIRST_TAG_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // Forge 1.12 let a recipe use the ore dictionary, its own ore recipe types, _constants.json
        // ingredients and conditions. 1.13 removed all of it, so these recipes fail to parse, and
        // on 26.3 a recipe that fails stops the world.
        Target target = Target.of(context);
        Map<String, Map<String, Constant>> constants = new HashMap<>();
        int changed = 0;
        int removed = 0;
        int tooLarge = 0;
        // Only the copies the layout step made of 1.12's recipes are translated.
        for (MigrationLedger.Relocation copy : LegacyDataCopies.of(tree, context, "recipe")) {
            JsonObject recipe = LegacyDataCopies.read(tree, copy.to());
            if (recipe == null) continue;
            String namespace = LegacyDataCopies.namespace(copy.to());
            Map<String, Constant> own = constants.computeIfAbsent(namespace,
                    ns -> readConstants(tree, ns));
            switch (new Recipe(recipe, own, target, namespace).migrate()) {
                case CHANGED -> {
                    LegacyDataCopies.write(tree, copy.to(), recipe);
                    changed++;
                }
                case CONDITIONS_NOT_ENFORCEABLE -> {
                    // On this host the recipe would load whatever its conditions said.
                    tree.delete(copy.to());
                    LOGGER.debug("Kept {} out of {}: the host cannot check all of its Forge 1.12 "
                            + "conditions", copy.from(), copy.to());
                    removed++;
                }
                case TOO_LARGE -> tooLarge++;
                case UNCHANGED -> { }
            }
        }
        if (changed > 0) {
            LOGGER.info("Translated Forge 1.12 recipe features in {} copied recipe(s) for {} {}",
                    changed, context.targetLoader(), context.targetMcVersion());
        }
        if (removed > 0) {
            LOGGER.info("Kept {} copied 1.12 recipe(s) out of {} {}: their Forge 1.12 conditions have "
                    + "no equivalent there, so they would load unconditionally", removed,
                    context.targetLoader(), context.targetMcVersion());
        }
        if (tooLarge > 0) {
            LOGGER.warn("Left {} copied 1.12 recipe(s) as shipped: their _constants.json references "
                    + "expand past {} JSON values", tooLarge, MAX_INLINED_VALUES);
        }
        return changed + removed;
    }

    /** What translating one recipe found, and so what happens to its copy. */
    private enum Outcome {
        UNCHANGED,
        CHANGED,
        /** Its conditions cannot all be written for a host that ignores Forge's 1.12 list. */
        CONDITIONS_NOT_ENFORCEABLE,
        /** Inlining its constants would pass {@link #MAX_INLINED_VALUES}. */
        TOO_LARGE
    }

    /** One {@code _constants.json} entry: its ingredient, its conditions, and its JSON value count. */
    private record Constant(JsonElement ingredient, List<JsonObject> conditions, int size) {}

    /** The constants a namespace defines, by name. A missing or unreadable file defines none. */
    private static Map<String, Constant> readConstants(ResourceTree tree, String namespace) {
        String name = "assets/" + namespace + "/recipes/_constants.json";
        Map<String, Constant> constants = new LinkedHashMap<>();
        JsonElement file = tree.exists(name) ? LegacyDataCopies.readElement(tree, name) : null;
        if (!(file instanceof JsonArray entries)) return constants;
        for (JsonElement entry : entries) {
            if (!(entry instanceof JsonObject definition)) continue;
            String constantName = string(definition.get("name"));
            JsonElement ingredient = definition.get("ingredient");
            if (constantName == null || ingredient == null) continue;
            List<JsonObject> conditions = new ArrayList<>();
            if (definition.get("conditions") instanceof JsonArray list) {
                for (JsonElement condition : list) {
                    if (condition instanceof JsonObject object) conditions.add(object);
                }
            }
            constants.put(constantName, new Constant(ingredient, conditions, valueCount(ingredient)));
        }
        return constants;
    }

    /** How many JSON values an element holds, itself included. */
    private static int valueCount(JsonElement element) {
        int count = 1;
        if (element instanceof JsonArray array) {
            for (JsonElement child : array) count += valueCount(child);
        } else if (element instanceof JsonObject object) {
            for (JsonElement field : object.asMap().values()) count += valueCount(field);
        }
        return count;
    }

    /** The tag family and condition syntax a target reads, as checked in each loader's jars. */
    private record Target(LegacyOreDictionary.Family tags, Conditions conditions,
                          boolean readsLegacyConditions) {
        static Target of(MigrationContext context) {
            // A null family leaves ore names as they are, and a null syntax means no condition
            // can be written.
            MigrationContext.Loader loader = context.targetLoader();
            if (loader == MigrationContext.Loader.FORGE) {
                // Forge 1.19.2 to 1.21.11 ship every forge: tag of the table but 1.21.x only some
                // c: ones; Forge 26.1 to 26.3 ship every c: tag and dropped the forge: ones.
                LegacyOreDictionary.Family tags = context.targetAtLeast("26.1")
                        ? LegacyOreDictionary.Family.CONVENTION
                        : context.targetAtLeast("1.19") ? LegacyOreDictionary.Family.FORGE : null;
                if (context.targetAtLeast("1.20.2")) {
                    return new Target(tags, Conditions.FORGE_SINGLE, false);
                }
                // Forge 1.13 to 1.20.1 still read Forge's own conditions list.
                return new Target(tags, context.targetAtLeast("1.19") ? Conditions.FORGE_LIST : null, true);
            }
            if (loader == MigrationContext.Loader.NEOFORGE) {
                // NeoForge for 1.20.1 is Forge 47.1 and reads Forge's list itself.
                if (!context.targetAtLeast("1.20.2")) return new Target(null, null, true);
                // 1.20.x NeoForge ships forge: tags and, on 1.20.6, an incomplete c: set.
                LegacyOreDictionary.Family tags = context.targetAtLeast("1.21")
                        ? LegacyOreDictionary.Family.CONVENTION : null;
                return new Target(tags, context.targetAtLeast(NEOFORGE_REGISTERED_HOST)
                        ? Conditions.NEOFORGE : Conditions.NEOFORGE_EARLY, false);
            }
            if (loader.isFabricFamily() && context.targetAtLeast("1.21")) {
                return new Target(LegacyOreDictionary.Family.CONVENTION, Conditions.FABRIC, false);
            }
            return new Target(null, null, false);
        }
    }

    /** One copied recipe and what translating it found. */
    private static final class Recipe {
        private final JsonObject json;
        private final Map<String, Constant> constants;
        private final Target target;
        private final String namespace;
        private final List<JsonObject> constantConditions = new ArrayList<>();
        private final Set<String> guardedTags = new LinkedHashSet<>();
        private boolean changed;
        private int inlinedValues;
        private boolean tooLarge;

        Recipe(JsonObject json, Map<String, Constant> constants, Target target, String namespace) {
            this.json = json;
            this.constants = constants;
            this.target = target;
            this.namespace = namespace;
        }

        /** Translates the recipe in place and says what happens to its copy. */
        Outcome migrate() {
            // Translate the ingredients, in a shaped recipe's key and a shapeless recipe's list.
            if (json.get("key") instanceof JsonObject key) {
                for (Map.Entry<String, JsonElement> symbol : key.entrySet()) {
                    JsonElement converted = ingredient(symbol.getValue(), 0);
                    if (converted != null) symbol.setValue(converted);
                }
            }
            if (json.get("ingredients") instanceof JsonArray ingredients) {
                for (int i = 0; i < ingredients.size(); i++) {
                    JsonElement converted = ingredient(ingredients.get(i), 0);
                    if (converted != null) ingredients.set(i, converted);
                }
            }
            if (tooLarge) return Outcome.TOO_LARGE;
            // The ore types become the vanilla crafting types. The mirrored flag goes, because
            // vanilla shaped recipes always match their mirror image.
            String type = string(json.get("type"));
            if ("forge:ore_shaped".equals(type) || "forge:ore_shapeless".equals(type)) {
                json.addProperty("type", type.equals("forge:ore_shaped")
                        ? "minecraft:crafting_shaped" : "minecraft:crafting_shapeless");
                json.remove("mirrored");
                changed = true;
            }
            if (!conditions()) return Outcome.CONDITIONS_NOT_ENFORCEABLE;
            return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
        }

        /** An ingredient with Forge 1.12's features translated, or null to leave it as shipped. */
        private JsonElement ingredient(JsonElement value, int depth) {
            if (tooLarge) return null;
            // A #NAME constant is replaced by its ingredient. Forge 1.12 parsed a constant once and
            // shared it, but inlining copies it at every reference, so constants that each name
            // the next several times would grow a recipe exponentially without the limit.
            Constant constant = constantNamed(value);
            if (constant != null && depth < MAX_CONSTANT_DEPTH) {
                inlinedValues += constant.size();
                if (inlinedValues > MAX_INLINED_VALUES) {
                    tooLarge = true;
                    return null;
                }
                // Forge 1.12 skipped a recipe whose constant failed its conditions, so the recipe
                // takes them on.
                for (JsonObject condition : constant.conditions()) {
                    if (!constantConditions.contains(condition)) constantConditions.add(condition);
                }
                JsonElement inlined = constant.ingredient().deepCopy();
                JsonElement converted = ingredient(inlined, depth + 1);
                changed = true;
                return converted != null ? converted : inlined;
            }
            if (value instanceof JsonArray alternatives) {
                JsonArray converted = new JsonArray();
                boolean any = false;
                for (JsonElement alternative : alternatives) {
                    JsonElement replacement = ingredient(alternative, depth);
                    converted.add(replacement != null ? replacement : alternative);
                    any |= replacement != null;
                }
                return any ? converted : null;
            }
            if (!(value instanceof JsonObject object)) return null;
            String type = string(object.get("type"));
            if ("forge:ore_dict".equals(type)) return oreIngredient(string(object.get("ore")));
            if ("minecraft:item".equals(type)) {
                // Forge 1.12 named the plain item ingredient; vanilla's has no type field.
                JsonObject item = object.deepCopy();
                item.remove("type");
                changed = true;
                return item;
            }
            // Anything else stays as shipped. That includes a recipe or ingredient type the mod
            // declared in its _factories.json: nothing registers those serializers, so
            // DataLoadGate keeps such a recipe out.
            return null;
        }

        /** The constant {@code {"item": "#NAME"}} or a bare {@code "#NAME"} refers to, or null. */
        private Constant constantNamed(JsonElement value) {
            String reference = value instanceof JsonObject object && object.size() == 1
                    ? string(object.get("item")) : string(value);
            return reference != null && reference.startsWith("#")
                    ? constants.get(reference.substring(1)) : null;
        }

        /** The tag that replaced an ore name in the target's family, or the vanilla item it held. */
        private JsonElement oreIngredient(String oreName) {
            if (oreName == null || target.tags() == null) return null;
            LegacyOreDictionary.Replacement replacement =
                    LegacyOreDictionary.replacement(oreName, target.tags());
            if (replacement == null) return null;
            // A tag only other mods fill gets a guard condition, so an empty one skips the recipe
            // instead of breaking it.
            if (replacement.guarded()) guardedTags.add(replacement.id());
            JsonObject converted = new JsonObject();
            converted.addProperty(replacement.tag() ? "tag" : "item", replacement.id());
            changed = true;
            return converted;
        }

        /** Writes the conditions in the target's syntax, or returns false when the host cannot check them. */
        private boolean conditions() {
            Conditions syntax = target.conditions();
            JsonElement shipped = json.get("conditions");
            List<JsonObject> legacy = new ArrayList<>();
            boolean convertible = syntax != null;
            // Gather the recipe's own 1.12 conditions and those of the constants it inlined. A
            // list that holds anything but objects cannot be converted.
            if (shipped instanceof JsonArray list) {
                for (JsonElement condition : list) {
                    if (condition instanceof JsonObject object) {
                        legacy.add(object);
                    } else {
                        convertible = false;
                    }
                }
            } else if (shipped != null) {
                convertible = false;
            }
            legacy.addAll(constantConditions);
            // One condition with no equivalent here means none are written.
            List<JsonObject> written = new ArrayList<>();
            for (JsonObject condition : legacy) {
                JsonObject converted = convertible ? syntax.convert(condition, namespace) : null;
                if (converted == null) {
                    convertible = false;
                    written.clear();
                    break;
                }
                written.add(converted);
            }
            // A host that ignores Forge's list would load the recipe whatever its conditions said,
            // so the recipe is removed. A host that still reads the list keeps any condition it
            // cannot take in the new form, and rejects the recipe if it does not know that either.
            boolean hasLegacy = shipped != null || !constantConditions.isEmpty();
            if (hasLegacy && !convertible && !target.readsLegacyConditions()) return false;
            // A list Forge cannot read fails the recipe there; guards written over it would not.
            if (syntax == null || (shipped != null && !(shipped instanceof JsonArray))) return true;
            // Add a guard for each guarded tag, and drop the old list once it is fully converted.
            for (String tag : guardedTags) written.add(syntax.notEmpty(tag));
            if (convertible && shipped != null) {
                json.remove("conditions");
                changed = true;
            }
            if (written.isEmpty()) return true;
            syntax.write(json, written);
            changed = true;
            return true;
        }
    }

    /** A loader's recipe condition syntax, with the field and type names checked in its jars. */
    private enum Conditions {
        /** Forge 1.19.2 and 1.20.1: a {@code conditions} list of {@code forge:} conditions. */
        FORGE_LIST("conditions", "type", "forge:"),
        /** Forge 1.20.2 to 26.3: one {@code forge:condition}, combined with {@code forge:and}. */
        FORGE_SINGLE("forge:condition", "type", "forge:"),
        /** NeoForge 20.2 to 21.3, which name a missing item {@code item_exists}. */
        NEOFORGE_EARLY("neoforge:conditions", "type", "neoforge:"),
        /** NeoForge 21.4 to 26.3, which replaced {@code item_exists} and {@code false}. */
        NEOFORGE("neoforge:conditions", "type", "neoforge:"),
        /** Fabric API for 1.21.1 to 26.3: resource conditions, keyed by {@code condition}. */
        FABRIC("fabric:load_conditions", "condition", "fabric:");

        private final String key;
        private final String typeField;
        private final String namespace;

        Conditions(String key, String typeField, String namespace) {
            this.key = key;
            this.typeField = typeField;
            this.namespace = namespace;
        }

        /** A Forge 1.12 condition in this syntax, or null when it has no equivalent here. */
        JsonObject convert(JsonObject legacy, String recipeNamespace) {
            String type = string(legacy.get("type"));
            if (type == null) return null;
            switch (type) {
                case "forge:mod_loaded" -> {
                    String modId = string(legacy.get("modid"));
                    if (modId == null) return null;
                    // Fabric API checks mods with all_mods_loaded, which takes a list.
                    if (this == FABRIC) return withList("all_mods_loaded", List.of(new JsonPrimitive(modId)));
                    JsonObject condition = typed("mod_loaded");
                    condition.addProperty("modid", modId);
                    return condition;
                }
                case "forge:not" -> {
                    JsonObject inner = legacy.get("value") instanceof JsonObject value
                            ? convert(value, recipeNamespace) : null;
                    return inner == null ? null : not(inner);
                }
                case "forge:and", "forge:or" -> {
                    if (!(legacy.get("values") instanceof JsonArray values)) return null;
                    List<JsonElement> converted = new ArrayList<>();
                    for (JsonElement value : values) {
                        JsonObject inner = value instanceof JsonObject object
                                ? convert(object, recipeNamespace) : null;
                        if (inner == null) return null;
                        converted.add(inner);
                    }
                    return withList(type.substring("forge:".length()), converted);
                }
                case "forge:false" -> {
                    // NeoForge 21.4 calls it never, and Fabric API writes it as not true.
                    return switch (this) {
                        case NEOFORGE -> typed("never");
                        case FABRIC -> not(typed("true"));
                        default -> typed("false");
                    };
                }
                case "minecraft:item_exists" -> {
                    String item = string(legacy.get("item"));
                    if (item == null) return null;
                    // Forge 1.12 read a bare item id here as the mod's own, and ids ignored case.
                    String id = item.indexOf(':') < 0 ? recipeNamespace + ":" + item : item;
                    return itemExists(id.toLowerCase(Locale.ROOT));
                }
                default -> {
                    // No equivalent. That covers a condition type from the mod's own
                    // _factories.json, whose code nothing runs any more.
                    return null;
                }
            }
        }

        /** A condition that holds while an item is registered. */
        private JsonObject itemExists(String item) {
            // NeoForge 21.4 and Fabric API name it by registry. Their registry field defaults to
            // items, and is written out anyway.
            JsonObject condition;
            switch (this) {
                case NEOFORGE -> {
                    condition = typed("registered");
                    condition.addProperty("registry", "minecraft:item");
                    condition.addProperty("value", item);
                }
                case FABRIC -> {
                    condition = withList("registry_contains", List.of(new JsonPrimitive(item)));
                    condition.addProperty("registry", "minecraft:item");
                }
                default -> {
                    condition = typed("item_exists");
                    condition.addProperty("item", item);
                }
            }
            return condition;
        }

        /** A condition that holds while a tag has entries. */
        JsonObject notEmpty(String tag) {
            if (this == FABRIC) return withList("tags_populated", List.of(new JsonPrimitive(tag)));
            JsonObject empty = typed("tag_empty");
            empty.addProperty("tag", tag);
            return not(empty);
        }

        /** Adds the conditions to a recipe under this syntax's key. */
        void write(JsonObject recipe, List<JsonObject> conditions) {
            // Forge 1.20.2 and newer take one condition, so several are combined with and.
            if (this == FORGE_SINGLE) {
                recipe.add(key, conditions.size() == 1 ? conditions.get(0)
                        : withList("and", new ArrayList<>(conditions)));
                return;
            }
            // The list forms add to any list the recipe already has.
            JsonArray list = recipe.get(key) instanceof JsonArray existing ? existing : new JsonArray();
            conditions.forEach(list::add);
            recipe.add(key, list);
        }

        private JsonObject typed(String name) {
            JsonObject condition = new JsonObject();
            condition.addProperty(typeField, namespace + name);
            return condition;
        }

        private JsonObject not(JsonObject inner) {
            JsonObject condition = typed("not");
            condition.add("value", inner);
            return condition;
        }

        private JsonObject withList(String name, List<JsonElement> values) {
            JsonObject condition = typed(name);
            JsonArray array = new JsonArray();
            values.forEach(array::add);
            condition.add("values", array);
            return condition;
        }
    }

    private static String string(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString() : null;
    }
}
