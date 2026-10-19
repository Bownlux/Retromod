/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Tells what a 1.12 item or entity id in mod data is called on a newer game. */
final class LegacyItemIds {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    // 1.12 named many items by an id and a data value: minecraft:wool with data 14 is red wool.
    // 1.13 gave each value its own id and renamed others outright, and a few were renamed again
    // later, such as 1.13 rose_red becoming red_dye in 1.14.
    // scripts/harvest-legacy-item-flattening.py reads this table from the 1.13 client's own data
    // fixers. The later renames were checked against the client jars on both sides of each change,
    // so a reference lands on the name its target actually reads.
    private static final String RESOURCE = "/retromod/legacy-item-flattening.tsv";
    private static final String FLATTENING = "1.13";
    private static final Pattern ID = Pattern.compile("minecraft:[a-z0-9_]+");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");

    /** A rename one release made, read only on hosts at that release or newer. */
    private record Rename(String to, String since) {}

    /** 1.12 id to data value to 1.13 id, in data order. */
    private static final Map<String, Map<Integer, String>> FLATTENED = new LinkedHashMap<>();
    /** 1.12 item ids 1.13 renamed whatever their data value. */
    private static final Map<String, String> RENAMED = new LinkedHashMap<>();
    private static final Map<String, String> RENAMED_ENTITIES = new LinkedHashMap<>();
    private static final Map<String, Rename> LATER_ITEMS = new LinkedHashMap<>();
    private static final Map<String, Rename> LATER_ENTITIES = new LinkedHashMap<>();
    /** 1.12 ids whose every data value a vanilla tag covers. */
    private static final Map<String, String> FAMILY_TAGS = new LinkedHashMap<>();
    /** By 1.12 id, then data value: the other 1.13 items accepted where 1.13 split one item in two. */
    private static final Map<String, Map<Integer, List<String>>> ALSO_ACCEPTED = new LinkedHashMap<>();

    // The table ships with Retromod. Computing it from the host's own data fixer at runtime would
    // load Minecraft classes while mods are still being constructed.
    static {
        load();
    }

    private LegacyItemIds() {}

    /** Whether 1.13 split this 1.12 id by data value, so the value decides the new item. */
    static boolean isFlattened(String id) {
        return FLATTENED.containsKey(id);
    }

    /** The item one data value of a flattened 1.12 id became on the target, or null if it got none. */
    static String flattened(String id, int data, MigrationContext context) {
        Map<Integer, String> values = FLATTENED.get(id);
        String flattened = values == null ? null : values.get(data);
        return flattened == null ? null : later(LATER_ITEMS, flattened, context);
    }

    /** The item a 1.12 id that 1.13 did not split by data value is called on the target. */
    static String renamed(String id, MigrationContext context) {
        return later(LATER_ITEMS, RENAMED.getOrDefault(id, id), context);
    }

    /** Every item a flattened 1.12 id's data values became on the target, in data order. */
    static List<String> family(String id, MigrationContext context) {
        Map<Integer, String> values = FLATTENED.get(id);
        if (values == null) return List.of();
        // Each value's item, plus any item 1.13 split from it, with duplicates dropped.
        Set<String> members = new LinkedHashSet<>();
        for (Map.Entry<Integer, String> value : values.entrySet()) {
            members.add(later(LATER_ITEMS, value.getValue(), context));
            members.addAll(alsoAccepted(id, value.getKey(), context));
        }
        return new ArrayList<>(members);
    }

    /** The other items a recipe ingredient or predicate naming this 1.12 id and data value accepts. */
    static List<String> alsoAccepted(String id, int data, MigrationContext context) {
        // 1.13 kept minecraft:pumpkin for a new uncarved block and named the 1.12 one
        // carved_pumpkin. 1.13's own recipes took either, so a 1.12 one takes both.
        List<String> also = ALSO_ACCEPTED.getOrDefault(id, Map.of()).getOrDefault(data, List.of());
        List<String> onTarget = new ArrayList<>();
        for (String item : also) onTarget.add(later(LATER_ITEMS, item, context));
        return onTarget;
    }

    /** The vanilla tag that covers every data value of a 1.12 id, without its {@code #}, or null. */
    static String familyTag(String id) {
        return FAMILY_TAGS.get(id);
    }

    /** The entity a 1.12 entity id is called on the target. */
    static String entity(String id, MigrationContext context) {
        return later(LATER_ENTITIES, RENAMED_ENTITIES.getOrDefault(id, id), context);
    }

    /** Follows the renames after 1.13 that the target has, starting from a 1.13 id. */
    private static String later(Map<String, Rename> renames, String id, MigrationContext context) {
        // These only ever apply to what the 1.13 step produced. 1.12 minecraft:grass is the grass
        // block and becomes grass_block, so the 1.20.3 rename of 1.13's grass plant to short_grass
        // never reaches it.
        String current = id;
        Rename rename;
        while ((rename = renames.get(current)) != null && context.targetAtLeast(rename.since())) {
            current = rename.to();
        }
        return current;
    }

    /** The rows of the table, for the test that checks it. */
    static Map<String, Integer> rowCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        counts.put("flattened", FLATTENED.values().stream().mapToInt(Map::size).sum());
        counts.put("renamed", RENAMED.size());
        counts.put("entities", RENAMED_ENTITIES.size());
        counts.put("later items", LATER_ITEMS.size());
        counts.put("later entities", LATER_ENTITIES.size());
        counts.put("families", FAMILY_TAGS.size());
        counts.put("also accepted", ALSO_ACCEPTED.values().stream()
                .flatMap(values -> values.values().stream()).mapToInt(List::size).sum());
        return Collections.unmodifiableMap(counts);
    }

    /** Reads the table. A missing table leaves 1.12 ids as written. */
    private static void load() {
        try (InputStream in = LegacyItemIds.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("Item id table {} is missing; 1.12 recipes and loot tables keep their "
                        + "old item ids.", RESOURCE);
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.isBlank() || line.charAt(0) == '#') continue;
                    addRow(line.split("\t"), number);
                }
            }
        } catch (IOException e) {
            // Converting what parsed beats converting nothing; the load gate keeps the rest out.
            LOGGER.warn("Could not read {} ({}). Continuing with the rows read so far.", RESOURCE,
                    e.toString());
        }
    }

    /** Files one row under its kind. Rows dated 1.13 are the Flattening, the rest are later renames. */
    private static void addRow(String[] columns, int number) {
        // Columns: kind, 1.12 id, data value, new id, release.
        if (columns.length != 5) throw malformed(number, "expected five columns");
        String kind = columns[0];
        String from = requireId(columns[1], number);
        String data = columns[2];
        String to = columns[3];
        String since = columns[4];
        if (!VERSION.matcher(since).matches()) throw malformed(number, "'" + since + "' is not a version");
        boolean flattening = since.equals(FLATTENING);
        switch (kind) {
            // An item row with a data value is a split. Without one it is a plain rename.
            case "item" -> {
                requireId(to, number);
                if (!data.equals("-")) {
                    if (!flattening) throw malformed(number, "only 1.13 split ids by data value");
                    int value = parseData(data, number);
                    Map<Integer, String> values = FLATTENED.computeIfAbsent(from, k -> new LinkedHashMap<>());
                    if (values.put(value, to) != null) throw duplicate(number, from + " " + data);
                } else if (flattening) {
                    if (RENAMED.put(from, to) != null) throw duplicate(number, from);
                } else if (LATER_ITEMS.put(from, new Rename(to, since)) != null) {
                    throw duplicate(number, from);
                }
            }
            case "entity" -> {
                requireId(to, number);
                boolean listedTwice = flattening ? RENAMED_ENTITIES.put(from, to) != null
                        : LATER_ENTITIES.put(from, new Rename(to, since)) != null;
                if (listedTwice) throw duplicate(number, from);
            }
            // An extra item one 1.12 id and data value also accepts.
            case "also" -> {
                requireId(to, number);
                if (!flattening) throw malformed(number, "only 1.13 split one item in two");
                List<String> also = ALSO_ACCEPTED.computeIfAbsent(from, k -> new LinkedHashMap<>())
                        .computeIfAbsent(parseData(data, number), k -> new ArrayList<>());
                if (also.contains(to)) throw duplicate(number, from + " " + data + " " + to);
                also.add(to);
            }
            // A vanilla tag that covers every data value of a 1.12 id.
            case "family" -> {
                if (!data.equals("*") || !to.startsWith("#")) {
                    throw malformed(number, "a family row maps every value (*) to a #tag");
                }
                if (FAMILY_TAGS.put(from, requireId(to.substring(1), number)) != null) {
                    throw duplicate(number, from);
                }
            }
            default -> throw malformed(number, "unknown kind '" + kind + "'");
        }
    }

    private static int parseData(String value, int number) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            throw malformed(number, "'" + value + "' is not a data value");
        }
    }

    private static String requireId(String value, int number) {
        if (!ID.matcher(value).matches()) throw malformed(number, "'" + value + "' is not a vanilla id");
        return value;
    }

    private static IllegalStateException duplicate(int number, String key) {
        return malformed(number, key + " is listed twice");
    }

    private static IllegalStateException malformed(int number, String reason) {
        return new IllegalStateException("Malformed " + RESOURCE + " at line " + number + ": " + reason);
    }
}
