/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Maps Mojang member names to the SRG names a Forge 1.20 to 1.20.4 host runs with. */
public final class TargetSrgMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-TargetSrg");
    private static final String FULL_TABLE = "/retromod/target-srg-1.20.1.tsv";
    private static final Map<String, TargetSrgMapper> CACHE = new ConcurrentHashMap<>();
    private static final TargetSrgMapper EMPTY = new TargetSrgMapper(Map.of(), Map.of());

    private final Map<String, String> methods;
    private final Map<String, String> fields;

    private TargetSrgMapper(Map<String, String> methods, Map<String, String> fields) {
        this.methods = methods;
        this.fields = fields;
    }

    /** Returns the target mapper, or an empty mapper when that host has no bundled table yet. */
    public static TargetSrgMapper forVersion(String targetVersion) {
        if (targetVersion == null || targetVersion.isBlank()) {
            return EMPTY;
        }
        return CACHE.computeIfAbsent(targetVersion, TargetSrgMapper::load);
    }

    /** Registers this target's owner-qualified mappings and returns their total count. */
    public int applyTo(RetromodTransformer transformer) {
        if (methods.isEmpty() && fields.isEmpty()) {
            return 0;
        }
        transformer.registerTargetSrgNameMappings(methods, fields);
        return methods.size() + fields.size();
    }

    public int methodCount() {
        return methods.size();
    }

    public int fieldCount() {
        return fields.size();
    }

    /** Stable string key shared with the hot transform path. */
    public static String memberKey(String owner, String name, String descriptor) {
        // A Mojang name such as get occurs on many owners and overloads, so the owner and
        // descriptor are part of the key and a name is never guessed.
        return owner + '\0' + name + '\0' + descriptor;
    }

    private static TargetSrgMapper load(String version) {
        // Forge 1.20.1 ships as a full table. Forge 1.20 and 1.20.2 to 1.20.4 kept most of its
        // ids, so their tables ship as changes to it. Every other host runs Mojang names.
        String changes = switch (version) {
            case "1.20.1" -> "";
            case "1.20" -> "/retromod/target-srg-1.20.delta.tsv";
            case "1.20.2" -> "/retromod/target-srg-1.20.2.delta.tsv";
            // 1.20.4 kept every id the 1.20.3 table names, so both read one file.
            case "1.20.3", "1.20.4" -> "/retromod/target-srg-1.20.3.delta.tsv";
            default -> null;
        };
        if (changes == null) {
            return EMPTY;
        }

        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        try {
            readRows(FULL_TABLE, false, methods, fields);
            if (!changes.isEmpty()) {
                readRows(changes, true, methods, fields);
            }
        } catch (Exception e) {
            // Without its own changes a host would get 1.20.1's ids, so nothing is registered.
            LOGGER.warn("Could not load target SRG mappings for {}: {}", version, e.toString());
            return EMPTY;
        }

        LOGGER.info("Loaded Forge {} target SRG mappings for {} methods and {} fields",
                version, methods.size(), fields.size());
        return new TargetSrgMapper(Map.copyOf(methods), Map.copyOf(fields));
    }

    /** Reads a full table, or with {@code changes} a file of rows to add, replace or remove. */
    private static void readRows(String resource, boolean changes, Map<String, String> methods,
                                 Map<String, String> fields) throws IOException {
        try (InputStream input = TargetSrgMapper.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Target SRG mapping resource is missing: " + resource);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\t", -1);
                // A change row starts with + to add or replace a row, or - to remove one.
                boolean remove = changes && parts[0].equals("-");
                if (changes) {
                    if (!remove && !parts[0].equals("+")) {
                        continue;
                    }
                    parts = Arrays.copyOfRange(parts, 1, parts.length);
                }
                if (parts.length != (remove ? 4 : 5)) {
                    continue;
                }
                Map<String, String> table = switch (parts[0]) {
                    case "METHOD" -> methods;
                    case "FIELD" -> fields;
                    default -> null;
                };
                if (table == null) {
                    continue;
                }
                String key = memberKey(parts[1], parts[2], parts[3]);
                if (remove) {
                    table.remove(key);
                } else {
                    table.put(key, parts[4]);
                }
            }
        }
    }
}
