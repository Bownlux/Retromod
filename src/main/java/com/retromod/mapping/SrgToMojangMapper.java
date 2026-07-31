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

/**
 * Loads Forge SRG → Mojang member-name mappings from a bundled TSV resource
 * and applies them to a {@link RetromodTransformer}.
 *
 * <h3>Why this exists</h3>
 * <p>Forge mods built before MC ~1.20.5 (and any build still running
 * ForgeGradle's {@code reobfJar} task) reference MC members by Forge's SRG
 * names: fields like {@code Blocks.f_50069_} (= STONE) and methods like
 * {@code Component.m_237113_(String)} (= literal). Forge's runtime
 * classloader used to remap these to the actual MC names at class load
 * time. <strong>Forge 64.x for MC 26.1+ dropped the SRG remap layer</strong>
 * because MC 26.1 has no obfuscation at all (Mojang shipped official
 * names directly). Reobf'd mods then break with {@code NoSuchFieldError} /
 * {@code NoSuchMethodError} on every SRG name.
 *
 * <p>This mapper takes over that responsibility: it loads the bundled
 * SRG → Mojang dictionary and registers the mappings on Retromod's
 * transformer, which then applies them via the same {@code ClassRemapper}
 * pipeline that handles intermediary→Mojang.
 *
 * <h3>Data file</h3>
 * <p>Loaded from {@code /retromod/srg-to-mojang.tsv}. Tab-separated,
 * three columns:
 *
 * <pre>
 *   KIND    SRG_NAME    MOJANG_NAME
 *   FIELD   f_50069_    STONE
 *   METHOD  m_237113_   literal
 * </pre>
 *
 * <p>Lines starting with {@code #} are comments. Blank lines are ignored.
 * KIND is one of {@code FIELD} or {@code METHOD}.
 *
 * <p>The bundled data is a <strong>starter set</strong>: the highest-value
 * symbols (common Block/Item statics, Component factory methods,
 * ResourceLocation helpers) that the test mod and common Forge mods like
 * Jade and JEI rely on. The full SRG name space is in the tens of
 * thousands; complete coverage requires generating from Forge's official
 * mapping artifacts. See the data file's header for expansion notes.
 *
 * <h3>Loader scope</h3>
 * <p>SRG remap is primarily relevant on Forge runtimes for old reobf'd
 * Forge mods. NeoForge dropped SRG natively but cross-loader scenarios
 * (running a Forge SRG-baked mod on NeoForge) benefit too. Fabric never
 * used SRG. The mapper is loader-agnostic at the transformer level: it
 * activates whenever input bytecode has SRG-pattern names, regardless of
 * which loader registered it.
 */
public final class SrgToMojangMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-SrgMapper");

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
        return methodMap.size() + fieldMap.size();
    }

    // Mapping data

    /**
     * The 1.12.2-era SRG dictionary ({@code func_NNN_x}/{@code field_NNN_x} -> readable names,
     * harvested from the MCPBot 1.12.2 CSVs filtered against Mojang's official 1.20.1 names by
     * {@code scripts/harvest-1.12.2-srg-members.py}). Loaded into the SAME maps as the modern
     * {@code m_}/{@code f_} dictionary: the key patterns never collide, and the remapper picks
     * the branch by prefix.
     */
    private static final String RESOURCE_PATH_1122 = "/retromod/srg-1.12.2-to-mojang.tsv";

    private static SrgToMojangMapper loadFromResource() {
        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        boolean any = loadOne(RESOURCE_PATH, methods, fields);
        any |= loadOne(RESOURCE_PATH_1122, methods, fields);
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
