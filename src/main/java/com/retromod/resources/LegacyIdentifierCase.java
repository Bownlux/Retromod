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
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.retromod.util.JsonSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Lowercases the identifiers a 1.12 mod wrote in mixed case. */
final class LegacyIdentifierCase implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** The first host that rejects uppercase identifiers instead of lowercasing them. */
    static final String FIRST_CASE_SENSITIVE_HOST = "1.13";

    private static final long MAX_JSON_BYTES = JsonSecurity.DEFAULT_MAX_BYTES;
    private static final Pattern IDENTIFIER = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");
    private static final Pattern IDENTIFIER_PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");

    /** Forge 1.12 recipe helpers, whose constant names recipes reference case-sensitively. */
    private static final Set<String> FORGE_RECIPE_HELPERS = Set.of("_constants.json", "_factories.json");

    /** The asset kinds the game loads by identifier, with the file endings each one holds. */
    private static final Map<String, List<String>> COPIED_ASSET_KINDS = Map.of(
            "textures", List.of(".png", ".png.mcmeta"),
            "models", List.of(".json"),
            "blockstates", List.of(".json"),
            "sounds", List.of(".ogg"));

    @Override
    public String name() {
        return "1.12 identifier case";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.sourceBefore(FIRST_CASE_SENSITIVE_HOST)
                && context.targetAtLeast(FIRST_CASE_SENSITIVE_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // 1.12 lowercased every identifier it parsed, and 1.13 rejects one with an uppercase letter,
        // failing the whole file. Lowercasing only where each file kind reads an identifier matches
        // 1.12. Free text, such as translation keys and ore dictionary names, is never touched.
        int rewritten = 0;
        for (String name : tree.names()) {
            FileKind kind = FileKind.of(name);
            if (kind != null && rewriteReferences(tree, name, kind)) rewritten++;
        }
        // Then files whose own names have uppercase letters get lowercase copies.
        int copied = copyMixedCaseFiles(tree, context);
        if (rewritten + copied > 0) {
            LOGGER.info("Lowercased the 1.12 identifiers in {} file(s) and copied {} file(s) to "
                    + "lowercase names for Minecraft {}", rewritten, copied, context.targetMcVersion());
        }
        return rewritten + copied;
    }

    /** The files whose identifier positions this converter knows, by where they sit. */
    private enum FileKind {
        MODEL, BLOCKSTATE, SOUNDS, RECIPE, ADVANCEMENT, LOOT_TABLE;

        static FileKind of(String name) {
            String[] parts = name.split("/");
            if (parts.length < 3 || !parts[0].equals("assets")) return null;
            if (parts.length == 3) return parts[2].equals("sounds.json") ? SOUNDS : null;
            if (!name.endsWith(".json")) return null;
            // 1.12 kept recipes, advancements and loot tables under assets/ too.
            return switch (parts[2]) {
                case "models" -> MODEL;
                case "blockstates" -> BLOCKSTATE;
                case "recipes" -> FORGE_RECIPE_HELPERS.contains(parts[parts.length - 1]) ? null : RECIPE;
                case "advancements" -> ADVANCEMENT;
                case "loot_tables" -> LOOT_TABLE;
                default -> null;
            };
        }
    }

    /** Lowercases the identifiers in one file, and writes it back when any changed. */
    private static boolean rewriteReferences(ResourceTree tree, String name, FileKind kind)
            throws IOException {
        JsonElement json = parse(tree, name);
        if (json == null) return false;
        boolean changed = switch (kind) {
            case MODEL -> rewriteModel(json);
            case BLOCKSTATE -> rewriteBlockstate(json);
            case SOUNDS -> rewriteSounds(json);
            case RECIPE -> rewriteRecipe(json);
            case ADVANCEMENT -> rewriteAdvancement(json);
            case LOOT_TABLE -> rewriteLootTable(json);
        };
        if (changed) tree.write(name, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        return changed;
    }

    /** Lowercases a model's parent, texture values and override models. */
    private static boolean rewriteModel(JsonElement json) {
        if (!(json instanceof JsonObject model)) return false;
        boolean changed = lowercaseMember(model, "parent");
        if (model.get("textures") instanceof JsonObject textures) changed |= lowercaseTextures(textures);
        if (model.get("overrides") instanceof JsonArray overrides) {
            for (JsonElement override : overrides) {
                if (override instanceof JsonObject entry) changed |= lowercaseMember(entry, "model");
            }
        }
        return changed;
    }

    /** Lowercases every {@code model} and {@code textures} value in a blockstate, at any depth. */
    private static boolean rewriteBlockstate(JsonElement json) {
        // Vanilla variants and multipart cases, and Forge's defaults, variants and submodels, all
        // use these two keys.
        boolean changed = false;
        if (json instanceof JsonObject object) {
            for (String key : List.copyOf(object.keySet())) {
                JsonElement value = object.get(key);
                if (key.equals("model")) {
                    changed |= lowercaseMember(object, key);
                } else if (key.equals("textures") && value instanceof JsonObject textures) {
                    changed |= lowercaseTextures(textures);
                } else {
                    changed |= rewriteBlockstate(value);
                }
            }
        } else if (json instanceof JsonArray array) {
            for (JsonElement element : array) changed |= rewriteBlockstate(element);
        }
        return changed;
    }

    /** Lowercases the sound event names, which are the keys, and the file or event each sound names. */
    private static boolean rewriteSounds(JsonElement json) {
        if (!(json instanceof JsonObject events)) return false;
        boolean changed = false;
        Map<String, JsonElement> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> event : events.entrySet()) {
            String key = event.getKey();
            // A key whose lowercase form is already taken stays as it is, since 1.12 merged the two
            // and nothing here can say which one the mod meant.
            String lowercase = lowercaseIdentifierPath(key);
            if (lowercase != null && !events.has(lowercase) && !renamed.containsKey(lowercase)) {
                renamed.put(lowercase, event.getValue());
                changed = true;
            } else {
                renamed.put(key, event.getValue());
            }
            // A sound is a name string, or an object whose name is a file or another event.
            if (event.getValue() instanceof JsonObject definition
                    && definition.get("sounds") instanceof JsonArray sounds) {
                for (int i = 0; i < sounds.size(); i++) {
                    JsonElement sound = sounds.get(i);
                    if (sound instanceof JsonObject entry) {
                        changed |= lowercaseMember(entry, "name");
                    } else {
                        String lowered = lowercaseIdentifier(stringOf(sound));
                        if (lowered != null) {
                            sounds.set(i, new JsonPrimitive(lowered));
                            changed = true;
                        }
                    }
                }
            }
        }
        // Rebuild the object, so a renamed event keeps its place in the file.
        if (changed) {
            for (String key : new ArrayList<>(events.keySet())) events.remove(key);
            renamed.forEach(events::add);
        }
        return changed;
    }

    /** Lowercases every {@code item} in a recipe, which 1.12 read as an item id wherever it sat. */
    private static boolean rewriteRecipe(JsonElement json) {
        // Ingredients, keys and results alike, outside NBT values. A result written as a plain
        // string is an item id too.
        boolean changed = lowercaseItems(json);
        if (json instanceof JsonObject recipe) changed |= lowercaseMember(recipe, "result");
        return changed;
    }

    /** Lowercases an advancement's parent, display ids, criteria ids and rewards. */
    private static boolean rewriteAdvancement(JsonElement json) {
        if (!(json instanceof JsonObject advancement)) return false;
        boolean changed = lowercaseMember(advancement, "parent");
        if (advancement.get("display") instanceof JsonObject display) {
            changed |= lowercaseMember(display, "background");
            if (display.get("icon") instanceof JsonObject icon) changed |= lowercaseItems(icon);
        }
        // Each criterion names its trigger and the items, recipe, block and potion it checks.
        if (advancement.get("criteria") instanceof JsonObject criteria) {
            for (Map.Entry<String, JsonElement> criterion : criteria.entrySet()) {
                if (!(criterion.getValue() instanceof JsonObject body)) continue;
                changed |= lowercaseMember(body, "trigger");
                if (body.get("conditions") instanceof JsonObject conditions) {
                    changed |= lowercaseItems(conditions);
                    changed |= lowercasePredicateIds(conditions);
                    changed |= lowercaseMember(conditions, "recipe");
                    changed |= lowercaseMember(conditions, "block");
                    changed |= lowercaseMember(conditions, "potion");
                }
            }
        }
        if (advancement.get("rewards") instanceof JsonObject rewards) {
            changed |= lowercaseElements(rewards.get("recipes"));
            changed |= lowercaseElements(rewards.get("loot"));
            changed |= lowercaseMember(rewards, "function");
        }
        return changed;
    }

    /** Lowercases the item and table ids in a loot table, and its function and condition names. */
    private static boolean rewriteLootTable(JsonElement json) {
        if (!(json instanceof JsonObject table) || !(table.get("pools") instanceof JsonArray pools)) {
            return false;
        }
        boolean changed = false;
        for (JsonElement pool : pools) {
            if (!(pool instanceof JsonObject body)) continue;
            changed |= lowercaseFunctionsAndConditions(body);
            if (!(body.get("entries") instanceof JsonArray entries)) continue;
            for (JsonElement entry : entries) {
                if (!(entry instanceof JsonObject item)) continue;
                // Only an item or table entry's name is an id. A pool's name, a modifier's name and
                // a 1.12 attribute name are free text the mod may look up by its exact spelling.
                String type = stringOf(item.get("type"));
                if (type != null && type.replaceFirst("^minecraft:", "").matches("item|loot_table")) {
                    changed |= lowercaseMember(item, "name");
                }
                changed |= lowercaseFunctionsAndConditions(item);
            }
        }
        return changed;
    }

    private static boolean lowercaseFunctionsAndConditions(JsonObject owner) {
        boolean changed = false;
        if (owner.get("functions") instanceof JsonArray functions) {
            for (JsonElement function : functions) {
                if (function instanceof JsonObject body) {
                    changed |= lowercaseMember(body, "function");
                    changed |= lowercaseConditions(body);
                }
            }
        }
        return changed | lowercaseConditions(owner);
    }

    private static boolean lowercaseConditions(JsonObject owner) {
        boolean changed = false;
        if (owner.get("conditions") instanceof JsonArray conditions) {
            for (JsonElement condition : conditions) {
                if (condition instanceof JsonObject body) changed |= lowercaseMember(body, "condition");
            }
        }
        return changed;
    }

    /** Lowercases every {@code item} string below {@code json}, skipping NBT, which is free-form data. */
    private static boolean lowercaseItems(JsonElement json) {
        boolean changed = false;
        if (json instanceof JsonObject object) {
            for (String key : List.copyOf(object.keySet())) {
                if (key.equals("nbt")) continue;
                // A criterion such as consume_item holds a whole item predicate under item, so an
                // object there is searched too.
                if (key.equals("item")) changed |= lowercaseMember(object, key);
                changed |= lowercaseItems(object.get(key));
            }
        } else if (json instanceof JsonArray array) {
            for (JsonElement element : array) changed |= lowercaseItems(element);
        }
        return changed;
    }

    /** Lowercases the potion and enchantment ids in a criterion's item predicates. */
    private static boolean lowercasePredicateIds(JsonObject conditions) {
        // The predicates sit under item or items. 1.12 read both ids through its lowercasing
        // identifier, as it did the item.
        List<JsonElement> predicates = new ArrayList<>();
        if (conditions.get("item") instanceof JsonObject predicate) predicates.add(predicate);
        if (conditions.get("items") instanceof JsonArray list) list.forEach(predicates::add);
        boolean changed = false;
        for (JsonElement element : predicates) {
            if (!(element instanceof JsonObject predicate)) continue;
            changed |= lowercaseMember(predicate, "potion");
            if (predicate.get("enchantments") instanceof JsonArray enchantments) {
                for (JsonElement enchantment : enchantments) {
                    if (enchantment instanceof JsonObject body) {
                        changed |= lowercaseMember(body, "enchantment");
                    }
                }
            }
        }
        return changed;
    }

    /** Lowercases texture values, but not {@code #variable} references, whose names are case-sensitive. */
    private static boolean lowercaseTextures(JsonObject textures) {
        boolean changed = false;
        for (String slot : List.copyOf(textures.keySet())) changed |= lowercaseMember(textures, slot);
        return changed;
    }

    private static boolean lowercaseElements(JsonElement json) {
        if (!(json instanceof JsonArray array)) return false;
        boolean changed = false;
        for (int i = 0; i < array.size(); i++) {
            String lowered = lowercaseIdentifier(stringOf(array.get(i)));
            if (lowered != null) {
                array.set(i, new JsonPrimitive(lowered));
                changed = true;
            }
        }
        return changed;
    }

    /** Lowercases one string member when it holds a mixed-case identifier. */
    private static boolean lowercaseMember(JsonObject owner, String member) {
        String lowered = lowercaseIdentifier(stringOf(owner.get(member)));
        if (lowered == null) return false;
        owner.addProperty(member, lowered);
        return true;
    }

    /** The lowercase form of an identifier with uppercase letters, or null when the value is not one. */
    private static String lowercaseIdentifier(String value) {
        // A value with no uppercase, a texture variable, or one still invalid once lowercased,
        // such as one with a space, is left.
        if (value == null || value.startsWith("#") || !UPPERCASE.matcher(value).find()) return null;
        String lowered = value.toLowerCase(Locale.ROOT);
        return IDENTIFIER.matcher(lowered).matches() ? lowered : null;
    }

    /** As {@link #lowercaseIdentifier}, for a bare path such as a sound event name. */
    private static String lowercaseIdentifierPath(String value) {
        String lowered = lowercaseIdentifier(value);
        return lowered != null && IDENTIFIER_PATH.matcher(lowered).matches() ? lowered : null;
    }

    private static String stringOf(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString() : null;
    }

    /** Parses a file the way 1.12 did, leniently, or returns null when it is not JSON at all. */
    private static JsonElement parse(ResourceTree tree, String name) {
        try {
            // A file with no uppercase letter has nothing to lowercase, so it is not parsed.
            byte[] bytes = tree.read(name, MAX_JSON_BYTES);
            if (!UPPERCASE.matcher(new String(bytes, StandardCharsets.ISO_8859_1)).find()) return null;
            JsonSecurity.validate(bytes, MAX_JSON_BYTES, JsonSecurity.DEFAULT_MAX_DEPTH, name);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return JsonParser.parseReader(new JsonReader(new StringReader(text)));
        } catch (IOException | RuntimeException unreadable) {
            return null; // a file the game cannot read either stays as shipped
        }
    }

    /** Copies the mod's mixed-case textures, models, blockstates and sounds to lowercase names. */
    private static int copyMixedCaseFiles(ResourceTree tree, MigrationContext context) throws IOException {
        // 1.12 never loaded such a file through an identifier either, because its lowercased
        // lookup missed it inside a jar. Mod code that reads it by its exact name still can, so
        // the original stays beside the copy. Files are grouped by the name they would take.
        SortedSet<String> names = tree.names();
        Map<String, List<String>> byTarget = new TreeMap<>();
        for (String name : names) {
            String target = lowercaseAssetName(name, context.ownNamespaces());
            if (target != null) byTarget.computeIfAbsent(target, key -> new ArrayList<>()).add(name);
        }
        int copied = 0;
        for (Map.Entry<String, List<String>> copy : byTarget.entrySet()) {
            String target = copy.getKey();
            if (target.endsWith(".png.mcmeta")) continue; // copied with its texture
            if (!canCopy(tree, names, byTarget, target)) continue;
            // A texture travels with its .png.mcmeta, so an animation is never split from its frames.
            String source = copy.getValue().get(0);
            String metadataTarget = target + ".mcmeta";
            List<String> metadata = byTarget.get(metadataTarget);
            boolean animated = names.contains(source + ".mcmeta");
            if (target.endsWith(".png") && animated) {
                if (metadata == null || !canCopy(tree, names, byTarget, metadataTarget)) {
                    LOGGER.debug("Left {} under its name: its animation file cannot follow it", source);
                    continue;
                }
                tree.write(metadataTarget, tree.read(source + ".mcmeta"));
                copied++;
            } else if (target.endsWith(".png") && names.contains(metadataTarget)) {
                LOGGER.debug("Left {} under its name: {} would animate it", source, metadataTarget);
                continue;
            }
            tree.write(target, tree.read(source));
            copied++;
        }
        return copied;
    }

    /** Whether exactly one file takes the lowercase name, and the name is still free. */
    private static boolean canCopy(ResourceTree tree, SortedSet<String> names,
                                   Map<String, List<String>> byTarget, String target) {
        List<String> sources = byTarget.get(target);
        // Two files that lowercase to one name are both left alone.
        if (sources.size() > 1) {
            LOGGER.debug("Left {} under their names: they all lowercase to {}", sources, target);
            return false;
        }
        if (names.contains(target)) return false; // the mod's own lowercase file wins
        if (sharesFileOnDisk(tree, target)) {
            // A case-insensitive file system already resolves the lowercase name to the original,
            // and writing it would only rewrite that file under its mixed-case name.
            LOGGER.debug("Left {} under its name: this file system does not tell the names apart",
                    sources.get(0));
            return false;
        }
        return true;
    }

    /** The lowercase name for a mixed-case asset this converter copies, or null to leave it. */
    private static String lowercaseAssetName(String name, Set<String> ownNamespaces) {
        String[] parts = name.split("/", 4);
        if (parts.length < 4 || !parts[0].equals("assets") || !ownNamespaces.contains(parts[1])) {
            return null;
        }
        List<String> endings = COPIED_ASSET_KINDS.get(parts[2]);
        String relative = parts[3];
        if (endings == null || !UPPERCASE.matcher(relative).find()) return null;
        String lowered = relative.toLowerCase(Locale.ROOT);
        if (endings.stream().noneMatch(lowered::endsWith)) return null;
        if (!IDENTIFIER_PATH.matcher(lowered).matches()) {
            LOGGER.debug("Left {} under its name: it is not a valid identifier even lowercased", name);
            return null;
        }
        return "assets/" + parts[1] + "/" + parts[2] + "/" + lowered;
    }

    /** Whether the name already resolves to a file on disk, as on a case-insensitive file system. */
    private static boolean sharesFileOnDisk(ResourceTree tree, String name) {
        return tree instanceof DirectoryResourceTree directory
                && Files.exists(directory.root().resolve(name), LinkOption.NOFOLLOW_LINKS);
    }
}
