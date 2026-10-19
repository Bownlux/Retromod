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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Updates Minecraft's own ids in a mod's data when a later release renamed or removed them. */
final class VanillaIdRenames {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataMigrate");
    // The game upgrades ids in worlds but never in a mod's data, such as minecraft:grass, which
    // became minecraft:short_grass in 1.20.3. One missing entry empties a whole tag, and from 26.3 a
    // recipe or loot table naming a missing item stops the world from loading.
    // scripts/harvest-vanilla-id-renames.py builds this table from the vanilla client jars.
    private static final String RESOURCE = "/retromod/vanilla-id-renames.tsv";
    private static final Pattern ID = Pattern.compile("minecraft:[a-z0-9_]+");

    /** The oldest release the table is harvested from: the Flattening, which renamed most ids. */
    static final String FIRST_TABLE_VERSION = "1.13";

    /** One row of the table. {@code newId} is null for an id that left the game with no successor. */
    record Row(String registry, String oldId, String newId, String version) {
    }

    private static final Map<String, List<Row>> BY_OLD_ID = load();

    /** Tag folders by the registry their entries name, singular and plural. */
    private static final Map<String, String> TAG_REGISTRIES = Map.of(
            "/tags/block/", "block", "/tags/blocks/", "block",
            "/tags/item/", "item", "/tags/items/", "item",
            "/tags/entity_type/", "entity", "/tags/entity_types/", "entity",
            "/tags/enchantment/", "enchantment");

    /** A namespaced vanilla id inside a command, not part of a longer id, path or tag. */
    private static final Pattern FUNCTION_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_:.#/-])(minecraft:[a-z0-9_]+)(?=[\\[{\\s]|$)", Pattern.MULTILINE);

    private VanillaIdRenames() {
    }

    /** Every row, for the tests that check the table itself. */
    static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        BY_OLD_ID.values().forEach(rows::addAll);
        return rows;
    }

    /** The id the host reads for {@code id}, or {@code id} when no rename applies. */
    static String renamed(String id, String sourceMcVersion, String targetMcVersion) {
        List<Row> rows = BY_OLD_ID.get(id);
        if (rows == null) return id;
        String successor = null;
        for (Row row : rows) {
            if (!applies(row, sourceMcVersion, targetMcVersion)) continue;
            // Outside a tag the registry is unknown, so every registry must agree on one successor.
            // An id with no single successor is never guessed at.
            if (row.newId() == null || successor != null && !successor.equals(row.newId())) return id;
            successor = row.newId();
        }
        return successor != null ? successor : id;
    }

    /** Whether the host no longer has {@code id} in {@code registry} and nothing replaced it. */
    static boolean removed(String id, String registry, String sourceMcVersion, String targetMcVersion) {
        Row row = applicable(id, registry, sourceMcVersion, targetMcVersion);
        return row != null && row.newId() == null;
    }

    /** The row that renames or removes {@code id} in {@code registry} for this mod and host, or null. */
    private static Row applicable(String id, String registry, String source, String target) {
        List<Row> rows = BY_OLD_ID.get(id);
        if (rows == null) return null;
        for (Row row : rows) {
            if (row.registry().equals(registry) && applies(row, source, target)) return row;
        }
        return null;
    }

    /** Whether a row applies to data built for {@code source} running on {@code target}. */
    private static boolean applies(Row row, String source, String target) {
        // The rows name ids as 1.13 and later spell them. 1.12 ids mean other things: its
        // minecraft:grass is the grass block, not the plant 1.20.3 renamed to short_grass.
        // Carrying 1.12 ids to their 1.13 names is a separate step.
        if (isVersion(source) && RetromodVersion.compareMcVersions(source, FIRST_TABLE_VERSION) < 0) {
            return false;
        }
        // A rename applies from its release on, for data built before it. Data of unknown age is
        // renamed too, because the old id names nothing on the host.
        return isVersion(target) && RetromodVersion.compareMcVersions(target, row.version()) >= 0
                && !(isVersion(source) && RetromodVersion.compareMcVersions(source, row.version()) >= 0);
    }

    /** Renames ids in one data JSON file; returns the same array when nothing changed. */
    static byte[] migrate(String entryName, byte[] json, String sourceMcVersion, String targetMcVersion) {
        // A quick text scan skips the parse for files that name no old id. A file that does not
        // parse is left for the game to report.
        String text = new String(json, StandardCharsets.UTF_8);
        if (!mentionsOldId(text)) return json;
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException unparsable) {
            return json;
        }
        // Rename every string that is exactly an old id. In a tag of the id's own registry, a
        // removed id then becomes an optional entry so the rest of the tag still loads.
        String tagRegistry = tagRegistry(entryName);
        boolean[] changed = {false};
        JsonElement out = rename(root, sourceMcVersion, targetMcVersion, changed);
        if (tagRegistry != null && out instanceof JsonObject tag && tag.get("values") instanceof JsonArray values) {
            optionalRemovedEntries(values, tagRegistry, sourceMcVersion, targetMcVersion, changed);
        }
        if (!changed[0]) return json;
        LOGGER.debug("Renamed vanilla ids in {}", entryName);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Renames ids named in an mcfunction file's commands; returns the same array when none were. */
    static byte[] migrateFunction(byte[] function, String sourceMcVersion, String targetMcVersion) {
        String text = new String(function, StandardCharsets.UTF_8);
        if (!mentionsOldId(text)) return function;
        Matcher matcher = FUNCTION_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (matcher.find()) {
            String id = matcher.group(1);
            String renamed = renamed(id, sourceMcVersion, targetMcVersion);
            if (!renamed.equals(id)) changed = true;
            matcher.appendReplacement(out, Matcher.quoteReplacement(renamed));
        }
        matcher.appendTail(out);
        return changed ? out.toString().getBytes(StandardCharsets.UTF_8) : function;
    }

    /** Whether the text names any id the table renames or removes. */
    private static boolean mentionsOldId(String text) {
        Matcher ids = ID.matcher(text);
        while (ids.find()) {
            if (BY_OLD_ID.containsKey(ids.group())) return true;
        }
        return false;
    }

    /** Renames old ids through the whole JSON tree, in keys and in string values. */
    private static JsonElement rename(JsonElement element, String source, String target, boolean[] changed) {
        if (element instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) array.set(i, rename(array.get(i), source, target, changed));
            return array;
        }
        if (element instanceof JsonObject object) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> field : object.entrySet()) {
                // A map keyed by id, such as spawn costs or enchantment levels, names ids in its keys.
                String key = renamed(field.getKey(), source, target);
                if (!key.equals(field.getKey())) changed[0] = true;
                out.add(key, rename(field.getValue(), source, target, changed));
            }
            return out;
        }
        // Only a string that is exactly the old id changes, never one that contains it.
        if (element instanceof JsonPrimitive primitive && primitive.isString()) {
            String id = primitive.getAsString();
            String renamed = renamed(id, source, target);
            if (!renamed.equals(id)) {
                changed[0] = true;
                return new JsonPrimitive(renamed);
            }
        }
        return element;
    }

    /** A removed id no longer fails its whole tag: the entry becomes optional. */
    private static void optionalRemovedEntries(JsonArray values, String registry, String source,
                                               String target, boolean[] changed) {
        for (int i = 0; i < values.size(); i++) {
            // An entry is a plain id or an object with an "id" field.
            JsonElement value = values.get(i);
            String id = value instanceof JsonPrimitive primitive && primitive.isString()
                    ? primitive.getAsString()
                    : value instanceof JsonObject entry && entry.get("id") instanceof JsonPrimitive inner
                    && inner.isString() ? inner.getAsString() : null;
            if (id == null || !removed(id, registry, source, target)) continue;
            // An entry already marked optional stays as written.
            if (value instanceof JsonObject entry && entry.get("required") instanceof JsonPrimitive flag
                    && flag.isBoolean() && !flag.getAsBoolean()) {
                continue;
            }
            JsonObject optional = new JsonObject();
            optional.addProperty("id", id);
            optional.addProperty("required", false);
            values.set(i, optional);
            changed[0] = true;
        }
    }

    /** The registry a tag file's entries name, or null when the file is not a known tag. */
    private static String tagRegistry(String entryName) {
        for (Map.Entry<String, String> folder : TAG_REGISTRIES.entrySet()) {
            if (entryName.contains(folder.getKey())) return folder.getValue();
        }
        return null;
    }

    private static boolean isVersion(String version) {
        return version != null && version.matches("\\d+\\.\\d+.*");
    }

    /** Reads the table, grouped by old id. A missing table leaves mod data as written. */
    private static Map<String, List<Row>> load() {
        Map<String, List<Row>> rows = new LinkedHashMap<>();
        try (InputStream in = VanillaIdRenames.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("Vanilla id table {} is missing; renamed vanilla ids in mod data stay as "
                        + "written.", RESOURCE);
                return Map.of();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.isBlank() || line.charAt(0) == '#') continue;
                    // Columns: registry, old id, new id or "-" for removed, release.
                    String[] parts = line.split("\t");
                    if (parts.length != 4 || !ID.matcher(parts[1]).matches()
                            || !(parts[2].equals("-") || ID.matcher(parts[2]).matches())
                            || !isVersion(parts[3])) {
                        throw new IllegalStateException("Malformed " + RESOURCE + " at line " + number);
                    }
                    Row row = new Row(parts[0], parts[1], parts[2].equals("-") ? null : parts[2], parts[3]);
                    // One id may appear in several registries, but only once in each.
                    List<Row> forId = rows.computeIfAbsent(row.oldId(), id -> new ArrayList<>());
                    for (Row other : forId) {
                        if (other.registry().equals(row.registry())) {
                            throw new IllegalStateException("Malformed " + RESOURCE + " at line " + number
                                    + ": " + row.oldId() + " is listed twice for " + row.registry());
                        }
                    }
                    forId.add(row);
                }
            }
        } catch (IOException unreadable) {
            LOGGER.warn("Could not read {} ({}); renamed vanilla ids in mod data stay as written.",
                    RESOURCE, unreadable.toString());
        }
        Map<String, List<Row>> frozen = new LinkedHashMap<>();
        rows.forEach((id, list) -> frozen.put(id, List.copyOf(list)));
        return Collections.unmodifiableMap(frozen);
    }
}
