/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.RetromodConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Resolves the {@code write_translated_jars} switch.
 *
 * <p>{@code false} (the default) is in-place mode: translated copies live in Retromod's cache
 * and the player's files stay untouched. {@code true} restores the rewrite behavior of earlier
 * versions, which writes translated jars into {@code mods/}.
 *
 * <p>The setting is always read against the loader's game directory. The working directory is
 * not reliable at discovery time, and reading the wrong file silently ignores the switch.
 * {@link RetromodConfig#ensureDefaultConfig()} only writes a missing file, so existing configs
 * never gain this key and the default has to live here.
 */
public final class InPlaceSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-InPlace");

    /** Config key in {@code config/retromod/config.json}. */
    public static final String KEY = "write_translated_jars";

    /** One-run override, for example on a server start command. */
    public static final String SYSTEM_PROPERTY = "retromod.writeTranslatedJars";

    /** In-place mode is the default. */
    public static final boolean DEFAULT_WRITE_TRANSLATED_JARS = false;

    /** Set by the Forge and NeoForge bootstrap that loaded Retromod in this JVM. */
    static final String BOOT_OWNER_PROPERTY = "retromod.boot.owner";

    /** Where the effective value came from, for the startup log. */
    public enum Source { SYSTEM_PROPERTY, CONFIG_FILE, DEFAULT }

    /** The effective mode for one launch. */
    public record Mode(boolean writeTranslatedJars, Source source) {
        /** True when translated jars stay in the cache and {@code mods/} is never written. */
        public boolean inPlace() {
            return !writeTranslatedJars;
        }
    }

    private InPlaceSettings() {}

    /** Resolves the mode for {@code gameDir} using the JVM's system properties. */
    public static Mode resolve(Path gameDir) {
        return resolve(gameDir, System.getProperties());
    }

    /** Resolves the mode with explicit properties, so tests never touch global state. */
    public static Mode resolve(Path gameDir, Properties properties) {
        Objects.requireNonNull(gameDir, "gameDir");
        Optional<Boolean> override = parseOverride(
                properties == null ? null : properties.getProperty(SYSTEM_PROPERTY));
        if (override.isPresent()) {
            return new Mode(override.get(), Source.SYSTEM_PROPERTY);
        }
        Optional<Boolean> configured = RetromodConfig.findBoolean(
                RetromodConfig.configPath(gameDir), KEY);
        if (configured.isPresent()) {
            return new Mode(configured.get(), Source.CONFIG_FILE);
        }
        return new Mode(DEFAULT_WRITE_TRANSLATED_JARS, Source.DEFAULT);
    }

    /**
     * True when the switch can change anything in this game. Only the Forge and NeoForge
     * bootstrap serves translations from the cache. Fabric, Quilt, and a Retromod jar loaded
     * without its bootstrap always write translated jars into {@code mods/}, so a toggle
     * shown there would claim a choice the player does not have.
     */
    public static boolean switchApplies(Properties properties) {
        return properties != null && (properties.getProperty(BOOT_OWNER_PROPERTY) != null
                || properties.getProperty(InPlaceStatus.MODE_PROPERTY) != null);
    }

    /**
     * Whether this launch writes translated jars into {@code mods/}, as the discovery pass
     * decided. Empty when no pass ran. The saved setting can differ from this after the
     * player changes it, because the pass reads it only at startup.
     */
    public static Optional<Boolean> writesTranslatedJarsThisLaunch(Properties properties) {
        String mode = properties == null ? null
                : properties.getProperty(InPlaceStatus.MODE_PROPERTY);
        if (InPlaceStatus.MODE_REWRITE.equals(mode)) return Optional.of(Boolean.TRUE);
        if (InPlaceStatus.MODE_IN_PLACE.equals(mode)
                || InPlaceStatus.MODE_UNAVAILABLE.equals(mode)) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }

    private static Optional<Boolean> parseOverride(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("true")) return Optional.of(Boolean.TRUE);
        if (value.equals("false")) return Optional.of(Boolean.FALSE);
        LOGGER.warn("Ignoring -D{}={}: use true or false. The config file decides instead.",
                SYSTEM_PROPERTY, raw);
        return Optional.empty();
    }
}
