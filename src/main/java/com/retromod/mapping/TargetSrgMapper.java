/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps Mojang member names to the SRG names exposed by a specific pre-26 Forge host.
 *
 * <p>The ordinary Mojang name is not a safe key. Names such as {@code get} occur on many
 * unrelated owners and overloads. The generated table is therefore keyed by owner, name,
 * and descriptor. This lets Retromod translate an old {@code func_} or {@code m_} name to
 * Mojang as an intermediate step, then emit the exact SRG name expected by the target Forge
 * runtime.
 */
public final class TargetSrgMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-TargetSrg");
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
        return owner + '\0' + name + '\0' + descriptor;
    }

    private static TargetSrgMapper load(String version) {
        String resource = switch (version) {
            case "1.20.1" -> "/retromod/target-srg-1.20.1.tsv";
            default -> null;
        };
        if (resource == null) {
            return EMPTY;
        }

        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        try (InputStream input = TargetSrgMapper.class.getResourceAsStream(resource)) {
            if (input == null) {
                LOGGER.warn("Target SRG mapping resource is missing: {}", resource);
                return EMPTY;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("\\t", -1);
                    if (parts.length != 5) {
                        continue;
                    }
                    String key = memberKey(parts[1], parts[2], parts[3]);
                    if ("METHOD".equals(parts[0])) {
                        methods.put(key, parts[4]);
                    } else if ("FIELD".equals(parts[0])) {
                        fields.put(key, parts[4]);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not load target SRG mappings for {}: {}", version, e.toString());
            return EMPTY;
        }

        LOGGER.info("Loaded Forge {} target SRG mappings for {} methods and {} fields",
                version, methods.size(), fields.size());
        return new TargetSrgMapper(Map.copyOf(methods), Map.copyOf(fields));
    }
}
