/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Marks Retromod's detail lines, which stay out of the game log unless {@code log_level} is {@code DEBUG}. */
public final class RetromodLog {

    /** The marker name, compared by name so any marker factory's copy matches. */
    public static final String DETAIL_NAME = "RETROMOD_DETAIL";

    /** Log a detail line with {@code LOGGER.debug(RetromodLog.DETAIL, ...)}. */
    public static final Marker DETAIL = MarkerFactory.getMarker(DETAIL_NAME);

    /** {@code -Dretromod.logLevel=debug} turns detail lines on for one launch. */
    public static final String PROPERTY = "retromod.logLevel";

    // The same file as RetromodConfig.CONFIG_PATH. Read here directly, so a log call never runs
    // RetromodConfig, which logs through this provider itself.
    private static final Path CONFIG_FILE = Path.of("config", "retromod", "config.json");
    private static final long MAX_CONFIG_BYTES = 1024L * 1024;

    private RetromodLog() {}

    /** True for a line logged with {@link #DETAIL}. */
    public static boolean isDetail(Marker marker) {
        return marker != null && (DETAIL_NAME.equals(marker.getName()) || marker.contains(DETAIL_NAME));
    }

    // Set only by tests, which read detail lines from standard error.
    private static volatile Boolean override;

    /** True when detail lines go to the game log at INFO, not only to the host's DEBUG output. */
    public static boolean detailInGameLog() {
        Boolean forced = override;
        return forced != null ? forced : Setting.IN_GAME_LOG;
    }

    /** Writes detail lines at INFO while a test needs to read them; null restores the setting. */
    public static void setDetailInGameLogForTesting(Boolean inGameLog) {
        override = inGameLog;
    }

    /** Reads the setting once, on the first detail line. */
    private static final class Setting {
        static final boolean IN_GAME_LOG = resolve(System.getProperty(PROPERTY), CONFIG_FILE);
    }

    /** The system property wins; otherwise {@code log_level} in the config file decides. */
    static boolean resolve(String property, Path configFile) {
        if (property != null && !property.isBlank()) return isDebug(property);
        try {
            if (configFile == null || !Files.isRegularFile(configFile)) return false;
            String json = JsonSecurity.readUtf8(configFile, MAX_CONFIG_BYTES,
                    JsonSecurity.DEFAULT_MAX_DEPTH, "Retromod config");
            JsonElement root = JsonParser.parseString(json);
            JsonElement level = root.isJsonObject() ? root.getAsJsonObject().get("log_level") : null;
            return level != null && level.isJsonPrimitive() && isDebug(level.getAsString());
        } catch (Exception | LinkageError unreadable) {
            // RetromodConfig reports a broken file; the log stays at its default here.
            return false;
        }
    }

    private static boolean isDebug(String level) {
        return "debug".equals(level.trim().toLowerCase(Locale.ROOT));
    }
}
