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
import java.util.HashMap;
import java.util.Map;

/** Loads the bundled Forge SRG to Mojang member names and registers them on the transformer. */
public final class SrgToMojangMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-SrgMapper");

    // Forge mods built with reobfJar name Minecraft members by SRG id, such as f_50069_ for STONE.
    // Hosts that run Mojang names (Forge 1.20.6 and newer, NeoForge 1.20.2 and newer, and 26.x)
    // need the ids translated. Rows are FIELD or METHOD, the SRG id, then the Mojang name.
    private static final String RESOURCE_PATH = "/retromod/srg-to-mojang.tsv";

    /** Singleton, lazily loaded on first {@link #getInstance()} call. */
    private static volatile SrgToMojangMapper instance;

    /** Empty fallback used when the data file can't be loaded. */
    private static final SrgToMojangMapper EMPTY =
            new SrgToMojangMapper(Map.of(), Map.of());

    private final Map<String, String> methodMap;
    private final Map<String, String> fieldMap;

    private SrgToMojangMapper(Map<String, String> methodMap, Map<String, String> fieldMap) {
        this.methodMap = methodMap;
        this.fieldMap = fieldMap;
    }

    /**
     * Process-wide singleton. Loads from the bundled TSV on first call;
     * returns an empty mapper if the resource is missing or malformed
     * (a missing data file shouldn't break the transformer entirely).
     */
    public static SrgToMojangMapper getInstance() {
        SrgToMojangMapper local = instance;
        if (local != null) return local;
        synchronized (SrgToMojangMapper.class) {
            if (instance != null) return instance;
            instance = loadFromResource();
            return instance;
        }
    }

    /**
     * Returns the SRG method name → Mojang name dictionary.
     * Keys look like {@code "m_237113_"}; values look like {@code "literal"}.
     */
    public Map<String, String> getMethodMap() {
        return methodMap;
    }

    /**
     * Returns the SRG field name → Mojang name dictionary.
     * Keys look like {@code "f_50069_"}; values look like {@code "STONE"}.
     */
    public Map<String, String> getFieldMap() {
        return fieldMap;
    }

    /**
     * Convenience: register this mapper's data on the given transformer.
     * Returns the total number of entries pushed (method count + field count)
     * so callers can log "applied N SRG mappings" without poking the maps.
     */
    public int applyTo(RetromodTransformer transformer) {
        if (methodMap.isEmpty() && fieldMap.isEmpty()) {
            LOGGER.debug("SRG mapper has no entries; nothing to apply");
            return 0;
        }
        transformer.registerSrgNameMappings(methodMap, fieldMap);
        registerVersionDrift(transformer);
        return methodMap.size() + fieldMap.size();
    }

    private static final String DRIFT_RESOURCE = "/retromod/srg-version-drift.tsv";

    /**
     * Loads the ids whose Mojang name changed across versions (scripts/harvest-srg-drift.py), so a
     * mod resolves each to the name it had in the mod's own version rather than in 1.20.1.
     */
    private static void registerVersionDrift(RetromodTransformer transformer) {
        Map<String, java.util.List<RetromodTransformer.SrgDrift>> ranges = new java.util.LinkedHashMap<>();
        try (InputStream input = SrgToMojangMapper.class.getResourceAsStream(DRIFT_RESOURCE)) {
            if (input == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length != 5) continue;
                ranges.computeIfAbsent(parts[0] + '\t' + parts[1], key -> new java.util.ArrayList<>())
                        .add(new RetromodTransformer.SrgDrift(parts[2], parts[3], parts[4]));
            }
        } catch (IOException e) {
            LOGGER.warn("Could not load SRG version drift from {}: {}", DRIFT_RESOURCE, e.toString());
            return;
        }
        ranges.forEach((key, drift) -> {
            String[] kindAndName = key.split("\t");
            transformer.registerSrgVersionDrift(kindAndName[0], kindAndName[1], drift);
        });
    }

    // Mapping data

    /**
     * The 1.12.2-era SRG dictionary ({@code func_NNN_x}/{@code field_NNN_x} -> readable names,
     * harvested from the MCPBot 1.12.2 CSVs filtered against Mojang's official 1.20.1 names by
     * {@code scripts/harvest-1.12.2-srg-members.py}). Loaded into the SAME maps as the modern
     * {@code m_}/{@code f_} dictionary.
     *
     * <p>The key patterns DO collide, which is why load order matters. 5,458 keys appear in both
     * files, because the main table carries old {@code field_}/{@code func_} spellings as well as
     * the modern ones. The two are not equally trustworthy: the main table is an owner and
     * descriptor disciplined join, while this one keeps whatever readable name MCP used, filtered
     * only by "the name exists somewhere". Where they disagree, this file tends to hold the name
     * from the mod's own era rather than the host's, and that name is exactly what will not
     * resolve: {@code isInvalid} for {@code isRemoved}, {@code entityId} for {@code id},
     * {@code maxStringLength} for {@code maxLength} (all verified against the 26.2 jar).
     */
    private static final String RESOURCE_PATH_1122 = "/retromod/srg-1.12.2-to-mojang.tsv";

    private static SrgToMojangMapper loadFromResource() {
        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        // Weaker table first so the owner-verified one overrides it on every shared key. The
        // 1.12.2 table still supplies the ids the main table does not carry.
        boolean any = loadOne(RESOURCE_PATH_1122, methods, fields);
        any |= loadOne(RESOURCE_PATH, methods, fields);
        if (!any) {
            LOGGER.warn("Could not find SRG to Mojang mapping data at {} or {}. "
                            + "SRG name remapping is unavailable.",
                    RESOURCE_PATH, RESOURCE_PATH_1122);
            return EMPTY;
        }
        LOGGER.info("Loaded SRG to Mojang mappings for {} methods and {} fields",
                methods.size(), fields.size());
        return new SrgToMojangMapper(Map.copyOf(methods), Map.copyOf(fields));
    }

    /** Load one TSV resource into the shared maps; false if the resource is absent/unreadable. */
    private static boolean loadOne(String resource,
            Map<String, String> methods, Map<String, String> fields) {
        try (InputStream in = SrgToMojangMapper.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                int lineNumber = 0;
                int parsed = 0;
                int skipped = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                    String[] parts = trimmed.split("\t");
                    if (parts.length < 3) {
                        skipped++;
                        continue; // Malformed row; skip silently
                    }

                    String kind = parts[0].trim();
                    String srgName = parts[1].trim();
                    String mojangName = parts[2].trim();

                    if (srgName.isEmpty() || mojangName.isEmpty()) {
                        skipped++;
                        continue;
                    }

                    switch (kind) {
                        case "FIELD" -> { fields.put(srgName, mojangName); parsed++; }
                        case "METHOD" -> { methods.put(srgName, mojangName); parsed++; }
                        default -> {
                            // Unknown rows should not prevent valid mappings from loading.
                            LOGGER.debug("Skipping unknown SRG mapping kind '{}' at line {}",
                                    kind, lineNumber);
                            skipped++;
                        }
                    }
                }
                LOGGER.debug("Loaded {}: {} parsed, {} skipped", resource, parsed, skipped);
                return parsed > 0;
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read SRG to Mojang mapping data from {}: {}",
                    resource, e.getMessage());
            return false;
        }
    }
}
