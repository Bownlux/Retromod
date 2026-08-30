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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The block and item texture basenames the 1.13 Flattening changed.
 *
 * <p>These were kept as a short hand-written list, which meant a converted pack came out mostly
 * right and quietly missing whatever the list had not reached yet. The table is now derived from the
 * 1.12.2, 1.13, and 26.2 client jars: identical PNG content proves a pair across the 1.13 boundary,
 * the texture a vanilla model names covers the art that also changed, and every row is checked
 * against 26.2 so a pack lands on a texture the game still ships.
 *
 * <p>Only the basename is stored. The {@code blocks/} to {@code block/} directory move is a separate
 * step, and block and item names are kept apart because the same basename can mean different things
 * in each: a 1.12.2 pack has both {@code blocks/brick.png} and {@code items/brick.png}, and only the
 * block one became {@code bricks}.
 */
final class FlatteningTextureRenames {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    private static final String RESOURCE = "/retromod/flattening-texture-renames.tsv";
    /** A basename only: no directory, no extension, and nothing that could escape one. */
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_]+");

    private static final Map<String, String> BLOCKS;
    private static final Map<String, String> ITEMS;

    static {
        Map<String, String> blocks = new LinkedHashMap<>();
        Map<String, String> items = new LinkedHashMap<>();
        load(blocks, items);
        BLOCKS = Map.copyOf(blocks);
        ITEMS = Map.copyOf(items);
    }

    private FlatteningTextureRenames() {}

    /** Block texture basenames, old to new. */
    static Map<String, String> blocks() {
        return BLOCKS;
    }

    /** Item texture basenames, old to new. */
    static Map<String, String> items() {
        return ITEMS;
    }

    private static void load(Map<String, String> blocks, Map<String, String> items) {
        try (InputStream in = FlatteningTextureRenames.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("Flattening texture table {} is missing; pre-1.13 packs will keep "
                        + "their original texture names.", RESOURCE);
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.isBlank() || line.charAt(0) == '#') continue;
                    String[] parts = line.split("\t");
                    if (parts.length != 3) throw malformed(number, "expected three columns");
                    String kind = parts[0];
                    String from = requireName(parts[1], number);
                    String to = requireName(parts[2], number);
                    if (from.equals(to)) throw malformed(number, "a row that renames nothing");
                    Map<String, String> target = switch (kind) {
                        case "block" -> blocks;
                        case "item" -> items;
                        default -> throw malformed(number, "unknown kind '" + kind + "'");
                    };
                    if (target.put(from, to) != null) {
                        throw malformed(number, "'" + from + "' is listed twice for " + kind);
                    }
                }
            }
        } catch (IOException e) {
            // A pack conversion is still better than none, so keep whatever parsed.
            LOGGER.warn("Could not read {} ({}). Continuing with {} block and {} item renames.",
                    RESOURCE, e.toString(), blocks.size(), items.size());
        }
    }

    private static String requireName(String value, int lineNumber) {
        if (!SAFE_NAME.matcher(value).matches()) {
            throw malformed(lineNumber, "'" + value + "' is not a bare texture name");
        }
        return value;
    }

    private static IllegalStateException malformed(int lineNumber, String reason) {
        return new IllegalStateException(
                "Malformed " + RESOURCE + " at line " + lineNumber + ": " + reason);
    }
}
