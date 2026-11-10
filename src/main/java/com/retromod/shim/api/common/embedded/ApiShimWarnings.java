/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common.embedded;

import com.retromod.shim.common.embedded.LegacyLog;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

/** Warns once when an old mod's call through a third-party API shim gives up, naming the API, the call and the cause. */
public final class ApiShimWarnings {

    private ApiShimWarnings() {}

    /** Warns once per shim method and receiver class; {@code failure} is null when no matching method was found. */
    public static void warnOnce(String api, String shimMethod, Object receiver, String effect, Throwable failure) {
        // Never call into the mod object here: only its class name is read, and a null receiver is allowed.
        String receiverName = receiver == null ? null : receiver.getClass().getName();
        String target = receiverName == null ? "" : " on " + receiverName;
        String cause = failure == null ? "no matching method was found" : describe(failure);
        LegacyLog.warnOnce(api + ":" + shimMethod + ":" + receiverName, api + " compatibility: an old mod's call to "
                + shimMethod + target + " failed, so " + effect + ": " + cause);
    }

    /** The real cause of {@code failure}, without the reflection wrappers around it. */
    public static String describe(Throwable failure) {
        Throwable cause = failure;
        // Bounded, so a cause chain that loops cannot hang the caller.
        for (int depth = 0; depth < 8 && isWrapper(cause) && cause.getCause() != null; depth++) {
            cause = cause.getCause();
        }
        try {
            return cause.toString();
        } catch (RuntimeException | LinkageError broken) {
            // A mod exception whose message itself fails still gets named.
            return cause.getClass().getName();
        }
    }

    private static boolean isWrapper(Throwable failure) {
        return failure instanceof InvocationTargetException
                || failure instanceof UndeclaredThrowableException
                || failure instanceof ExceptionInInitializerError;
    }
}
