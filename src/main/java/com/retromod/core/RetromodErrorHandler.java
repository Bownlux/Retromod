/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps repeated non-fatal mod errors from flooding the log and freezing the game. */
public final class RetromodErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final int MAX_SEEN_ERRORS = 500;
    private static final Set<String> seenErrors = ConcurrentHashMap.newKeySet();

    private RetromodErrorHandler() {}

    /** Logs the first occurrence of each distinct non-fatal error. */
    public static void handleNonFatal(String className, Throwable t) {
        String key = className + "|" + t.getClass().getName() + "|" + t.getMessage();

        // A hard limit keeps hostile or badly broken mods from growing this set forever.
        if (seenErrors.size() >= MAX_SEEN_ERRORS) {
            return;
        }

        // Through the logger, so the line and its stack trace reach latest.log at ERROR.
        if (seenErrors.add(key)) {
            LOGGER.error("A mod entry point failed in {}. Minecraft can continue, but the rest of"
                    + " that entry point did not run, so features it sets up, such as key handlers,"
                    + " may be missing: {}", className, t.toString(), t);
        }
    }

    public static void reset() {
        seenErrors.clear();
    }
}
