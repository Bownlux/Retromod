/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Refreshes block-state caches after a legacy mod changes a block's random-tick flag. */
public final class LegacyBlockRandomTickBridge {

    public static final String INTERNAL_NAME =
            "com/retromod/shim/fabric/embedded/LegacyBlockRandomTickBridge";

    private LegacyBlockRandomTickBridge() {}

    /**
     * Rebuilds every state owned by {@code block}. Modern Minecraft caches the random-tick flag
     * on each state, so changing only the inherited block field no longer changes scheduling.
     */
    public static void refreshStates(Object block) {
        if (block == null) {
            throw new IllegalArgumentException("block must not be null");
        }

        try {
            Object definition = publicMethod(block.getClass(), "getStateDefinition").invoke(block);
            Object possibleStates = publicMethod(definition.getClass(), "getPossibleStates")
                    .invoke(definition);
            if (!(possibleStates instanceof Iterable<?> states)) {
                throw new IllegalStateException("getPossibleStates did not return an Iterable");
            }
            for (Object state : states) {
                publicMethod(state.getClass(), "initCache").invoke(state);
            }
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof InvocationTargetException invocation
                    && invocation.getCause() != null
                    ? invocation.getCause() : e;
            throw new IllegalStateException(
                    "Retromod could not refresh the random-tick state cache for "
                            + block.getClass().getName(), cause);
        }
    }

    private static Method publicMethod(Class<?> owner, String name) throws NoSuchMethodException {
        return owner.getMethod(name);
    }
}
