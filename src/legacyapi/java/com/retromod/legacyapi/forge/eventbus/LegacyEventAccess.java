/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import com.retromod.legacyapi.forge.event.LegacyCancel;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.forge.embedded.LegacyEventPriority;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.eventbus.api.event.MutableEvent;

/** Old calls made on an {@code Event}-typed value, answered for host events and for every mod's legacy events. */
public final class LegacyEventAccess {

    private static final String EVENT_ROOT = "net.minecraftforge.eventbus.internal.Event";

    // Each mod's copy of the two public bases ends with these names, whatever package the embedder put it in.
    private static final String LEGACY_EVENT = "com.retromod.legacyapi.forge.eventbus.LegacyEvent";
    private static final String LEGACY_GENERIC_EVENT = "com.retromod.legacyapi.forge.eventbus.LegacyGenericEvent";

    // The members another copy is called through, in table order.
    private static final int MEMBERS = 8;
    private static final int DELIVER = 0;
    private static final int IS_CANCELABLE = 1;
    private static final int IS_CANCELED = 2;
    private static final int SET_CANCELED = 3;
    private static final int HAS_RESULT = 4;
    private static final int GET_PHASE = 5;
    private static final int SET_PHASE = 6;
    private static final int GET_GENERIC_TYPE = 7;

    private static final Map<Class<?>, MethodHandle[]> TABLES = new ConcurrentHashMap<>();
    private static volatile Class<?> eventRoot;

    private LegacyEventAccess() {}

    /** {@code Event.class}: EventBus 7's root of every event, which old code tests other classes against. */
    public static Class<?> eventRoot() {
        Class<?> root = eventRoot;
        if (root == null) {
            // EventBus 7 does not export the package, so the class is found by name, which checks no module access.
            try {
                root = Class.forName(EVENT_ROOT, false, MutableEvent.class.getClassLoader());
            } catch (ClassNotFoundException missing) {
                root = MutableEvent.class;
            }
            eventRoot = root;
        }
        return root;
    }

    /** {@code value instanceof Event}: true for host events and for every mod's legacy events. */
    public static boolean isEvent(Object value) {
        return value != null && eventRoot().isInstance(value);
    }

    /** Moves a legacy event's phase and says whether a listener with {@code receiveCanceled} runs; a host event always runs it. */
    public static boolean deliver(Object event, boolean receiveCanceled, byte phase) {
        if (event instanceof LegacyEvent legacy) return legacy.retromod$deliver(receiveCanceled, phase);
        MethodHandle theirs = handle(event, DELIVER);
        return theirs == null || (Boolean) call(theirs, event, receiveCanceled, phase);
    }

    public static boolean isCancelable(Object event) {
        if (event instanceof LegacyEvent legacy) return legacy.isCancelable();
        MethodHandle theirs = handle(event, IS_CANCELABLE);
        if (theirs != null) return (Boolean) call(theirs, event);
        // A host event keeps no cancel state; Retromod keeps it beside the event where the host's bus takes a cancel.
        return LegacyCancel.isCancelable(event);
    }

    public static boolean isCanceled(Object event) {
        if (event instanceof LegacyEvent legacy) return legacy.isCanceled();
        MethodHandle theirs = handle(event, IS_CANCELED);
        if (theirs != null) return (Boolean) call(theirs, event);
        return LegacyCancel.isCanceled(event);
    }

    public static void setCanceled(Object event, boolean cancel) {
        if (event instanceof LegacyEvent legacy) {
            legacy.setCanceled(cancel);
            return;
        }
        MethodHandle theirs = handle(event, SET_CANCELED);
        if (theirs != null) {
            call(theirs, event, cancel);
            return;
        }
        LegacyCancel.setCanceled(event, cancel);
    }

    /** A legacy event asks its marker; a host event answers as Forge 1.20.1 did for it. */
    public static boolean hasResult(Object event) {
        if (event instanceof LegacyEvent legacy) return legacy.hasResult();
        MethodHandle theirs = handle(event, HAS_RESULT);
        if (theirs != null) return (Boolean) call(theirs, event);
        return EventResults.hasResult(event);
    }

    /** Every mod's legacy events implement Forge 26.2's {@code HasResult}; Retromod keeps a result for host events without one. */
    public static Result getResult(Object event) {
        return EventResults.getResult(event);
    }

    public static void setResult(Object event, Result result) {
        EventResults.setResult(event, result);
    }

    /** The phase; another mod's copy answers with its own priority enum, matched here by constant name. */
    public static LegacyEventPriority getPhase(Object event) {
        if (event instanceof LegacyEvent legacy) return legacy.getPhase();
        MethodHandle theirs = handle(event, GET_PHASE);
        if (theirs == null) return null;
        Object phase = call(theirs, event);
        return phase == null ? null : LegacyEventPriority.valueOf(((Enum<?>) phase).name());
    }

    public static void setPhase(Object event, LegacyEventPriority phase) {
        if (event instanceof LegacyEvent legacy) {
            legacy.setPhase(phase);
            return;
        }
        MethodHandle theirs = handle(event, SET_PHASE);
        if (theirs == null) {
            unsupported("set the phase of", event);
            return;
        }
        Objects.requireNonNull(phase, "phase");
        Class<?> theirPriority = theirs.type().parameterType(1);
        call(theirs, event, constant(theirPriority, phase.name()));
    }

    /** The generic type of a legacy generic event from any mod; no Forge 26.2 or 26.3 host event declares one. */
    public static Type getGenericType(Object event) {
        if (event instanceof LegacyGenericEvent<?> generic) return generic.getGenericType();
        MethodHandle theirs = handle(event, GET_GENERIC_TYPE);
        return theirs == null ? null : (Type) call(theirs, event);
    }

    /** Another mod's handle for one member, or null for a host event or a member that copy cannot reach. */
    private static MethodHandle handle(Object event, int member) {
        MethodHandle[] table = TABLES.computeIfAbsent(event.getClass(), LegacyEventAccess::lookUp);
        // Another mod's legacy event carries its own copy of the delivery hook; a host event has none.
        return table[DELIVER] == null ? null : table[member];
    }

    private static MethodHandle[] lookUp(Class<?> type) {
        MethodHandle[] table = new MethodHandle[MEMBERS];
        Class<?> base = rebuiltBase(type);
        if (base == null) return table;
        // Looked up on the copy's public base: virtual calls still reach a subclass's overrides, even in a
        // class that is not public, and the subclass's own signatures are never loaded.
        table[DELIVER] = find(base, "retromod$deliver", boolean.class, byte.class);
        table[IS_CANCELABLE] = find(base, "isCancelable");
        table[IS_CANCELED] = find(base, "isCanceled");
        table[SET_CANCELED] = find(base, "setCanceled", boolean.class);
        table[HAS_RESULT] = find(base, "hasResult");
        table[GET_PHASE] = find(base, "getPhase");
        // The copy's phase setter takes that copy's own priority enum, the type its getter returns.
        if (table[GET_PHASE] != null) {
            table[SET_PHASE] = find(base, "setPhase", table[GET_PHASE].type().returnType());
        }
        table[GET_GENERIC_TYPE] = find(base, "getGenericType");
        return table;
    }

    // The nearest superclass that is a mod's copy of LegacyGenericEvent or LegacyEvent, or null for a host event.
    private static Class<?> rebuiltBase(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (isCopyOf(current.getName(), LEGACY_GENERIC_EVENT) || isCopyOf(current.getName(), LEGACY_EVENT)) {
                return current;
            }
        }
        return null;
    }

    private static boolean isCopyOf(String name, String rebuilt) {
        return name.equals(rebuilt) || name.endsWith("." + rebuilt);
    }

    private static MethodHandle find(Class<?> base, String name, Class<?>... parameters) {
        try {
            return MethodHandles.publicLookup().unreflect(base.getMethod(name, parameters));
        } catch (NoSuchMethodException | IllegalAccessException unreachable) {
            // A missing member, or a copy in a package its module keeps closed, answers as a host event would.
            return null;
        }
    }

    private static Object call(MethodHandle handle, Object... arguments) {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(Class<?> enumType, String name) {
        return Enum.valueOf((Class) enumType, name);
    }

    // EventBus 7 events keep no phase; the call is noted once per class.
    private static void unsupported(String action, Object event) {
        String type = event.getClass().getName();
        LegacyLog.warnOnce("legacy-event " + action + " " + type, "An old mod tried to " + action + " " + type
                + ", which Retromod cannot do for this EventBus 7 event yet, so the call was ignored");
    }
}
