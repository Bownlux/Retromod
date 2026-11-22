/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** The drop item of an old boat subclass, asked from its own getDropItem once the boat exists. */
public final class LegacyBoatDrops {

    // An override of the host's now-final getDropItem is renamed with this prefix so the class loads.
    private static final String RENAMED = "retromod$final$getDropItem";
    // An override that calls super.getDropItem() comes back here; that inner call answers the vanilla item.
    private static final ThreadLocal<Boolean> ASKING = new ThreadLocal<>();

    private LegacyBoatDrops() {}

    /** A supplier for the boat constructor: the boat's own drop item once {@code boat} is set, else the named vanilla boat item. */
    public static Supplier<Object> of(AtomicReference<Object> boat, String fallback) {
        return () -> {
            if (ASKING.get() != null) return vanilla(fallback);
            ASKING.set(Boolean.TRUE);
            try {
                Object own = ownDrop(boat.get());
                return own != null ? own : vanilla(fallback);
            } finally {
                ASKING.remove();
            }
        };
    }

    // The mod's own getDropItem, found below the game's classes, under its renamed or its old name.
    private static Object ownDrop(Object boat) {
        for (Class<?> type = boat == null ? null : boat.getClass(); type != null && !type.getName().startsWith("net.minecraft.");
                type = type.getSuperclass()) {
            for (String name : new String[] {RENAMED, "getDropItem"}) {
                try {
                    Method drop = type.getDeclaredMethod(name);
                    drop.setAccessible(true);
                    Object item = drop.invoke(boat);
                    if (item != null) return item;
                } catch (ReflectiveOperationException | RuntimeException notHere) {
                    // try the next name, then the superclass
                }
            }
        }
        return null;
    }

    private static Object vanilla(String field) {
        try {
            return Class.forName("net.minecraft.world.item.Items", true, LegacyBoatDrops.class.getClassLoader()).getField(field).get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not find the item " + field + " for an old boat", failure);
        }
    }
}
