/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.effect;

import com.retromod.legacyapi.forge.LegacySharedSlots;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Which living entity owns an attribute map, for old effect code that was handed the entity. */
public final class LegacyEffectHolders {

    static final int PROTOCOL = 1;

    // 1.20.5 stopped passing the entity to an effect's attribute calls, so the entity is learned from the hooks that
    // still name it. Every translated jar's copy shares these maps, and weak keys let an unloaded entity go.
    @SuppressWarnings("unchecked")
    private static final Map<Object, Object> HOLDERS = LegacySharedSlots.get("effect-holders", PROTOCOL, Map.class,
            () -> Collections.synchronizedMap(new WeakHashMap<>()));
    // The amplifier each effect last applied to a map, which the old removal was handed and the new one is not.
    @SuppressWarnings("unchecked")
    private static final Map<Object, Object> AMPLIFIERS = LegacySharedSlots.get("effect-amplifiers", PROTOCOL,
            Map.class, () -> Collections.synchronizedMap(new WeakHashMap<>()));
    // Additions that ran before the owner was known, which the next hook naming the owner finishes.
    @SuppressWarnings("unchecked")
    private static final Map<Object, Object> PENDING = LegacySharedSlots.get("effect-pending", PROTOCOL,
            Map.class, () -> Collections.synchronizedMap(new WeakHashMap<>()));

    private LegacyEffectHolders() {}

    /** Records that {@code entity} owns {@code attributes}. */
    public static void remember(Object attributes, Object entity) {
        // An effect's tick reports its owner every time it runs, so a known owner is left as it is.
        if (attributes == null || entity == null || holder(attributes) == entity) return;
        // The value is weak too: the entity holds its map, so a strong value would keep both alive.
        HOLDERS.put(attributes, new WeakReference<>(entity));
    }

    /** The entity that owns {@code attributes}, or null when no hook has named it yet. */
    public static Object holder(Object attributes) {
        Object reference = attributes == null ? null : HOLDERS.get(attributes);
        return reference instanceof WeakReference<?> weak ? weak.get() : null;
    }

    /** Records the amplifier {@code effect} applied to {@code attributes} with. */
    public static void applied(Object attributes, Object effect, int amplifier) {
        perEffect(AMPLIFIERS, attributes).put(effect, amplifier);
    }

    /** The amplifier {@code effect} last applied to {@code attributes} with, or 0 when it is not known. */
    public static int amplifier(Object attributes, Object effect) {
        Object known = perEffect(AMPLIFIERS, attributes).get(effect);
        return known instanceof Integer level ? level : 0;
    }

    /** Records an addition that ran before the owner of {@code attributes} was known. */
    public static void pending(Object attributes, Object effect, int amplifier) {
        perEffect(PENDING, attributes).put(effect, amplifier);
    }

    /** Removes and returns the waiting addition of {@code effect} to {@code attributes}, or -1 when none waits. */
    public static int takePending(Object attributes, Object effect) {
        Object waiting = perEffect(PENDING, attributes).remove(effect);
        return waiting instanceof Integer level ? level : -1;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> perEffect(Map<Object, Object> table, Object attributes) {
        if (attributes == null) return new HashMap<>();
        synchronized (table) {
            return (Map<Object, Object>) table.computeIfAbsent(attributes,
                    unused -> Collections.synchronizedMap(new HashMap<>()));
        }
    }
}
