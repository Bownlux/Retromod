/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.retromod.util.JsonSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Keeps data that {@link ModDataLayoutMigrator} copied from stopping a world from loading. */
final class DataLoadGate implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataLayout");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** The first host that loads recipes, loot tables and advancements with the registries. */
    static final String FIRST_REGISTRY_LOADED_HOST = "26.3";
    // Size and nesting limits for reading one copied JSON file.
    private static final long MAX_JSON_BYTES = 16L * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 256;

    /** Folders whose files load with the registries on 26.3 and newer. */
    private static final Set<String> REGISTRY_FOLDERS = Set.of(
            "recipe", "loot_table", "advancement", "predicate", "item_modifier");

    // The folders whose Minecraft ids a kept file must name as the host ships them. Checked in the
    // 26.3 client jar: recipe triggers, the container loot trigger, loot rewards and nested loot
    // table entries read holder sets of these registries, and a condition named by id is a holder.
    private static final Set<String> BOUND_VANILLA_REFERENCES = Set.of("recipe", "loot_table", "predicate");

    /** Recipe types whose whole shape this gate checks, and the ingredient fields each one has. */
    private static final Map<String, List<String>> CHECKED_RECIPES = Map.of(
            "minecraft:crafting_shaped", List.of(),
            "minecraft:crafting_shapeless", List.of(),
            "minecraft:smelting", List.of("ingredient"),
            "minecraft:blasting", List.of("ingredient"),
            "minecraft:smoking", List.of("ingredient"),
            "minecraft:campfire_cooking", List.of("ingredient"),
            "minecraft:stonecutting", List.of("ingredient"),
            "minecraft:smithing_transform", List.of("template", "base", "addition"));

    /** Every field those recipe types read. Anything else, such as Forge conditions, is unknown. */
    private static final Set<String> KNOWN_RECIPE_FIELDS = Set.of("type", "category", "group",
            "show_notification", "pattern", "key", "ingredients", "ingredient", "result",
            "experience", "cookingtime", "template", "base", "addition");

    /** Item and tag namespaces every host provides. */
    private static final Set<String> HOST_NAMESPACES = Set.of("minecraft", "c");

    private static final Pattern IDENTIFIER = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");

    @Override
    public String name() {
        return "data load gate";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        // Only copied files are judged, so a mod with no copies is left alone.
        return !context.ledger().relocations().isEmpty();
    }

    /** Judges every copied file and removes the ones the host would reject or misread. */
    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // Copied data was invisible to the game before, so a file that fails to parse is new
        // trouble. Up to 26.2 the game skips a broken recipe, loot table or advancement with a
        // log line, so those copies stay. From 26.3 they load with the registries, and one broken
        // file stops the world.
        boolean registryLoaded = context.targetAtLeast(FIRST_REGISTRY_LOADED_HOST);
        Set<String> registered = registeredNamespaces(tree, context);
        Map<String, RegistryDataShapes> referencing = new LinkedHashMap<>();
        int changed = 0;
        int removed = 0;
        for (MigrationLedger.Relocation copy : context.ledger().relocations()) {
            if (!tree.exists(copy.to())) continue;
            // Copied tags stay, with every entry made optional. Of the rest, only registry
            // folders are judged.
            if (copy.kind().startsWith("tags/")) {
                changed += makeTagEntriesOptional(tree, copy);
                continue;
            }
            if (!REGISTRY_FOLDERS.contains(copy.kind())) continue; // structures and functions
            // On every host, a copy that names an item by pre-1.13 metadata goes, because it
            // parses and then silently matches the wrong item. From 26.3 a copy stays only when
            // this gate can show the game will read it.
            JsonElement json = parseStrict(tree, copy.to());
            String reason;
            if (json != null && namesItemMetadata(json)) {
                reason = "it names an item by pre-1.13 metadata";
            } else if (!registryLoaded) {
                continue;
            } else if (json == null) {
                reason = "it is not strict JSON";
            } else if (copy.kind().equals("recipe")) {
                Verdict verdict = json.isJsonObject()
                        ? checkRecipe(json.getAsJsonObject(), registered, context.targetLoader())
                        : Verdict.reject("the recipe is not an object");
                if (verdict.rewritten() != null) {
                    tree.write(copy.to(), GSON.toJson(verdict.rewritten()).getBytes(StandardCharsets.UTF_8));
                    changed++;
                }
                reason = verdict.problem();
            } else {
                // Loot tables, predicates, item modifiers and advancements are checked against the
                // 26.3 shapes, after the data content step converted them. A kept one is
                // remembered for the reference check below.
                RegistryDataShapes shapes = new RegistryDataShapes(registered,
                        conditionKey(context.targetLoader()));
                reason = checkShape(shapes, copy.kind(), json);
                if (reason == null) referencing.put(copy.to(), shapes);
            }
            if (reason == null) continue;
            // The original is never touched, so a removed copy only leaves the data as inert as
            // it was before.
            tree.delete(copy.to());
            LOGGER.debug("Kept {} out of {}: {}", copy.from(), copy.to(), reason);
            removed++;
        }
        if (registryLoaded) removed += keepOutDanglingReferences(tree, referencing, registered);
        if (removed > 0) {
            LOGGER.info("Kept {} copied data file(s) out of Minecraft {}, which would reject or "
                    + "misread them; the originals are unchanged", removed,
                    context.targetMcVersion());
        }
        return changed + removed;
    }

    /** Why a loot table, predicate, item modifier or advancement is not current, or null. */
    private static String checkShape(RegistryDataShapes shapes, String kind, JsonElement json) {
        try {
            switch (kind) {
                case "loot_table" -> shapes.lootTable(json);
                case "predicate" -> shapes.predicate(json);
                case "item_modifier" -> shapes.itemModifier(json);
                case "advancement" -> shapes.advancement(json);
                default -> {
                    // Nothing here can show any other kind parses, so it is kept out.
                    return "this kind of data is not checked for hosts that load it with the registries";
                }
            }
            return null;
        } catch (RegistryDataShapes.Problem problem) {
            return problem.getMessage();
        }
    }

    /** Removes every kept copy that names data the host will not have. */
    private static int keepOutDanglingReferences(ResourceTree tree, Map<String, RegistryDataShapes> referencing,
                                                 Set<String> registered) throws IOException {
        // Repeat until none is left, since each removal can leave another file dangling. From
        // 26.3 a dangling reference can stop the world from loading.
        int removed = 0;
        boolean again = true;
        while (again) {
            again = false;
            for (var kept = referencing.entrySet().iterator(); kept.hasNext(); ) {
                Map.Entry<String, RegistryDataShapes> file = kept.next();
                String missing = missingReference(tree, file.getKey(), file.getValue(), registered);
                if (missing == null) continue;
                tree.delete(file.getKey());
                LOGGER.debug("Kept {} out: it names {}, which this host will not have", file.getKey(), missing);
                kept.remove();
                removed++;
                again = true;
            }
        }
        return removed;
    }

    /** The first id the file names that the host will not have, or null. */
    private static String missingReference(ResourceTree tree, String file, RegistryDataShapes shapes,
                                           Set<String> registered) {
        // The ids the shape check collected, by the folder each one points into.
        Map<String, Set<String>> byFolder = Map.of("recipe", shapes.recipes, "loot_table", shapes.lootTables,
                "advancement", shapes.advancements, "predicate", shapes.predicates,
                "worldgen/structure", shapes.structures, "worldgen/biome", shapes.biomes);
        String root = dataRoot(file);
        for (Map.Entry<String, Set<String>> folder : byFolder.entrySet()) {
            for (String id : folder.getValue()) {
                int colon = id.indexOf(':');
                String namespace = id.substring(0, colon);
                if (namespace.equals("minecraft")) {
                    // 26.3 binds these as registry references, and an old mod can name one a
                    // later release renamed, so it must be one the host ships. An advancement's
                    // parent is a plain id that nothing binds, so Minecraft's own parents count.
                    if (BOUND_VANILLA_REFERENCES.contains(folder.getKey())
                            && !VanillaRegistryIds.contains(folder.getKey(), id)) {
                        return folder.getKey() + " " + id;
                    }
                    continue;
                }
                // This mod's own id must point at a file that is still here. Another mod's id is
                // never taken as present, since that mod may be missing.
                String path = root + namespace + "/" + folder.getKey() + "/" + id.substring(colon + 1) + ".json";
                if (!registered.contains(namespace) || !tree.exists(path)) return folder.getKey() + " " + id;
            }
        }
        return null;
    }

    /** The {@code data/} folder a file sits in, which may be inside a data pack the jar ships. */
    private static String dataRoot(String file) {
        int at = 0;
        for (String segment : file.split("/")) {
            if (segment.equals("data")) return file.substring(0, at) + "data/";
            at += segment.length() + 1;
        }
        return "data/";
    }

    /** A copied tag whose entries may be missing on this host no longer fails the whole tag. */
    private static int makeTagEntriesOptional(ResourceTree tree, MigrationLedger.Relocation copy)
            throws IOException {
        JsonObject tag = parseStrict(tree, copy.to()) instanceof JsonObject object ? object : null;
        if (tag == null || !(tag.get("values") instanceof JsonArray values)) {
            // A tag the host cannot read fails as a whole, so the copy goes.
            tree.delete(copy.to());
            LOGGER.debug("Kept {} out of {}: the tag does not parse", copy.from(), copy.to());
            return 1;
        }
        // One missing entry fails the whole tag. A mod's addition to a vanilla tag such as
        // minecraft:mineable/pickaxe would then empty that tag for every block.
        boolean changed = false;
        for (int i = 0; i < values.size(); i++) {
            JsonElement value = values.get(i);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                JsonObject optional = new JsonObject();
                optional.addProperty("id", value.getAsString());
                optional.addProperty("required", false);
                values.set(i, optional);
                changed = true;
            } else if (value instanceof JsonObject entry && entry.has("id")
                    && !Boolean.FALSE.equals(requiredFlag(entry))) {
                entry.addProperty("required", false);
                changed = true;
            }
        }
        if (!changed) return 0;
        tree.write(copy.to(), GSON.toJson(tag).getBytes(StandardCharsets.UTF_8));
        return 1;
    }

    /** The entry's {@code required} flag, or null when it has no boolean one. */
    private static Boolean requiredFlag(JsonObject entry) {
        JsonElement required = entry.get("required");
        return required != null && required.isJsonPrimitive() && required.getAsJsonPrimitive().isBoolean()
                ? required.getAsBoolean() : null;
    }

    /** The outcome for one recipe: a problem that keeps it out, or a rewrite that lets it load. */
    private record Verdict(String problem, JsonObject rewritten) {
        static Verdict keep() {
            return new Verdict(null, null);
        }

        static Verdict reject(String problem) {
            return new Verdict(problem, null);
        }
    }

    /** The namespaces whose items this mod registers itself. */
    static Set<String> registeredNamespaces(ResourceTree tree, MigrationContext context)
            throws IOException {
        // The ids its metadata declares, plus every namespace it names blocks or items in through
        // its own language files or blockstates. A data folder alone is no proof, since mods ship
        // recipes for other mods' items, and an item that is not there on 26.3 stops the world
        // rather than skipping the recipe.
        Set<String> namespaces = new TreeSet<>(context.declaredNamespaces());
        for (String name : tree.names()) {
            if (!name.startsWith("assets/")) continue;
            String[] parts = name.split("/", 4);
            if (parts.length == 4 && (parts[2].equals("lang") || parts[2].equals("blockstates"))
                    && !MigrationContext.SHARED_NAMESPACES.contains(parts[1])) {
                namespaces.add(parts[1]);
            }
        }
        return namespaces;
    }

    /** Checks a recipe's whole shape, and guards other mods' items with a load condition. */
    static Verdict checkRecipe(JsonObject recipe, Set<String> registeredNamespaces,
                               MigrationContext.Loader loader) {
        // Only the common vanilla types are checked. Any other type is kept out, since nothing
        // here can show it parses.
        String type = namespaced(stringValue(recipe.get("type")));
        if (type == null || !CHECKED_RECIPES.containsKey(type)) {
            return Verdict.reject("recipe type " + type + " is not one whose shape is checked");
        }
        // A field the host does not read is unknown. Only the host loader's own condition key is
        // allowed beside the vanilla fields.
        String conditionKey = conditionKey(loader);
        for (String field : recipe.keySet()) {
            if (!KNOWN_RECIPE_FIELDS.contains(field) && !field.equals(conditionKey)) {
                return Verdict.reject("it has the field " + field + ", which the host does not read");
            }
        }
        // Check the ingredient and result shapes, and collect every namespace they name.
        Set<String> referenced = new TreeSet<>();
        String shapeProblem = switch (type) {
            case "minecraft:crafting_shaped" -> checkShaped(recipe, referenced);
            case "minecraft:crafting_shapeless" -> checkIngredientList(recipe.get("ingredients"), referenced);
            default -> checkIngredientFields(recipe, CHECKED_RECIPES.get(type), type, referenced);
        };
        if (shapeProblem == null) shapeProblem = checkResult(recipe.get("result"), referenced);
        if (shapeProblem != null) return Verdict.reject(shapeProblem);

        // Another mod's items get the host loader's mod-loaded condition, as 1.12 Forge skipped
        // a recipe naming a missing item.
        Set<String> foreign = new TreeSet<>();
        for (String namespace : referenced) {
            if (!HOST_NAMESPACES.contains(namespace) && !registeredNamespaces.contains(namespace)) {
                foreign.add(namespace);
            }
        }
        if (foreign.isEmpty()) return Verdict.keep();
        JsonObject conditioned = withModLoadedCondition(recipe, foreign, loader);
        if (conditioned == null) {
            return Verdict.reject("it names items from " + foreign
                    + " and the host loader has no load condition to guard them");
        }
        return new Verdict(null, conditioned);
    }

    /** Checks a shaped recipe's pattern rows and every ingredient in its key. */
    private static String checkShaped(JsonObject recipe, Set<String> referenced) {
        if (!(recipe.get("pattern") instanceof JsonArray pattern) || pattern.isEmpty()) {
            return "the shaped recipe has no pattern";
        }
        for (JsonElement row : pattern) {
            if (stringValue(row) == null) return "a pattern row is not a string";
        }
        if (!(recipe.get("key") instanceof JsonObject key) || key.isEmpty()) {
            return "the shaped recipe has no key";
        }
        for (Map.Entry<String, JsonElement> symbol : key.entrySet()) {
            String problem = checkIngredient(symbol.getValue(), referenced);
            if (problem != null) return problem;
        }
        return null;
    }

    /** Checks a shapeless recipe's list of ingredients. */
    private static String checkIngredientList(JsonElement list, Set<String> referenced) {
        if (!(list instanceof JsonArray ingredients) || ingredients.isEmpty()) {
            return "the recipe has no ingredients";
        }
        for (JsonElement ingredient : ingredients) {
            String problem = checkIngredient(ingredient, referenced);
            if (problem != null) return problem;
        }
        return null;
    }

    /** Checks the named ingredient fields of a cooking, stonecutting, or smithing recipe. */
    private static String checkIngredientFields(JsonObject recipe, List<String> fields, String type,
                                                Set<String> referenced) {
        for (String field : fields) {
            JsonElement ingredient = recipe.get(field);
            // A smithing template is optional; every other field here is required.
            if (ingredient == null && field.equals("template")) continue;
            if (ingredient == null) return type + " has no " + field;
            String problem = checkIngredient(ingredient, referenced);
            if (problem != null) return problem;
        }
        return null;
    }

    /** Checks one ingredient, or returns why it is not in a current form. */
    private static String checkIngredient(JsonElement ingredient, Set<String> referenced) {
        // An item id, a #tag, or a list of item ids: the only vanilla forms since 1.21.2.
        String single = stringValue(ingredient);
        if (single != null) {
            return single.startsWith("#") ? recordIdentifier(single.substring(1), referenced)
                    : recordItem(single, referenced);
        }
        if (ingredient instanceof JsonArray alternatives && !alternatives.isEmpty()) {
            for (JsonElement alternative : alternatives) {
                String id = stringValue(alternative);
                if (id == null || id.startsWith("#")) return "an ingredient list holds something but item ids";
                String problem = recordItem(id, referenced);
                if (problem != null) return problem;
            }
            return null;
        }
        // The NeoForge and Fabric component ingredients the data content step writes for an item
        // that had to carry components. Their inner items are checked like any other.
        if (ingredient instanceof JsonObject custom && custom.size() == 3
                && custom.get("components") instanceof JsonObject) {
            if ("neoforge:components".equals(stringValue(custom.get("neoforge:ingredient_type")))) {
                return checkIngredient(custom.get("items"), referenced);
            }
            if ("fabric:components".equals(stringValue(custom.get("fabric:type")))) {
                return checkIngredient(custom.get("base"), referenced);
            }
        }
        return "an ingredient is not in the current id or tag form";
    }

    /** The current result shape: an object naming the item by {@code id}, as 1.20.5 changed it. */
    private static String checkResult(JsonElement result, Set<String> referenced) {
        if (!(result instanceof JsonObject stack)) return "the result is not an item stack object";
        String id = stringValue(stack.get("id"));
        if (id == null) return "the result does not name its item by id";
        for (String field : stack.keySet()) {
            if (!field.equals("id") && !field.equals("count") && !field.equals("components")) {
                return "the result has the old field " + field;
            }
        }
        JsonElement count = stack.get("count");
        if (count != null && !(count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber())) {
            return "the result count is not a number";
        }
        JsonElement components = stack.get("components");
        if (components != null && !components.isJsonObject()) return "the result components are not an object";
        return recordItem(id, referenced);
    }

    /** {@link #recordIdentifier}, for an item: a vanilla one must be an item the host has. */
    private static String recordItem(String id, Set<String> referenced) {
        String problem = recordIdentifier(id, referenced);
        if (problem != null) return problem;
        String full = namespaced(id);
        if (full.startsWith("minecraft:") && !VanillaRegistryIds.contains("item", full)) {
            return "Minecraft has no item " + full;
        }
        return null;
    }

    /** Records the id's namespace, or returns why the id is not valid. */
    private static String recordIdentifier(String id, Set<String> referenced) {
        if (!IDENTIFIER.matcher(id).matches()) return "the id " + id + " is not a valid identifier";
        int colon = id.indexOf(':');
        referenced.add(colon < 0 ? "minecraft" : id.substring(0, colon));
        return null;
    }

    /** The key the host loader reads load conditions from, or null when Retromod writes none. */
    private static String conditionKey(MigrationContext.Loader loader) {
        if (loader == MigrationContext.Loader.NEOFORGE) return "neoforge:conditions";
        if (loader.isFabricFamily()) return "fabric:load_conditions";
        return null;
    }

    /** The recipe guarded by the host loader's mod-loaded condition, or null when it has none. */
    private static JsonObject withModLoadedCondition(JsonObject recipe, Set<String> modIds,
                                                     MigrationContext.Loader loader) {
        String key = conditionKey(loader);
        if (key == null) return null;
        // The new condition joins any the recipe already has.
        JsonObject guarded = recipe.deepCopy();
        JsonArray conditions = guarded.get(key) instanceof JsonArray existing
                ? existing : new JsonArray();
        // NeoForge takes one mod_loaded condition per mod, and Fabric one all_mods_loaded
        // condition for them all. Both were checked in the 26.3 NeoForge and Fabric API jars.
        if (loader == MigrationContext.Loader.NEOFORGE) {
            for (String modId : modIds) {
                JsonObject condition = new JsonObject();
                condition.addProperty("type", "neoforge:mod_loaded");
                condition.addProperty("modid", modId);
                conditions.add(condition);
            }
        } else {
            JsonArray values = new JsonArray();
            modIds.forEach(values::add);
            JsonObject condition = new JsonObject();
            condition.addProperty("condition", "fabric:all_mods_loaded");
            condition.add("values", values);
            conditions.add(condition);
        }
        guarded.add(key, conditions);
        return guarded;
    }

    /** True for a 1.12 item reference with {@code data}, or the {@code set_data} loot function. */
    static boolean namesItemMetadata(JsonElement element) {
        // Search the whole tree, since such a reference can sit at any depth.
        if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                if (namesItemMetadata(child)) return true;
            }
            return false;
        }
        if (!(element instanceof JsonObject object)) return false;
        if (object.has("data") && (object.has("item") || object.has("id"))) return true;
        String function = namespaced(stringValue(object.get("function")));
        if ("minecraft:set_data".equals(function)) return true;
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            if (namesItemMetadata(field.getValue())) return true;
        }
        return false;
    }

    /** Parses a copy as the strict hosts read it, or returns null when that fails. */
    private static JsonElement parseStrict(ResourceTree tree, String name) {
        try {
            // Comments and trailing commas are stripped first, as the data content step does for
            // strict hosts, so a lenient host's copy is not judged by a rule it does not apply.
            byte[] bytes = ModDataMigrator.normalizeLenientJson(tree.read(name, MAX_JSON_BYTES));
            JsonSecurity.validate(bytes, MAX_JSON_BYTES, MAX_JSON_DEPTH, "Copied data JSON");
            JsonReader reader = new JsonReader(new StringReader(StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()));
            // Read through the adapter: JsonParser.parseReader turns leniency back on.
            reader.setLenient(false);
            JsonElement root = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) return null;
            return root.isJsonObject() || root.isJsonArray() ? root : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String stringValue(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString() : null;
    }

    private static String namespaced(String id) {
        if (id == null) return null;
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }
}
