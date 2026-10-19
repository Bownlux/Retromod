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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up what replaced a Forge 1.12 ore dictionary name: an item tag, or a vanilla item. */
final class LegacyOreDictionary {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    // 1.13 replaced the ore dictionary with tags, and the loaders later moved their shared tags from
    // forge: to c:, renaming some paths (forge:string is c:strings). So the table keeps one answer per
    // family, and every tag or item in it is one the loaders or vanilla ship themselves.
    private static final String RESOURCE = "/retromod/forge-ore-dictionary.tsv";
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    /** The material prefixes the ore dictionary shared with the convention tags. */
    private static final Pattern MATERIAL =
            Pattern.compile("(ore|ingot|nugget|gem|dust|block|plate|gear)([A-Z][A-Za-z0-9]*)");
    private static final Map<String, String> MATERIAL_FOLDERS = Map.of(
            "ore", "ores", "ingot", "ingots", "nugget", "nuggets", "gem", "gems", "dust", "dusts",
            "block", "storage_blocks", "plate", "plates", "gear", "gears");

    /** Which shared tags a host reads. */
    enum Family {
        /** Forge's own {@code forge:} tags. */
        FORGE,
        /** The common convention tags, {@code c:}. */
        CONVENTION
    }

    /** What an ore name became: a tag or item id without {@code #}, and if only other mods fill it. */
    record Replacement(String id, boolean tag, boolean guarded) {}

    /** Ore name to {forge answer, convention answer}. A null answer means the family has none. */
    private static final Map<String, Replacement[]> LISTED = new HashMap<>();

    static {
        load();
    }

    private LegacyOreDictionary() {}

    /** What an ore name became in a family, or null when nothing replaced it there. */
    static Replacement replacement(String oreName, Family family) {
        Replacement[] listed = LISTED.get(oreName);
        if (listed != null) return listed[family.ordinal()];
        // A modded material such as ingotTin is not listed and maps to the conventional path for its
        // kind. Only another mod fills that tag, so it is guarded: the recipe gets a condition that
        // skips it while the tag is empty.
        Matcher material = MATERIAL.matcher(oreName);
        if (!material.matches()) return null;
        String path = MATERIAL_FOLDERS.get(material.group(1)) + "/" + snakeCase(material.group(2));
        String id = (family == Family.FORGE ? "forge:" : "c:") + path;
        return ID.matcher(id).matches() ? new Replacement(id, true, true) : null;
    }

    static int listedCount() {
        return LISTED.size();
    }

    /** {@code LiquifiedCoralium} to {@code liquified_coralium}, as the convention tags spell materials. */
    private static String snakeCase(String camel) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            char previous = i > 0 ? camel.charAt(i - 1) : 'A';
            boolean wordStart = Character.isLowerCase(previous) || Character.isDigit(previous);
            if (Character.isUpperCase(c) && wordStart) {
                out.append('_');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /** Reads the bundled table: ore name, then the forge answer, then the convention answer. */
    private static void load() {
        try (InputStream in = LegacyOreDictionary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("Ore dictionary table {} is missing; only modded material names will be "
                        + "converted in 1.12 recipes.", RESOURCE);
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.isBlank() || line.charAt(0) == '#') continue;
                    String[] columns = line.split("\t");
                    if (columns.length != 3) throw malformed(number, "expected three columns");
                    Replacement[] answers = {answer(columns[1], number), answer(columns[2], number)};
                    if (LISTED.put(columns[0], answers) != null) {
                        throw malformed(number, "'" + columns[0] + "' is listed twice");
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read {} ({}). Continuing with {} ore names.", RESOURCE, e.toString(),
                    LISTED.size());
        }
    }

    /** One table answer: a {@code #tag}, an item id, or {@code -} for none. */
    private static Replacement answer(String value, int number) {
        if (value.equals("-")) return null;
        // An item answer is the vanilla item Forge 1.12 registered under the name, used where the
        // family has no tag for it, such as paper.
        boolean tag = value.startsWith("#");
        String id = tag ? value.substring(1) : value;
        if (!ID.matcher(id).matches()) throw malformed(number, "'" + value + "' is not a tag or item id");
        return new Replacement(id, tag, false);
    }

    private static IllegalStateException malformed(int number, String reason) {
        return new IllegalStateException("Malformed " + RESOURCE + " at line " + number + ": " + reason);
    }
}
