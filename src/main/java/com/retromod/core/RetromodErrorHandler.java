/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps repeated non-fatal mod errors from flooding the log and freezing the game. */
public final class RetromodErrorHandler {

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

        if (seenErrors.add(key)) {
            System.err.println("[Retromod] A mod entry point failed in " + className
                    + ", but Minecraft can continue: " + t);
            t.printStackTrace();
        }
    }

    public static void reset() {
        seenErrors.clear();
    }
}
