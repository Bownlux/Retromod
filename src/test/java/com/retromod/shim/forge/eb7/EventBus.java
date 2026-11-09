/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.eb7;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Stands in for EventBus 7's per-class EventBus, with the behavior Retromod relies on. */
public interface EventBus {

    EventListener addListener(byte priority, Consumer<Object> listener);

    EventListener addListener(Consumer<Object> listener);

    void removeListener(EventListener listener);

    boolean post(Event event);

    boolean hasListeners();

    static EventBus create(BusGroup group, Class<?> type) {
        return Bus.of((BusGroup.Group) group, type);
    }

    /** The handle a listener gets back, which removeListener takes. */
    final class Handle implements EventListener {
        private final Class<?> type;
        private final byte priority;

        Handle(Class<?> type, byte priority) {
            this.type = type;
            this.priority = priority;
        }

        public Class<?> eventType() {
            return type;
        }

        public byte priority() {
            return priority;
        }
    }

    /** One bus per group and class; tests read its group and type to see where a listener went. */
    final class Bus implements EventBus {
        private static final Map<BusGroup, Map<Class<?>, Bus>> BUSES = new HashMap<>();
        private static final String INHERITABLE = "net.minecraftforge.eventbus.api.event.InheritableEvent";
        private static final String MUTABLE = "net.minecraftforge.eventbus.api.event.MutableEvent";

        public final BusGroup group;
        public final Class<?> type;
        private final List<Object[]> listeners = new ArrayList<>();
        private final List<Bus> children = new ArrayList<>();

        private Bus(BusGroup group, Class<?> type) {
            this.group = group;
            this.type = type;
        }

        static synchronized Bus of(BusGroup.Group group, Class<?> type) {
            Bus existing = BUSES.computeIfAbsent(group, any -> new HashMap<>()).get(type);
            if (existing != null) return existing;
            if (group.baseType != null && !group.baseType.isAssignableFrom(type)) {
                throw new IllegalArgumentException(type + " is not a " + group.baseType + " for " + group.name);
            }
            Bus bus = new Bus(group, type);
            // A parent bus's listeners reach the child, as EventBus 7 does for inheritable events.
            for (Bus parent : parents(group, type)) {
                bus.listeners.addAll(parent.listeners);
                parent.children.add(bus);
            }
            BUSES.get(group).put(type, bus);
            return bus;
        }

        private static List<Bus> parents(BusGroup.Group group, Class<?> type) {
            List<Bus> parents = new ArrayList<>();
            Class<?> parent = type.getSuperclass();
            if (parent != null && !parent.getName().equals(MUTABLE) && isInheritable(parent)) {
                parents.add(of(group, parent));
            }
            return parents;
        }

        private static boolean isInheritable(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Class<?> each : current.getInterfaces()) {
                    if (each.getName().equals(INHERITABLE) || isInheritable(each)) return true;
                }
            }
            return false;
        }

        @Override
        public EventListener addListener(byte priority, Consumer<Object> listener) {
            Handle handle = new Handle(type, priority);
            add(new Object[]{priority, listener, handle});
            return handle;
        }

        // Takes the listener off this bus and the child buses it reached, as EventBus 7 does.
        @Override
        public void removeListener(EventListener listener) {
            listeners.removeIf(entry -> entry[2] == listener);
            for (Bus child : children) child.removeListener(listener);
        }

        @Override
        public EventListener addListener(Consumer<Object> listener) {
            return addListener((byte) 0, listener);
        }

        private void add(Object[] entry) {
            listeners.add(entry);
            for (Bus child : children) child.add(entry);
        }

        // Descending priority, keeping registration order within a priority, as EventBus 7's stable sort does.
        @Override
        @SuppressWarnings("unchecked")
        public boolean post(Event event) {
            List<Object[]> sorted = new ArrayList<>(listeners);
            sorted.sort(Comparator.comparingInt(entry -> -(byte) entry[0]));
            for (Object[] entry : sorted) ((Consumer<Object>) entry[1]).accept(event);
            return false;
        }

        @Override
        public boolean hasListeners() {
            return !listeners.isEmpty();
        }

        /** The priorities the bus holds, in the order a post runs them. */
        public List<Byte> priorities() {
            List<Object[]> sorted = new ArrayList<>(listeners);
            sorted.sort(Comparator.comparingInt(entry -> -(byte) entry[0]));
            return sorted.stream().map(entry -> (Byte) entry[0]).toList();
        }
    }
}
