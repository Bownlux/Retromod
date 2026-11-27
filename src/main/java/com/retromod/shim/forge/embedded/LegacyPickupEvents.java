/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;

import java.lang.reflect.Method;

/** Forge's {@code EntityItemPickupEvent} cancel and result calls on NeoForge's {@code ItemEntityPickupEvent.Pre}. */
public final class LegacyPickupEvents {

    private LegacyPickupEvents() {}

    /** A cancel refuses the pickup; taking it back leaves the pickup to the game again. */
    public static void setCanceled(Object event, boolean cancel) {
        if (cancel) {
            setCanPickup(event, "FALSE");
        } else if (isCanceled(event)) {
            setCanPickup(event, "DEFAULT");
        }
    }

    public static boolean isCanceled(Object event) {
        return "FALSE".equals(canPickup(event));
    }

    /**
     * Forge's ALLOW still waited for the pickup delay and the owner check, which NeoForge's TRUE skips, so every old
     * result leaves the pickup to the game. A cancel still wins, as it did there.
     */
    public static void setResult(Object event, Object result) {
        if (isCanceled(event)) return;
        setCanPickup(event, "DEFAULT");
    }

    /** Forge's {@code getResult()}: ALLOW where something has forced the pickup, else DEFAULT. */
    public static LegacyEventResult getResult(Object event) {
        return "TRUE".equals(canPickup(event)) ? LegacyEventResult.ALLOW : LegacyEventResult.DEFAULT;
    }

    // The TriState type is vanilla's on 26.x and NeoForge's own on 1.21.1, so it is found from the method.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setCanPickup(Object event, String state) {
        Method setter = find(event, "setCanPickup", 1);
        Class<? extends Enum> type = (Class<? extends Enum>) setter.getParameterTypes()[0];
        invoke(setter, event, Enum.valueOf(type, state));
    }

    private static String canPickup(Object event) {
        Object state = invoke(find(event, "canPickup", 0), event);
        return state instanceof Enum<?> constant ? constant.name() : "DEFAULT";
    }

    private static Method find(Object event, String name, int parameters) {
        for (Method method : LegacyPublicMethods.methods(event.getClass())) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        throw new IllegalStateException("Retromod found no " + name + " on the pickup event " + event.getClass().getName()
                + ". Report this line with the Retromod and NeoForge versions.");
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not call " + method + " on " + target, e);
        }
    }
}
