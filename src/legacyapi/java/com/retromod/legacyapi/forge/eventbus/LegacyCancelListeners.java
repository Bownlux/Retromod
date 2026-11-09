/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import com.retromod.legacyapi.forge.event.LegacyCancel;
import com.retromod.shim.forge.embedded.LegacyEventBusImpl;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.CancellableEventBus;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.EventListener;
import net.minecraftforge.eventbus.api.listener.ObjBooleanBiConsumer;

/** Adds an old listener to a host event's bus so that, on a bus that takes cancels, it can cancel as on Forge 1.20.1. */
public final class LegacyCancelListeners {

    private LegacyCancelListeners() {}

    /** {@code bus.addListener(priority, listener)}, as a predicate where the event takes a cancel, kept under {@code registered} for unregister. */
    public static void addListener(BusGroup group, Object registered, Object bus, Class<?> type, byte priority,
            boolean receiveCanceled, Consumer<Object> listener) {
        addListener(group, registered, bus, type, priority, receiveCanceled, listener, null);
    }

    // As above; a static handler named as claimed keeps its claim on the bus until unregister lets it go.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addListener(BusGroup group, Object registered, Object bus, Class<?> type, byte priority,
            boolean receiveCanceled, Consumer<Object> listener, Method claimed) {
        // RenderNameTagEvent has a cancellable bus that Forge fires without reading, so a cancel there stops nothing.
        if (!(bus instanceof CancellableEventBus<?> cancellable) || !LegacyCancel.cancelsThrough(type)) {
            LegacyListenerSets.remember(group, registered, bus, ((EventBus) bus).addListener(priority, (Consumer) listener),
                    claimed, null);
            return;
        }
        // EventBus 7 reads a cancel only from a listener's return value.
        Predicate<Object> predicate = LegacyCancel.predicate(listener, receiveCanceled);
        LegacyListenerSets.remember(group, registered, bus,
                ((CancellableEventBus) cancellable).addListener(priority, (Predicate) predicate), claimed, null);
        // It also stops at the first cancel, so a listener that takes cancelled events sees them from a monitor.
        if (receiveCanceled) {
            LegacyListenerSets.remember(group, registered, bus,
                    ((CancellableEventBus) cancellable).addListener((ObjBooleanBiConsumer) new Monitor(predicate)));
        }
    }

    /**
     * Adds a listener on a parent nothing posts to each of its children instead, as {@code addListener} would add it
     * there, from the {@code parent=child,child;...} table the bridge was built with; false for any other event.
     */
    public static boolean hookChildren(BusGroup group, Class<?> type, byte priority, boolean receiveCanceled,
            Consumer<Object> listener, String table) {
        List<String> children = children(type, table);
        if (children == null) return false;
        for (String child : children) {
            try {
                LegacyEventBusImpl.hookBus(group, Class.forName(child, false, type.getClassLoader()), priority,
                        receiveCanceled, listener);
            } catch (ClassNotFoundException | LinkageError missing) {
                // A child this host does not have takes nothing; the others still get the listener.
            }
        }
        return true;
    }

    /** Adds an old handler on a parent nothing posts to each child the table names, as Forge 1.20.1 delivered them; false for any other event. */
    public static boolean hookHandlerChildren(BusGroup group, Object registered, Method handler, Object receiver,
            byte priority, String table) {
        // Only an old void, one-parameter handler; a cancel twin is always on a child with a bus of its own.
        if (handler.getParameterCount() != 1 || handler.getReturnType() != void.class) return false;
        Class<?> type = handler.getParameterTypes()[0];
        List<String> children = children(type, table);
        if (children == null) return false;
        Consumer<Object> listener = event -> call(handler, receiver, event);
        for (String name : children) {
            Class<?> child;
            Object bus;
            try {
                child = Class.forName(name, false, type.getClassLoader());
                bus = child.getField("BUS").get(null);
            } catch (ReflectiveOperationException | LinkageError missing) {
                // A child this host does not have takes nothing; the others still get the handler.
                continue;
            }
            // A static handler goes onto each child's bus once, as it would if it listened to the child itself.
            if (receiver == null && !LegacyStaticHandlers.claim(group, handler, bus)) continue;
            addListener(group, registered, bus, child, priority, false, listener, receiver == null ? handler : null);
        }
        return true;
    }

    // The children the table names for the type, or null when it has no row; rows are "parent=child,child" split by ";".
    private static List<String> children(Class<?> type, String table) {
        String key = type.getName() + "=";
        int row = 0;
        if (!table.startsWith(key)) {
            int found = table.indexOf(";" + key);
            if (found < 0) return null;
            row = found + 1;
        }
        int start = row + key.length();
        int end = table.indexOf(';', start);
        return List.of(table.substring(start, end < 0 ? table.length() : end).split(","));
    }

    /** Whether a handler method answers a cancel, as Retromod's boolean and (event, boolean) twins of old handlers do. */
    public static boolean isCancelAware(Method handler) {
        return handler.getReturnType() == boolean.class || handler.getParameterCount() == 2;
    }

    /** Adds a cancel-aware handler method to its event's bus, kept under {@code registered} for unregister. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void addHandler(BusGroup group, Object registered, Object bus, Method handler, Object receiver,
            byte priority) {
        CancellableEventBus cancellable = (CancellableEventBus) bus;
        // A boolean twin cancels by its answer; an (event, boolean) twin runs after the post as a monitor.
        EventListener added = handler.getParameterCount() == 2
                ? cancellable.addListener((ObjBooleanBiConsumer) new HandlerMonitor(handler, receiver))
                : cancellable.addListener(priority, (Predicate) new HandlerPredicate(handler, receiver));
        LegacyListenerSets.remember(group, registered, bus, added, handler, receiver);
    }

    /** As {@link #addHandler} for the lone static handler a subscriber's companion hooks, kept under its class on the game group. */
    public static void addStaticHandler(Object bus, Method handler, byte priority) {
        // Every cancellable host event is a game event, and Forge 1.20.1 subscribed the class to the game bus.
        addHandler(BusGroup.DEFAULT, handler.getDeclaringClass(), bus, handler, null, priority);
    }

    private static Object call(Method handler, Object receiver, Object... arguments) {
        try {
            return handler.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            // The handler's own failure reaches the poster as it would from a listener EventBus 7 built itself.
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException unchecked) throw unchecked;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** A boolean handler method as a predicate. */
    private static final class HandlerPredicate implements Predicate<Object> {
        private final Method handler;
        private final Object receiver;

        HandlerPredicate(Method handler, Object receiver) {
            this.handler = handler;
            this.receiver = receiver;
        }

        @Override
        public boolean test(Object event) {
            return (Boolean) call(handler, receiver, event);
        }
    }

    /** An (event, boolean) handler method as a monitor. */
    private static final class HandlerMonitor implements ObjBooleanBiConsumer<Object> {
        private final Method handler;
        private final Object receiver;

        HandlerMonitor(Method handler, Object receiver) {
            this.handler = handler;
            this.receiver = receiver;
        }

        @Override
        public void accept(Object event, boolean cancelled) {
            call(handler, receiver, event, cancelled);
        }
    }

    /** Hands the end of each post to the listener's predicate, which runs the listener if a cancel skipped it. */
    private static final class Monitor implements ObjBooleanBiConsumer<Object> {
        private final Predicate<Object> predicate;

        Monitor(Predicate<Object> predicate) {
            this.predicate = predicate;
        }

        @Override
        public void accept(Object event, boolean cancelled) {
            LegacyCancel.monitor(predicate, event, cancelled);
        }
    }
}
