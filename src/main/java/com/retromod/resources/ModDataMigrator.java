/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.core.RetromodVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Migrates a mod's bundled data-pack JSON across the 1.21.x to 26.x data changes the
 * bytecode pass can't reach (loot tables, advancements, recipes, tags, worldgen).
 *
 * <p>The mod-jar transform rewrites bytecode and loader metadata but not the {@code data/}
 * JSON a mod ships, and several 26.x changes are data-only.
 *
 * <p>Main fix: 26.1 parses data-pack JSON with a strict Gson reader, rejecting the {@code //}
 * / {@code /* *}{@code /} comments and trailing commas common in hand-authored worldgen files
 * (Jigsaw template_pools, processor_lists). Each such file throws
 * {@code MalformedJsonException} and stays unbound, and one unbound template_pool is fatal
 * (FatalStartupException) and aborts the shared worldgen registry load, so co-loaded structure
 * mods then look like they never registered their own types. {@link #normalizeLenientJson}
 * strips the offending tokens, but only for files that contain one.
 *
 * <p>Targeted rewrites, each gated to 26.x targets ({@link RetromodVersion#isUnobfuscatedTarget}):
 * <ul>
 *   <li>{@code minecraft:chain} to {@code minecraft:iron_chain}: 26.x split the chain item
 *       into iron/copper variants. Quote-bounded so it can't touch
 *       {@code minecraft:chain_command_block} or a {@code <modid>:chain}.</li>
 *   <li>{@code dyed_color} object to int: 26.x wants the colour int (or an {@code [r,g,b]}
 *       array); 1.21.x shipped an object {@code {"rgb":N,...}}.</li>
 *   <li>advancement icon {@code "item"} to {@code "id"}: 26.x renamed the icon ItemStack key.</li>
 *   <li>{@code custom_model_data} int to object: 26.x (1.21.4+) expanded the component from a
 *       bare int to {@code {"floats":[...],...}}; the legacy int maps to the first float.</li>
 *   <li>{@code minecraft:potion} entity-type split: 26.x split the thrown-potion entity into
 *       {@code minecraft:splash_potion} and {@code minecraft:lingering_potion} (vanilla's
 *       {@code ThrownPotionSplitFix}). Scoped to {@code tags/entity_type/} files only, since
 *       the potion item is unchanged.</li>
 * </ul>
 */
public final class ModDataMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataMigrate");

    private static final String CHAIN_OLD = "\"minecraft:chain\"";
    private static final String CHAIN_NEW = "\"minecraft:iron_chain\"";

    // No nested braces inside a dyed_color object, so [^}] stays within the one object.
    private static final Pattern DYED_COLOR = Pattern.compile(
            "\"minecraft:dyed_color\"\\s*:\\s*\\{[^}]*?\"rgb\"\\s*:\\s*(-?\\d+)[^}]*?\\}");

    // The icon object's item id is its leading key, so anchor on the icon brace.
    private static final Pattern ICON_ITEM = Pattern.compile(
            "(\"icon\"\\s*:\\s*\\{\\s*)\"item\"(\\s*:)");

    // Bare-int custom_model_data. The negative lookahead skips a value that's already a float
    // or a longer token.
    private static final Pattern CMD_INT = Pattern.compile(
            "\"minecraft:custom_model_data\"\\s*:\\s*(-?\\d+)(?![\\d.])");

    // A bare-string "minecraft:potion" array element inside an entity_type tag. Anchored on a
    // leading [ or , and a trailing , or ] so it can't touch an object's "id":"minecraft:potion";
    // the preceding ':' never matches [\[,]. The trailing delimiter stays in the lookahead.
    private static final Pattern POTION_ENTITY_SPLIT = Pattern.compile(
            "([\\[,]\\s*)\"minecraft:potion\"(?=\\s*[,\\]])");

    // Lenient-only features 26.1's strict gson rejects. A `//` inside a string value only
    // triggers the string-aware strip, never a false negative.
    private static final Pattern LENIENT_JSON_HINT = Pattern.compile("//|/\\*|,\\s*[}\\]]");

    private ModDataMigrator() {}

    /**
     * True for data entries this migrator can act on (cheap pre-filter). Covers all datapack
     * JSON, since the strict-JSON comment fix applies to any data file.
     */
    public static boolean isMigratableData(String entryName) {
        return entryName.endsWith(".json")
                && (entryName.startsWith("data/")         // datapack JSON (worldgen, loot, tags, ...)
                 || entryName.contains("/data/")          // same, with a path prefix
                 || entryName.contains("/loot_table")     // fallback for layouts that don't sit
                 || entryName.contains("/advancement")    // under data/
                 || entryName.contains("/recipe")
                 || entryName.contains("/tags/"));
    }

    /**
     * Apply the 26.x data rewrites to one JSON entry. Returns the input bytes unchanged
     * if the target is not 26.x, the entry is not migratable data, or nothing matched.
     */
    public static byte[] migrate(String entryName, byte[] json, String targetMcVersion) {
        if (!RetromodVersion.isUnobfuscatedTarget(targetMcVersion)) return json;
        if (!isMigratableData(entryName)) return json;

        // Make the file parse under 26.1's strict gson (strip // /* */ comments and trailing
        // commas). A clean file comes back as the same array, so the rewrites below still see
        // the original bytes.
        byte[] normalized = normalizeLenientJson(json);

        // 26.x turned EntityPredicate into a registry-keyed map: the 1.21.x field names
        // ("type", "flags", ...) are now sub-predicate REGISTRY IDS ("minecraft:entity_type",
        // "minecraft:flags", ...), so an old predicate fails with "Unknown registry key in
        // entity_sub_predicate_type: minecraft:type" (#114, Apollo's Enchantment Rebalance:
        // every enchantment referencing an entity_properties condition failed to parse).
        // Structure-aware (Gson), so nothing outside a predicate object is touched.
        boolean advancementLike = entryName.contains("/advancement") || entryName.contains("/predicates/");
        String probe = new String(normalized, StandardCharsets.UTF_8);
        if (probe.contains("entity_properties") || (advancementLike && probe.contains("\"type\""))) {
            normalized = migrateEntityPredicates(normalized, advancementLike);
        }

        String in = new String(normalized, StandardCharsets.UTF_8);
        String out = in.replace(CHAIN_OLD, CHAIN_NEW);
        out = DYED_COLOR.matcher(out).replaceAll("\"minecraft:dyed_color\":$1");
        out = CMD_INT.matcher(out).replaceAll("\"minecraft:custom_model_data\":{\"floats\":[$1]}");
        if (entryName.contains("/advancement")) {
            out = ICON_ITEM.matcher(out).replaceAll("$1\"id\"$2");
        }
        if (entryName.contains("/tags/entity_type/")) {
            out = POTION_ENTITY_SPLIT.matcher(out).replaceAll(
                    "$1\"minecraft:splash_potion\", \"minecraft:lingering_potion\"");
        }

        // No rewrite fired: return normalized, which is `json` when the file was already
        // strict-clean, or the comment-stripped bytes otherwise.
        if (out.equals(in)) return normalized;
        LOGGER.debug("Migrated 26.x data formats in {}", entryName);
        return out.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Make a data-pack JSON parse under 26.1's strict Gson by removing the two lenient-only
     * features old mods rely on: {@code //} / {@code /* *}{@code /} comments and trailing commas.
     * A string-aware single pass, not a reserialize: a {@code //} or {@code ,} inside a string
     * value is copied verbatim and the file's formatting is otherwise preserved. Hand-rolled
     * because Gson's own lenient reader rejects trailing commas.
     *
     * <p>Other lenient quirks (single quotes, unquoted keys, NaN) are left for the loader to
     * report. Returns the input array (same reference) when there's nothing to strip, so callers
     * can detect a change by reference identity.
     */
    static byte[] normalizeLenientJson(byte[] json) {
        String in = new String(json, StandardCharsets.UTF_8);
        if (!LENIENT_JSON_HINT.matcher(in).find()) return json;   // already strict; cheap exit
        String out = stripLenientJson(in);
        if (out.equals(in)) return json;
        LOGGER.debug("Normalized lenient JSON (stripped comments / trailing commas)");
        return out.getBytes(StandardCharsets.UTF_8);
    }

    /** String-aware strip of // and block comments plus trailing commas. See {@link #normalizeLenientJson}. */
    private static String stripLenientJson(String s) {
        int n = s.length();
        StringBuilder out = new StringBuilder(n);
        boolean inStr = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(s.charAt(++i));   // escaped char (incl. \" and \\) copied verbatim
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') { inStr = true; out.append(c); continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {       // line comment
                i += 2;
                while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
                i--;                              // leave the newline for the loop to append
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {       // block comment
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i++;                              // skip the closing '/'
                continue;
            }
            if (c == ',' && trailingComma(s, i + 1)) continue;            // drop a dangling comma
            out.append(c);
        }
        return out.toString();
    }

    /** True if the next non-whitespace, non-comment char from {@code i} is a closing }} or ]. */
    private static boolean trailingComma(String s, int i) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; }
            else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                return c == '}' || c == ']';
            }
        }
        return false;
    }

    /**
     * Apply {@link #migrate} to every data file in an already-extracted mod tree, rewriting
     * changed files in place. Used by the runtime transformers (Fabric/Forge) and JiJ handlers,
     * which extract a mod before repackaging; the byte-stream {@link #migrate} fits the offline
     * CLI's entry-by-entry copy instead.
     *
     * <p>Returns 0 for non-26.x targets or a missing/empty root. Entry names are normalized to
     * forward slashes so {@link #isMigratableData} path checks match on every OS.
     *
     * @return the number of files rewritten.
     */
    public static int migrateTree(Path root, String targetMcVersion) {
        // not gated on 26.x as a whole: the client item-definition split below applies from
        // 1.21.4 (review finding - a 1.21.1 -> 1.21.11 translation also needs the definitions).
        if (root == null || !Files.isDirectory(root)) return 0;
        boolean is26x = RetromodVersion.isUnobfuscatedTarget(targetMcVersion);

        int changed = 0;
        final List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            LOGGER.debug("Could not walk {} for data migration: {}", root, e.getMessage());
            return 0;
        }

        java.util.Set<String> relNames = new java.util.HashSet<>();
        for (Path p : files) {
            String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
            relNames.add(rel);
            if (!is26x) continue;                 // the data rewrites below are 26.x-only
            if (!isMigratableData(rel)) continue;
            try {
                byte[] before = Files.readAllBytes(p);
                byte[] after = migrate(rel, before, targetMcVersion);
                if (after != before) {        // same array back means nothing matched
                    Files.write(p, after);
                    changed++;
                }
            } catch (IOException e) {
                LOGGER.debug("Could not migrate data file {}: {}", rel, e.getMessage());
            }
        }

        // 1.21.4+ client item definitions: a pre-1.21.4 mod ships only models/item/*.json, and
        // without an assets/<ns>/items/<id>.json per item everything renders as the purple/black
        // missing model ("Missing item model for location <ns>:<id>").
        for (var def : synthesizeItemDefinitionEntries(relNames, targetMcVersion).entrySet()) {
            try {
                Path out = root.resolve(def.getKey());
                Files.createDirectories(out.getParent());
                Files.write(out, def.getValue());
                changed++;
            } catch (IOException e) {
                LOGGER.debug("Could not write item definition {}: {}", def.getKey(), e.getMessage());
            }
        }
        return changed;
    }

    // 1.21.x EntityPredicate field name -> 26.x sub-predicate registry id. Keys not in this map
    // (already-namespaced 26.x keys, unknown fields) pass through untouched. Verified against the
    // full 26.2 vanilla datapack vocabulary (3120 files scanned).
    private static final java.util.Map<String, String> ENTITY_PREDICATE_KEYS = java.util.Map.of(
            "type", "minecraft:entity_type",
            "flags", "minecraft:flags",
            "location", "minecraft:location",
            "movement", "minecraft:movement",
            "movement_affected_by", "minecraft:movement_affected_by",
            "periodic_tick", "minecraft:periodic_tick",
            "vehicle", "minecraft:vehicle",
            "equipment", "minecraft:equipment",
            "distance", "minecraft:distance",
            "stepping_on", "minecraft:stepping_on");

    /**
     * Rewrite 1.21.x {@code entity_properties} predicates to the 26.x registry-keyed form.
     * Returns the input array unchanged when nothing matched or the JSON doesn't parse.
     */
    static byte[] migrateEntityPredicates(byte[] json) {
        return migrateEntityPredicates(json, false);
    }

    static byte[] migrateEntityPredicates(byte[] json, boolean advancementMode) {
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser
                    .parseString(new String(json, StandardCharsets.UTF_8));
            boolean[] changed = {false};
            rewriteEntityPredicates(root, null, changed, advancementMode);
            if (!changed[0]) return json;
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return json;   // unparseable or unexpected shape: leave the file alone
        }
    }

    // Advancement criteria embed EntityPredicates BARE under these keys (no entity_properties
    // wrapper): "victims": [{"type": "#aer:fish"}] etc. Subset-guarded: an object under one of
    // these slots is only rewritten when its keys all belong to the EntityPredicate vocabulary,
    // so an unrelated {"type": ...} object (an effect, a provider) can never be touched.
    private static final java.util.Set<String> ENTITY_PREDICATE_SLOTS = java.util.Set.of(
            "victims", "entity", "projectile", "direct_entity", "source_entity",
            "shooter", "cause", "parent", "partner", "child", "zombie", "villager");

    private static final java.util.Set<String> ENTITY_PREDICATE_VOCAB;
    static {
        java.util.Set<String> v = new java.util.HashSet<>(ENTITY_PREDICATE_KEYS.keySet());
        v.addAll(ENTITY_PREDICATE_KEYS.values());
        v.addAll(java.util.Set.of("nbt", "team", "effects", "slots", "components",
                "targeted_entity", "passenger", "type_specific"));
        ENTITY_PREDICATE_VOCAB = v;
    }

    private static void rewriteEntityPredicates(com.google.gson.JsonElement el,
            String parentCondition, boolean[] changed, boolean advancementMode) {
        if (el.isJsonArray()) {
            for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                rewriteEntityPredicates(child, parentCondition, changed, advancementMode);
            }
            return;
        }
        if (!el.isJsonObject()) return;
        com.google.gson.JsonObject obj = el.getAsJsonObject();
        String condition = obj.has("condition") && obj.get("condition").isJsonPrimitive()
                ? obj.get("condition").getAsString() : parentCondition;
        if ("minecraft:entity_properties".equals(condition)
                && obj.has("predicate") && obj.get("predicate").isJsonObject()) {
            obj.add("predicate", rewritePredicateKeys(obj.getAsJsonObject("predicate"), changed));
        }
        if (advancementMode) {
            for (String slot : ENTITY_PREDICATE_SLOTS) {
                if (!obj.has(slot)) continue;
                com.google.gson.JsonElement v = obj.get(slot);
                if (v.isJsonObject() && looksLikeEntityPredicate(v.getAsJsonObject())) {
                    obj.add(slot, rewritePredicateKeys(v.getAsJsonObject(), changed));
                } else if (v.isJsonArray()) {
                    com.google.gson.JsonArray arr = v.getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        com.google.gson.JsonElement e = arr.get(i);
                        if (e.isJsonObject() && looksLikeEntityPredicate(e.getAsJsonObject())) {
                            arr.set(i, rewritePredicateKeys(e.getAsJsonObject(), changed));
                        }
                    }
                }
            }
        }
        for (String key : new java.util.ArrayList<>(obj.keySet())) {
            rewriteEntityPredicates(obj.get(key), condition, changed, advancementMode);
        }
    }

    /** At least one legacy key present, and every key within the EntityPredicate vocabulary. */
    private static boolean looksLikeEntityPredicate(com.google.gson.JsonObject obj) {
        boolean anyLegacy = false;
        for (String k : obj.keySet()) {
            if (ENTITY_PREDICATE_KEYS.containsKey(k)) anyLegacy = true;
            else if (!ENTITY_PREDICATE_VOCAB.contains(k)) return false;
        }
        return anyLegacy;
    }

    private static com.google.gson.JsonObject rewritePredicateKeys(
            com.google.gson.JsonObject predicate, boolean[] changed) {
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        for (String key : predicate.keySet()) {
            String mapped = ENTITY_PREDICATE_KEYS.getOrDefault(key, key);
            com.google.gson.JsonElement value = predicate.get(key);
            // vehicle/targeted_entity values are nested EntityPredicates - migrate them too
            if (value.isJsonObject()
                    && ("vehicle".equals(key) || "targeted_entity".equals(key)
                        || "passenger".equals(key))) {
                value = rewritePredicateKeys(value.getAsJsonObject(), changed);
            }
            if (!mapped.equals(key)) changed[0] = true;
            out.add(mapped, value);
        }
        return out;
    }

    /**
     * 1.21.4 split "client item" definitions out of item models: every item id needs an
     * {@code assets/<ns>/items/<id>.json} naming its model, and items without one render as the
     * missing model. Pre-1.21.4 mods only ship {@code assets/<ns>/models/item/<id>.json}, so all
     * their items are invisible on 26.x (verified on Macaw's: 146 item models, 0 definitions,
     * every item purple/black in the creative tab).
     *
     * <p>Given a mod's entry names, returns the definitions to add: one per direct child of
     * {@code models/item/} (item ids cannot contain {@code /}, so nested model files are parents or
     * templates, never item models) whose {@code items/<id>.json} is absent. A definition for a
     * model that isn't actually an item is inert (never queried), so over-synthesis is harmless;
     * ids with characters an Identifier rejects are skipped.
     */
    public static java.util.Map<String, byte[]> synthesizeItemDefinitionEntries(
            java.util.Set<String> entryNames, String targetMcVersion) {
        // client item definitions exist from 1.21.4, not just 26.x
        if (RetromodVersion.mcVersionExceeds("1.21.4", targetMcVersion)) return java.util.Map.of();
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        for (String name : entryNames) {
            if (!name.startsWith("assets/") || !name.endsWith(".json")) continue;
            int m = name.indexOf("/models/item/");
            if (m < 0) continue;
            String ns = name.substring("assets/".length(), m);
            if (ns.isEmpty() || ns.indexOf('/') >= 0) continue;   // exactly assets/<ns>/models/item/
            String file = name.substring(m + "/models/item/".length());
            if (file.indexOf('/') >= 0) continue;                 // nested = not an item id
            String id = file.substring(0, file.length() - ".json".length());
            if (!id.matches("[a-z0-9_.\\-]+")) continue;          // must be a valid Identifier path
            String defName = "assets/" + ns + "/items/" + id + ".json";
            if (entryNames.contains(defName)) continue;           // the mod already ships one
            String json = "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \""
                    + ns + ":item/" + id + "\"\n  }\n}\n";
            out.put(defName, json.getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }
}
