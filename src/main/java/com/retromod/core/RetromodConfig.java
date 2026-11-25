/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.util.JsonSecurity;
import com.retromod.util.RetromodLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reads and writes Retromod's {@code config.json} for every loader. */
public final class RetromodConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Config");

    /** {@code config/retromod} relative to the game directory. */
    public static final Path CONFIG_DIR = Path.of("config", "retromod");
    /** {@code config/retromod/config.json}. */
    public static final Path CONFIG_PATH = CONFIG_DIR.resolve("config.json");
    private static final long MAX_CONFIG_BYTES = 1024L * 1024;

    /** Written when no config exists; kept here so every loader writes the same file. */
    static final String DEFAULT_CONFIG = """
            {
              "_comment": "Retromod Configuration - https://github.com/Bownlux/Retromod",

              "_install_comment": "Forge and NeoForge: false keeps translated copies in retromod-cache/ and never changes mods/. true writes translated jars into mods/ like earlier versions. Fabric and Quilt always use retromod-input/.",
              "write_translated_jars": false,

              "_huge_jars_comment": "A mod jar that unpacks to more than 1 GB passes an archive check first. Over 25 GB, or when the check fails, Retromod asks on the title screen. true here allows jars over 25 GB that pass the check, never one that failed it.",
              "allow_huge_jars": false,

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

              "polyfills_enabled": true,

              "verify_transforms": true,

              "_network_comment": "Retromod never initiates network downloads without user consent. The flag below is OFF by default - flip it to true to enable the optional Modrinth lookup (queries Modrinth's public API to suggest native versions of mods you're transforming). No JAR files are ever auto-downloaded; the CLI's 'archive download' command is the only path that fetches files, and it prompts before each download.",
              "check_for_native_versions": false
            }
            """;

    // Static methods only, with no loader supertype, so all three entry points can use this
    // without pulling in another loader's classes (#74).
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
            LOGGER.debug(RetromodLog.DETAIL, "Generated default config at {}", CONFIG_PATH);
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
        return loadExistingOrNull();
    }

    private static JsonObject loadExistingOrNull() {
        return loadExistingOrNull(CONFIG_PATH);
    }

    private static JsonObject loadExistingOrNull(Path configPath) {
        try {
            if (configPath == null || !Files.exists(configPath)) return null;
            String json = JsonSecurity.readUtf8(configPath, MAX_CONFIG_BYTES,
                    JsonSecurity.DEFAULT_MAX_DEPTH, "Retromod config");
            return parseConfig(json);
        } catch (Exception e) {
            LOGGER.warn("Could not load config {}: {}", configPath, e.getMessage());
            return null;
        }
    }

    static JsonObject parseConfig(String json) throws IOException {
        JsonSecurity.validate(json, MAX_CONFIG_BYTES,
                JsonSecurity.DEFAULT_MAX_DEPTH, "Retromod config");
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Read one boolean setting, returning its documented default when it is absent or invalid. */
    public static boolean getBoolean(String key, boolean defaultValue) {
        return booleanValue(loadOrNull(), key, defaultValue);
    }

    /** Reads one setting without creating a missing config file. */
    public static boolean getBooleanIfPresent(String key, boolean defaultValue) {
        return booleanValue(loadExistingOrNull(), key, defaultValue);
    }

    /** Reads one setting from an explicit config path without creating a missing file. */
    public static boolean getBooleanIfPresent(
            Path configPath, String key, boolean defaultValue) {
        return booleanValue(loadExistingOrNull(configPath), key, defaultValue);
    }

    /** The config file inside an explicit game directory. */
    public static Path configPath(Path gameDir) {
        // Discovery-time code must use this: CONFIG_PATH alone resolves against the working directory.
        return gameDir.resolve(CONFIG_PATH);
    }

    /** Reads one boolean setting without creating a missing file. */
    public static Optional<Boolean> findBoolean(Path configPath, String key) {
        // Empty when the file, the key, or a valid boolean is missing, so callers can tell
        // "unset" from "set to the default".
        JsonObject config = loadExistingOrNull(configPath);
        if (config == null || key == null || !config.has(key)) return Optional.empty();
        var value = config.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            LOGGER.warn("Config setting '{}' in {} is not true or false; ignoring it",
                    key, configPath);
            return Optional.empty();
        }
        return Optional.of(value.getAsBoolean());
    }

    /**
     * Reads one setting as its raw JSON value without creating a missing file, for settings with
     * more than two states. Empty when the file or the key is missing, or the value is not a
     * string, number, or boolean.
     */
    public static Optional<com.google.gson.JsonPrimitive> findSetting(Path configPath, String key) {
        JsonObject config = loadExistingOrNull(configPath);
        if (config == null || key == null || !config.has(key)) return Optional.empty();
        var value = config.get(key);
        if (!value.isJsonPrimitive()) {
            LOGGER.warn("Config setting '{}' in {} is not a single value; ignoring it",
                    key, configPath);
            return Optional.empty();
        }
        return Optional.of(value.getAsJsonPrimitive());
    }

    /** Reads a list of strings without creating a missing file; empty when it is absent or not a list. */
    public static java.util.Set<String> findStrings(Path configPath, String key) {
        JsonObject config = loadExistingOrNull(configPath);
        if (config == null || key == null || !config.has(key)) return java.util.Set.of();
        var value = config.get(key);
        if (!value.isJsonArray()) {
            LOGGER.warn("Config setting '{}' in {} is not a list; ignoring it", key, configPath);
            return java.util.Set.of();
        }
        java.util.Set<String> strings = new java.util.LinkedHashSet<>();
        for (var element : value.getAsJsonArray()) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                strings.add(element.getAsString().trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return java.util.Collections.unmodifiableSet(strings);
    }

    /**
     * Adds {@code value} to the string list {@code key} and keeps every other setting. The file is
     * written next to itself first, so a failed write never leaves half a config.
     */
    public static synchronized void addString(Path configPath, String key, String value)
            throws IOException {
        Path folder = configPath.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        JsonObject root = Files.exists(configPath) ? parseConfig(JsonSecurity.readUtf8(configPath,
                MAX_CONFIG_BYTES, JsonSecurity.DEFAULT_MAX_DEPTH, "Retromod config")) : new JsonObject();
        com.google.gson.JsonArray list = root.has(key) && root.get(key).isJsonArray()
                ? root.getAsJsonArray(key) : new com.google.gson.JsonArray();
        for (var element : list) {
            if (element.isJsonPrimitive() && value.equals(element.getAsString())) return;
        }
        list.add(value);
        root.add(key, list);
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
        Path temp = Files.createTempFile(folder, ".config.json.", ".tmp");
        try {
            Files.writeString(temp, json);
            try {
                Files.move(temp, configPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
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
