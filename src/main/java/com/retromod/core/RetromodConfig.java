/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loader-agnostic config helper: static methods, no loader supertype, so all three
 * entry points can write the default {@code config.json} without pulling in another
 * loader's classes (#74).
 */
public final class RetromodConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Config");

    /** {@code config/retromod} relative to the game directory. */
    public static final Path CONFIG_DIR = Path.of("config", "retromod");
    /** {@code config/retromod/config.json}. */
    public static final Path CONFIG_PATH = CONFIG_DIR.resolve("config.json");

    /** Written when no config exists; kept here so every loader writes the same file. */
    private static final String DEFAULT_CONFIG = """
            {
              "_comment": "Retromod Configuration - https://github.com/Bownlux/Retromod",

              "use_aot": true,
              "use_hybrid": true,
              "instruction_level_granularity": true,

              "transform_mixins": true,
              "transform_refmaps": true,

              "remap_reflection": true,

              "log_level": "INFO",
              "log_transformations": false,

              "target_mc_version": "auto",

              "debug": false,
              "dump_bytecode": false,

              "force_translate_complex": false,

              "polyfills_enabled": true,

              "verify_transforms": true,

              "_network_comment": "Retromod never initiates network downloads without user consent. The flag below is OFF by default - flip it to true to enable the optional Modrinth lookup (queries Modrinth's public API to suggest native versions of mods you're transforming). No JAR files are ever auto-downloaded; the CLI's 'archive download' command is the only path that fetches files, and it prompts before each download.",
              "check_for_native_versions": false
            }
            """;

    private RetromodConfig() {}

    /**
     * Create {@code config/retromod/} and write a default {@code config.json} if absent.
     * Idempotent and best-effort: never throws.
     */
    public static void ensureDefaultConfig() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (Exception e) {
            LOGGER.warn("Could not create config directory {}: {}", CONFIG_DIR, e.getMessage());
            return;
        }
        if (Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            Files.writeString(CONFIG_PATH, DEFAULT_CONFIG);
            LOGGER.info("Generated default config at {}", CONFIG_PATH);
        } catch (Exception e) {
            LOGGER.warn("Could not generate default config at {}: {}", CONFIG_PATH, e.getMessage());
        }
    }

    /**
     * Load the config as a {@link JsonObject}, writing the default first if missing.
     * Returns {@code null} if the file can't be read or parsed.
     */
    public static JsonObject loadOrNull() {
        ensureDefaultConfig();
        try {
            if (!Files.exists(CONFIG_PATH)) return null;
            String json = Files.readString(CONFIG_PATH);
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("Could not load config {}: {}", CONFIG_PATH, e.getMessage());
            return null;
        }
    }

    /** Read one boolean setting, returning its documented default when it is absent or invalid. */
    public static boolean getBoolean(String key, boolean defaultValue) {
        return booleanValue(loadOrNull(), key, defaultValue);
    }

    static boolean booleanValue(JsonObject config, String key, boolean defaultValue) {
        if (config == null || key == null || !config.has(key)) return defaultValue;
        try {
            var value = config.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalStateException("not a JSON boolean");
            }
            return value.getAsBoolean();
        } catch (Exception e) {
            LOGGER.warn("Config setting '{}' is not a boolean; using {}", key, defaultValue);
            return defaultValue;
        }
    }
}
