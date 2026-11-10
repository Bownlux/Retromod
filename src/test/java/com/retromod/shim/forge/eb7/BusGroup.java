/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.eb7;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Stands in for EventBus 7's BusGroup: a named group that may only take subtypes of its base type. */
public interface BusGroup {

    BusGroup DEFAULT = create("default");

    static BusGroup create(String name) {
        return new Group(name, null);
    }

    static BusGroup create(String name, Class<?> baseType) {
        return new Group(name, baseType);
    }

    String name();

    Collection<EventListener> register(MethodHandles.Lookup lookup, Object listener);

    Collection<EventListener> register(MethodHandles.Lookup lookup, Class<?> listenerClass);

    /** The group's own state: its base type and what EventBus 7's own registration was handed. */
    final class Group implements BusGroup {
        final String name;
        final Class<?> baseType;
        public final List<Object> registrations = new ArrayList<>();

        Group(String name, Class<?> baseType) {
            this.name = name;
            this.baseType = baseType;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Collection<EventListener> register(MethodHandles.Lookup lookup, Object listener) {
            registrations.add(listener);
            return List.of();
        }

        @Override
        public Collection<EventListener> register(MethodHandles.Lookup lookup, Class<?> listenerClass) {
            registrations.add(listenerClass);
            return List.of();
        }
    }
}
