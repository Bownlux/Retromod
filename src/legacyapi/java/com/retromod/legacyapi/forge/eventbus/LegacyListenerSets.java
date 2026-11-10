/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.EventListener;

/** Keeps the EventBus 7 listeners each old registration added, so {@code unregister} removes all of them as Forge 1.20.1 did. */
public final class LegacyListenerSets {

    // Groups by identity; within a group, the registered object by equals and hashCode, as EventBus 6 keyed its listeners.
    private static final Map<BusGroup, Map<Object, List<Added>>> ADDED = new IdentityHashMap<>();

    /**
     * One listener and the bus it is on; a null bus means the group placed it, as BusGroup.register does.
     * A static handler also holds its claim on the bus, which unregister lets go.
     */
    private record Added(EventBus<?> bus, EventListener listener, Method claimed) {}

    private LegacyListenerSets() {}

    /** Whether the group already holds listeners registered for an equal object, which EventBus 6 did not register again. */
    public static boolean holds(BusGroup group, Object target) {
        synchronized (ADDED) {
            Map<Object, List<Added>> byTarget = ADDED.get(group);
            return byTarget != null && byTarget.containsKey(target);
        }
    }

    /** Remembers what EventBus 7's own registration of {@code target} added to the group. */
    public static void remember(BusGroup group, Object target, Collection<EventListener> listeners) {
        if (listeners == null) return;
        for (EventListener listener : listeners) add(group, target, new Added(null, listener, null));
    }

    /** Remembers one listener added to {@code bus} for {@code target}, such as each half of a split tick listener. */
    public static void remember(BusGroup group, Object target, Object bus, EventListener listener) {
        if (bus instanceof EventBus<?> eventBus) add(group, target, new Added(eventBus, listener, null));
    }

    /** Remembers one handler the bridge hooked for {@code target}; a static one, with no receiver, holds a claim. */
    public static void remember(BusGroup group, Object target, Object bus, EventListener listener, Method handler,
            Object receiver) {
        if (bus instanceof EventBus<?> eventBus) add(group, target, new Added(eventBus, listener, receiver == null ? handler : null));
    }

    /** {@code bus.unregister(target)}: removes every listener the group's registrations of {@code target} added. */
    public static void forget(BusGroup group, Object target) {
        List<Added> added;
        synchronized (ADDED) {
            Map<Object, List<Added>> byTarget = ADDED.get(group);
            added = byTarget == null ? null : byTarget.remove(target);
        }
        if (added == null) return;
        List<EventListener> placedByGroup = new ArrayList<>();
        for (Added each : added) {
            if (each.bus() == null) {
                placedByGroup.add(each.listener());
            } else {
                each.bus().removeListener(each.listener());
            }
            // Registering the class again puts the static handler back, as it did on Forge 1.20.1.
            if (each.claimed() != null) LegacyStaticHandlers.release(each.claimed(), each.bus());
        }
        // EventBus 7 refuses an empty collection here.
        if (!placedByGroup.isEmpty()) group.unregister(placedByGroup);
    }

    private static void add(BusGroup group, Object target, Added added) {
        // Without a listener handle there is nothing unregister could remove later.
        if (group == null || target == null || added.listener() == null) return;
        synchronized (ADDED) {
            ADDED.computeIfAbsent(group, any -> new HashMap<>()).computeIfAbsent(target, any -> new ArrayList<>()).add(added);
        }
    }
}
