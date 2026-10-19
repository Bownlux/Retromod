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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Lists the ids Minecraft registers in the registries mod data names most. */
final class VanillaRegistryIds {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataLayout");
    // Items, blocks, entity types, potions, mob effects, enchantments, biomes, structures, recipes,
    // loot tables and predicates. scripts/harvest-vanilla-registry-ids.py reads the rows from the
    // newest client Retromod builds for.
    private static final String RESOURCE = "/retromod/vanilla-registry-ids.tsv";

    private static final Map<String, Set<String>> IDS = load();

    private VanillaRegistryIds() {
    }

    /** Whether the host registers {@code id}, a namespaced {@code minecraft:} id, in {@code registry}. */
    static boolean contains(String registry, String id) {
        // From 26.3 a recipe, loot table or advancement that names a missing id stops the world from
        // loading, so DataLoadGate keeps a copied file out unless every vanilla id it names is here.
        Set<String> ids = IDS.get(registry);
        return ids != null && ids.contains(id);
    }

    /** Reads the bundled table, one registry and id per line. */
    private static Map<String, Set<String>> load() {
        Map<String, Set<String>> ids = new HashMap<>();
        try (InputStream in = VanillaRegistryIds.class.getResourceAsStream(RESOURCE)) {
            // Without the table nothing counts as known, so the gate keeps such files out.
            if (in == null) {
                LOGGER.warn("Vanilla id table {} is missing; copied data that names vanilla ids stays "
                        + "out of 26.3 worlds.", RESOURCE);
                return Map.of();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.isBlank() || line.charAt(0) == '#') continue;
                    String[] parts = line.split("\t");
                    if (parts.length != 2 || !parts[1].matches("minecraft:[a-z0-9_./-]+")) {
                        throw new IllegalStateException("Malformed " + RESOURCE + " at line " + number);
                    }
                    ids.computeIfAbsent(parts[0], registry -> new HashSet<>()).add(parts[1]);
                }
            }
        } catch (IOException unreadable) {
            LOGGER.warn("Could not read {} ({}); copied data that names vanilla ids stays out of 26.3 "
                    + "worlds.", RESOURCE, unreadable.toString());
            return Map.of();
        }
        Map<String, Set<String>> frozen = new HashMap<>();
        ids.forEach((registry, set) -> frozen.put(registry, Set.copyOf(set)));
        return Collections.unmodifiableMap(frozen);
    }
}
