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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Names vanilla items and entities in a 1.12 mod's copied data the way the target game does. */
final class LegacyItemFlattening implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataLayout");

    /** The first host whose items have no data values. */
    static final String FIRST_FLATTENED_HOST = "1.13";
    // The first host whose item predicates read an items list. Checked in the client jars:
    // 1.16.1 reads one item, 1.19 and 1.20.1 read items, and 1.17 made the change.
    static final String FIRST_ITEM_LIST_PREDICATE_HOST = "1.17";
    /** 1.12's "any data value", {@code OreDictionary.WILDCARD_VALUE}. */
    private static final int WILDCARD = 32767;
    // A reference that names no data value, and one whose value is not a whole number in range.
    private static final int ABSENT = -1;
    private static final int INVALID = -2;

    @Override
    public String name() {
        return "1.12 item ids";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.sourceBefore(FIRST_FLATTENED_HOST) && context.targetAtLeast(FIRST_FLATTENED_HOST);
    }

    /** Rewrites the item ids in every copied recipe, loot table and advancement. */
    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // 1.12 data named an item by an id and a data value (minecraft:dye with data 4 is lapis
        // lazuli), and set a loot drop's value with set_data. 1.13 removed both, so every such
        // file failed to parse once it was copied where the game reads it. Recipes and loot
        // tables are written in the 1.20.1 shape, and the data content step then brings them to
        // the target's shape.
        int changed = 0;
        for (String kind : List.of("recipe", "loot_table", "advancement")) {
            for (MigrationLedger.Relocation copy : LegacyDataCopies.of(tree, context, kind)) {
                JsonObject json = LegacyDataCopies.read(tree, copy.to());
                if (json == null) continue;
                Rewriter rewriter = new Rewriter(context, LegacyDataCopies.namespace(copy.to()));
                switch (kind) {
                    case "recipe" -> rewriter.recipe(json);
                    case "loot_table" -> rewriter.lootEntries(json);
                    default -> rewriter.advancement(json);
                }
                if (rewriter.changed) {
                    LegacyDataCopies.write(tree, copy.to(), json);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            LOGGER.info("Renamed 1.12 item ids in {} copied data file(s) for Minecraft {}", changed,
                    context.targetMcVersion());
        }
        return changed;
    }

    /** Rewrites the references in one copied file through {@link LegacyItemIds}. */
    private static final class Rewriter {
        private final MigrationContext context;
        private final String namespace;
        private boolean changed;

        Rewriter(MigrationContext context, String namespace) {
            this.context = context;
            this.namespace = namespace;
        }

        /** Rewrites a recipe's key, its ingredient list, and its result. */
        void recipe(JsonObject recipe) {
            if (recipe.get("key") instanceof JsonObject key) {
                for (Map.Entry<String, JsonElement> symbol : key.entrySet()) {
                    JsonElement converted = ingredient(symbol.getValue());
                    if (converted != null) symbol.setValue(converted);
                }
            }
            if (recipe.get("ingredients") instanceof JsonArray ingredients) {
                for (int i = 0; i < ingredients.size(); i++) {
                    JsonElement converted = ingredient(ingredients.get(i));
                    if (converted != null) ingredients.set(i, converted);
                }
            }
            if (recipe.get("result") instanceof JsonObject result) produced(result, true);
        }

        /** Rewrites an advancement's icon and the items and entities its criteria name. */
        void advancement(JsonObject advancement) {
            if (advancement.get("display") instanceof JsonObject display
                    && display.get("icon") instanceof JsonObject icon) {
                produced(icon, false);
            }
            if (advancement.get("criteria") instanceof JsonObject criteria) {
                for (Map.Entry<String, JsonElement> criterion : criteria.entrySet()) {
                    if (criterion.getValue() instanceof JsonObject body) predicates(body.get("conditions"));
                }
            }
        }

        /** Every item entry of a loot table, at any depth, so entries nested in groups are covered. */
        void lootEntries(JsonElement element) {
            if (element instanceof JsonArray array) {
                for (JsonElement child : array) lootEntries(child);
                return;
            }
            if (!(element instanceof JsonObject object)) return;
            String type = string(object.get("type"));
            boolean itemEntry = "item".equals(type) || "minecraft:item".equals(type);
            if (itemEntry && string(object.get("name")) != null) {
                lootItem(object);
            }
            for (Map.Entry<String, JsonElement> field : object.entrySet()) lootEntries(field.getValue());
        }

        /** Folds a vanilla drop's {@code set_data}, which 1.13 removed, into the drop's id. */
        private void lootItem(JsonObject entry) {
            String id = vanilla(resolve(string(entry.get("name")), false));
            if (id == null) return;
            JsonArray functions = entry.get("functions") instanceof JsonArray array ? array : null;
            int setData = -1;
            int data = ABSENT;
            if (functions != null) {
                for (int i = 0; i < functions.size(); i++) {
                    if (!(functions.get(i) instanceof JsonObject function)
                            || !isSetData(string(function.get("function")))) {
                        continue;
                    }
                    // A second or conditional set_data picks the value at drop time.
                    if (setData >= 0 || function.has("conditions")) return;
                    setData = i;
                    data = dataValue(function.get("data"));
                }
            }
            // Without a set_data the drop had value 0. Once the value is in the id, the function
            // goes.
            String target = producedId(id, data);
            if (target == null || (target.equals(id) && setData < 0)) return;
            entry.addProperty("name", target);
            if (setData >= 0) {
                functions.remove(setData);
                if (functions.isEmpty()) entry.remove("functions");
            }
            changed = true;
        }

        /** Item and entity references in advancement criteria. */
        private void predicates(JsonElement element) {
            if (element instanceof JsonArray array) {
                for (JsonElement child : array) predicates(child);
                return;
            }
            if (!(element instanceof JsonObject object)) return;
            // A vanilla item with a data value, or one 1.13 renamed, gets a new item predicate.
            String item = string(object.get("item"));
            String itemId = item == null ? null : vanilla(resolve(item, false));
            List<String> items = itemId == null ? null : matched(itemId, dataOf(object), false);
            boolean newItems = items != null && (items.size() != 1 || !items.get(0).equals(itemId));
            if (items != null && (object.has("data") || newItems)) itemPredicate(object, items);
            // A renamed vanilla entity id becomes its new name.
            String type = string(object.get("type"));
            String entity = type == null || type.isEmpty() ? null : vanilla(resolve(type, false));
            if (entity != null) {
                String renamed = LegacyItemIds.entity(entity, context);
                if (!renamed.equals(entity)) {
                    object.addProperty("type", renamed);
                    changed = true;
                }
            }
            for (Map.Entry<String, JsonElement> field : object.entrySet()) predicates(field.getValue());
        }

        /** Names the items of a rewritten predicate the way the target reads them. */
        private void itemPredicate(JsonObject predicate, List<String> items) {
            // Without its data value a 1.12 predicate would load and match every item, and the
            // 1.16.1 to 1.20.1 clients reject a data value outright. From 1.17 a predicate lists
            // items and ignores a single item, so leaving that key would match every item.
            if (context.targetAtLeast(FIRST_ITEM_LIST_PREDICATE_HOST)) {
                JsonArray list = new JsonArray();
                items.forEach(list::add);
                predicate.remove("item");
                predicate.add("items", list);
            } else if (items.size() == 1) {
                // Before 1.17 one item is all a predicate can name, so a whole family stays for
                // the gate there.
                predicate.addProperty("item", items.get(0));
            } else {
                return;
            }
            predicate.remove("data");
            changed = true;
        }

        /** An ingredient as the target reads it, or null to leave it as shipped. */
        private JsonElement ingredient(JsonElement value) {
            // A list of alternatives is rewritten one alternative at a time.
            JsonElement converted = null;
            if (value instanceof JsonArray alternatives) {
                JsonArray list = new JsonArray();
                for (JsonElement alternative : alternatives) {
                    List<JsonElement> items = alternative instanceof JsonObject reference
                            ? alternativeItems(reference) : null;
                    if (items == null) {
                        list.add(alternative);
                    } else {
                        items.forEach(list::add);
                    }
                }
                converted = list;
            } else if (value instanceof JsonObject reference) {
                converted = singleIngredient(reference);
            }
            if (converted == null || converted.equals(value)) return null;
            changed = true;
            return converted;
        }

        /** One ingredient object as the target reads it, or null to leave it as shipped. */
        private JsonElement singleIngredient(JsonObject reference) {
            if (!isPlainItemReference(reference)) return null;
            String id = itemId(reference);
            if (id == null) return null;
            if (vanilla(id) == null) return modItem(reference, id);
            // Any value (32767) of a flattened id becomes the vanilla tag that covers the family,
            // or else the list of its items.
            int data = dataOf(reference);
            String tag = LegacyItemIds.isFlattened(id) && data == WILDCARD
                    ? LegacyItemIds.familyTag(id) : null;
            if (tag != null) {
                JsonObject ingredient = new JsonObject();
                ingredient.addProperty("tag", tag);
                return ingredient;
            }
            List<String> items = matched(id, data, true);
            if (items == null) return null;
            if (items.size() == 1) return itemIngredient(items.get(0));
            JsonArray list = new JsonArray();
            items.forEach(item -> list.add(itemIngredient(item)));
            return list;
        }

        /** The items one alternative of a list stands for, or null to keep it as shipped. */
        private List<JsonElement> alternativeItems(JsonObject reference) {
            if (!isPlainItemReference(reference)) return null;
            String id = itemId(reference);
            if (id == null) return null;
            if (vanilla(id) == null) {
                JsonObject mod = modItem(reference, id);
                return mod == null ? null : List.of(mod);
            }
            // A list of alternatives holds items only, so a family joins it item by item.
            List<String> items = matched(id, dataOf(reference), true);
            if (items == null) return null;
            List<JsonElement> alternatives = new ArrayList<>();
            items.forEach(item -> alternatives.add(itemIngredient(item)));
            return alternatives;
        }

        /** A mod's own item with its namespace written out and "any value" dropped, or null. */
        private JsonObject modItem(JsonObject reference, String id) {
            // The mod's code registers one item for every value, so "any value" is the item
            // itself. Any other value stays, since no id can say which one is meant, and
            // DataLoadGate keeps such a copy out of the game.
            boolean wildcard = dataOf(reference) == WILDCARD;
            if (!wildcard && id.equals(string(reference.get("item")))) return null;
            JsonObject converted = reference.deepCopy();
            converted.addProperty("item", id);
            if (wildcard) converted.remove("data");
            return converted;
        }

        /** Rewrites the item of a result or icon, which makes one value: 0 when none is named. */
        private void produced(JsonObject stack, boolean recipe) {
            String raw = string(stack.get("item"));
            String id = raw == null ? null : resolve(raw, recipe);
            if (id == null) return;
            // A mod's own item only gets its namespace written out.
            if (vanilla(id) == null) {
                if (!id.equals(raw)) {
                    stack.addProperty("item", id);
                    changed = true;
                }
                return;
            }
            String target = producedId(id, dataOf(stack));
            if (target == null) return;
            // Vanilla read a bare id outside recipes as its own, so only a new name is a change.
            String before = recipe ? raw : id;
            boolean hadData = stack.remove("data") != null;
            if (hadData || !target.equals(before)) {
                stack.addProperty("item", target);
                changed = true;
            }
        }

        /** The id a made item has on the target, or null when no single item fits. */
        private String producedId(String id, int data) {
            // Where 1.13 split one item in two, a made item is the one 1.13's own recipes used: a
            // pumpkin a recipe makes or a loot table drops is the uncarved one. An item 1.13 kept
            // whole only made value 0.
            if (LegacyItemIds.isFlattened(id)) {
                if (data == ABSENT) return LegacyItemIds.flattened(id, 0, context);
                return data < 0 || data == WILDCARD ? null : LegacyItemIds.flattened(id, data, context);
            }
            return data == ABSENT || data == 0 ? LegacyItemIds.renamed(id, context) : null;
        }

        /** The items a matching reference accepts on the target, or null when ids cannot say it. */
        private List<String> matched(String id, int data, boolean ingredient) {
            // "Any value" of a flattened id is its whole family. A predicate without a value
            // matched any, while 1.12's recipe parser built value 0 for an ingredient without one.
            // Where 1.13 split one item in two, the reference takes both: a 1.12 pumpkin
            // ingredient takes the uncarved and the carved pumpkin.
            if (LegacyItemIds.isFlattened(id)) {
                if (data == ABSENT) data = ingredient ? 0 : WILDCARD;
                if (data == WILDCARD) return LegacyItemIds.family(id, context);
                String flattened = data < 0 ? null : LegacyItemIds.flattened(id, data, context);
                if (flattened == null) return null;
                List<String> items = new ArrayList<>(List.of(flattened));
                items.addAll(LegacyItemIds.alsoAccepted(id, data, context));
                return items;
            }
            // An item 1.13 kept whole had only value 0, so 0 and "any" name the same item. Any
            // other value is a tool's damage, which a 1.13 ingredient cannot ask for.
            if (data == ABSENT || data == 0 || data == WILDCARD) {
                return List.of(LegacyItemIds.renamed(id, context));
            }
            return null;
        }

        /** The namespaced id of an ingredient's item, or null when it cannot be resolved. */
        private String itemId(JsonObject reference) {
            return resolve(string(reference.get("item")), true);
        }

        /** A namespaced id, or null for a tag or a missing id. */
        private String resolve(String raw, boolean recipe) {
            if (raw == null || raw.startsWith("#")) return null;
            // 1.12 identifiers ignored case, so the vanilla namespace is matched in any case.
            if (raw.indexOf(':') >= 0) {
                String lower = raw.toLowerCase(Locale.ROOT);
                return lower.startsWith("minecraft:") ? lower : raw;
            }
            // Forge 1.12 gave a bare id in a recipe the loading mod's id, whatever item it named,
            // so it gets the recipe's namespace. Vanilla read a bare id elsewhere as minecraft.
            return (recipe ? namespace : "minecraft") + ":" + raw.toLowerCase(Locale.ROOT);
        }
    }

    /** An item ingredient with nothing beyond an id and a data value, so rewriting it loses nothing. */
    private static boolean isPlainItemReference(JsonObject reference) {
        String item = string(reference.get("item"));
        if (item == null || item.startsWith("#")) return false;
        if (reference.has("type") && !"minecraft:item".equals(string(reference.get("type")))) return false;
        for (String field : reference.keySet()) {
            if (!field.equals("item") && !field.equals("data") && !field.equals("type")) return false;
        }
        return true;
    }

    private static JsonObject itemIngredient(String id) {
        JsonObject item = new JsonObject();
        item.addProperty("item", id);
        return item;
    }

    /** The id when it is Minecraft's, or null for a mod's id. */
    private static String vanilla(String id) {
        return id != null && id.startsWith("minecraft:") ? id : null;
    }

    private static boolean isSetData(String function) {
        return "set_data".equals(function) || "minecraft:set_data".equals(function);
    }

    /** The reference's data value, or ABSENT when it names none. */
    private static int dataOf(JsonObject reference) {
        return reference.has("data") ? dataValue(reference.get("data")) : ABSENT;
    }

    /** A 1.12 data value: a whole number from 0 to 32767. Anything else, such as a range, is INVALID. */
    private static int dataValue(JsonElement data) {
        if (data instanceof JsonPrimitive primitive && primitive.isNumber()) {
            double value = primitive.getAsDouble();
            if (value == Math.rint(value) && value >= 0 && value <= WILDCARD) return (int) value;
        }
        return INVALID;
    }

    private static String string(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString() : null;
    }
}
