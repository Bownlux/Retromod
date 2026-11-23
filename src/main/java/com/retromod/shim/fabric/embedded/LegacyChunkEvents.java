/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Old two-argument calls on Fabric API's chunk load callback, which 26.1 gave a third argument. */
public final class LegacyChunkEvents {

    public static final String INTERNAL_NAME = "com/retromod/shim/fabric/embedded/LegacyChunkEvents";
    private static final String LOAD = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents$Load";

    private LegacyChunkEvents() {}

    /** {@code listener.onChunkLoad(level, chunk)}: the host form, told the chunk was not just generated, as the old call could not say. */
    public static void onChunkLoad(Object listener, Object level, Object chunk) {
        try {
            // The public interface's method, since a lambda's own class cannot be called reflectively.
            Class<?> load = Class.forName(LOAD, false, listener.getClass().getClassLoader());
            for (Method method : load.getMethods()) {
                if (method.getName().equals("onChunkLoad") && method.getParameterCount() == 3
                        && method.getParameterTypes()[2] == boolean.class) {
                    method.invoke(listener, level, chunk, false);
                    return;
                }
            }
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not call the chunk load listener " + listener
                    + ". Report this line with the Retromod and Fabric API versions.", e);
        }
        throw new IllegalStateException("This Fabric API's chunk load callback has no onChunkLoad Retromod knows."
                + " Report this line with the Retromod and Fabric API versions.");
    }
}
