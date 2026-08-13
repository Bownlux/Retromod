/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;

/** Keeps the old supplier-shaped custom-payload context call usable on current Forge. */
public final class LegacyForgeNetworkEventAdapter {

    private LegacyForgeNetworkEventAdapter() {}

    public static Supplier<Object> getSource(Object event) {
        return () -> {
            try {
                return event.getClass().getMethod("getSource").invoke(event);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
                        ? e.getCause() : e;
                throw new IllegalStateException(
                        "Retromod could not read Forge's custom-payload context", cause);
            }
        };
    }
}
