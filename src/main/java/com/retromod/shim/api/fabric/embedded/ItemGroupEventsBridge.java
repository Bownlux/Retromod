/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Runtime half of the removed Fabric item-group events v1 bridge
 * ({@code ItemGroupEvents}), reimplemented on 26.1's {@code CreativeModeTabEvents}.
 *
 * <p>The SAM method renamed ({@code modifyEntries} -> {@code modifyOutput}), so a class
 * redirect would crash {@code LambdaMetafactory}. Instead a forwarding {@link Proxy}
 * replays each v2 call's args onto the v1 invoker (the v1/v2 parameter objects carry the
 * same instance). The {@code addAfter}/{@code addBefore} rename is handled in
 * {@link com.retromod.shim.api.fabric.FabricItemGroupEventsShim}.</p>
 *
 * <p>All reflection, no compile-time Fabric/MC dependency, embedded into each transformed
 * mod jar. A reflective miss leaves the event registrable but inert. Contracts checked
 * against {@code fabric-api-0.145.4+26.1.2}.</p>
 */
public final class ItemGroupEventsBridge {

    private ItemGroupEventsBridge() {}

    private static final String TAG = "[Retromod] ItemGroupEventsBridge: ";

    private static final String CREATIVE_TAB_EVENTS = "net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents";
    private static final String EVENT_FACTORY       = "net.fabricmc.fabric.api.event.EventFactory";
    private static final String RESOURCE_KEY        = "net.minecraft.resources.ResourceKey";

    // Snapshot.6 initially emitted these shared names. Keep the no-arg entrypoints below so
    // already transformed jars continue to load after the per-mod relocation fix.
    private static final String LEGACY_MODIFY_ENTRIES =
            "com.retromod.generated.legacyitemgroup.ModifyEntries";
    private static final String LEGACY_MODIFY_ENTRIES_ALL =
            "com.retromod.generated.legacyitemgroup.ModifyEntriesAll";

    // v2 counterparts of the synthetic v1 SAM interfaces injected by the shim
    private static final String V2_MODIFY_OUTPUT     = CREATIVE_TAB_EVENTS + "$ModifyOutput";
    private static final String V2_MODIFY_OUTPUT_ALL = CREATIVE_TAB_EVENTS + "$ModifyOutputAll";

    /**
     * Per-SAM-type and per-{@code ResourceKey} v1 events. Each transformed mod gets its own
     * relocated SAM type, so sharing only by key would recreate the listener-array type mismatch.
     */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<Object, Object>> PER_TYPE =
            new ConcurrentHashMap<>();

    // computeIfAbsent, not get-then-putIfAbsent: wire() registers a forwarder on the live
    // v2 event, so a lost race would fire every handler twice per tab build.

    private static ClassLoader cl() {
        return ItemGroupEventsBridge.class.getClassLoader();
    }

    /**
     * v1 {@code ItemGroupEvents.modifyEntriesEvent(key)} -> a v1 {@code Event<ModifyEntries>}
     * wired to {@code CreativeModeTabEvents.modifyOutputEvent(key)}.
     */
    public static Object modifyEntriesEvent(Object resourceKey, Class<?> v1Type) {
        ConcurrentHashMap<Object, Object> perKey =
                PER_TYPE.computeIfAbsent(v1Type, ignored -> new ConcurrentHashMap<>());
        return perKey.computeIfAbsent(resourceKey, key -> {
            try {
                Class<?> cmte = Class.forName(CREATIVE_TAB_EVENTS, true, cl());
                Class<?> keyType = Class.forName(RESOURCE_KEY, false, cl());
                Object v2Event = cmte.getMethod("modifyOutputEvent", keyType).invoke(null, key);
                return wire(v1Type, V2_MODIFY_OUTPUT, v2Event);
            } catch (Throwable t) {
                System.out.println(TAG + "modifyEntriesEvent wiring failed (" + t + "); entries inert for this tab.");
                return createEmpty(v1Type);
            }
        });
    }

    /** Compatibility entrypoint for jars transformed before listener types became per-mod. */
    public static Object modifyEntriesEvent(Object resourceKey) {
        try {
            return modifyEntriesEvent(resourceKey,
                    Class.forName(LEGACY_MODIFY_ENTRIES, false, cl()));
        } catch (Throwable t) {
            System.out.println(TAG + "legacy modifyEntriesEvent wiring failed (" + t
                    + "); entries inert for this tab.");
            return null;
        }
    }

    /**
     * v1 {@code ItemGroupEvents.MODIFY_ENTRIES_ALL} -> a v1 {@code Event<ModifyEntriesAll>}
     * wired to {@code CreativeModeTabEvents.MODIFY_OUTPUT_ALL}. Called once from the
     * synthetic holder's static init.
     */
    public static Object installModifyAll(Class<?> v1Type) {
        try {
            Class<?> cmte = Class.forName(CREATIVE_TAB_EVENTS, true, cl());
            Object v2Event = cmte.getField("MODIFY_OUTPUT_ALL").get(null);
            return wire(v1Type, V2_MODIFY_OUTPUT_ALL, v2Event);
        } catch (Throwable t) {
            System.out.println(TAG + "MODIFY_ENTRIES_ALL wiring failed (" + t + "); all-tabs entries inert.");
            return createEmpty(v1Type);
        }
    }

    /** Compatibility entrypoint for jars transformed before listener types became per-mod. */
    public static Object installModifyAll() {
        try {
            return installModifyAll(Class.forName(LEGACY_MODIFY_ENTRIES_ALL, false, cl()));
        } catch (Throwable t) {
            System.out.println(TAG + "legacy MODIFY_ENTRIES_ALL wiring failed (" + t
                    + "); all-tabs entries inert.");
            return null;
        }
    }

    /**
     * Build a v1 {@code Event} for {@code v1Type} and register a forwarder on
     * {@code v2Event} that replays each v2 SAM call onto the v1 invoker's SAM.
     */
    private static Object wire(Class<?> v1Type, String v2SamClassName, Object v2Event) throws Exception {
        ClassLoader cl = cl();
        Class<?> v2SamType = Class.forName(v2SamClassName, false, cl);

        Object v1Event = createArrayBacked(v1Type);
        // resolve on the public Event interface; the impl (ArrayBackedEvent) is non-public
        // and throws IllegalAccessException on lookups against it
        Class<?> eventIface = Class.forName("net.fabricmc.fabric.api.event.Event", false, cl);
        final Method invokerM = eventIface.getMethod("invoker");
        final Method v1Sam = sam(v1Type);

        Object forwarder = Proxy.newProxyInstance(cl, new Class<?>[]{v2SamType}, (proxy, method, args) -> {
            if (isSam(method)) {
                Object v1Invoker = invokerM.invoke(v1Event);
                invokeUnwrapped(v1Sam, v1Invoker, args);
                return null;
            }
            return objectMethod(proxy, method, args);
        });

        eventIface.getMethod("register", Object.class).invoke(v2Event, forwarder);
        return v1Event;
    }

    /** Unwired fallback v1 {@code Event} used when wiring to the v2 event fails. */
    private static Object createEmpty(Class<?> v1Type) {
        try {
            return createArrayBacked(v1Type);
        } catch (Throwable t) {
            System.out.println(TAG + "could not create fallback event for " + v1Type.getName() + " (" + t + ").");
            return null;
        }
    }

    private static Object createArrayBacked(Class<?> v1Type) throws Exception {
        ClassLoader cl = v1Type.getClassLoader();
        Class<?> eventFactory = Class.forName(EVENT_FACTORY, true, cl);
        Method createArrayBacked = eventFactory.getMethod("createArrayBacked", Class.class, Function.class);
        Function<Object, Object> invokerFactory = (listenersObj) -> {
            final Object[] listeners = (Object[]) listenersObj;
            return Proxy.newProxyInstance(cl, new Class<?>[]{v1Type}, (proxy, method, args) -> {
                if (isSam(method)) {
                    for (Object listener : listeners) {
                        invokeUnwrapped(method, listener, args);
                    }
                    return null;
                }
                return objectMethod(proxy, method, args);
            });
        };
        return createArrayBacked.invoke(null, v1Type, invokerFactory);
    }

    private static Method sam(Class<?> declared) {
        for (Method m : declared.getMethods()) {
            if ((m.getModifiers() & java.lang.reflect.Modifier.ABSTRACT) != 0 && isSam(m)) return m;
        }
        throw new IllegalStateException("no SAM on " + declared.getName());
    }

    private static boolean isSam(Method m) {
        String n = m.getName();
        return m.getDeclaringClass().isInterface()
                && !("equals".equals(n) && m.getParameterCount() == 1)
                && !"hashCode".equals(n)
                && !"toString".equals(n);
    }

    private static void invokeUnwrapped(Method m, Object target, Object[] args) throws Throwable {
        try {
            m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":   return proxy == (args == null ? null : args[0]);
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return "RetromodItemGroupV1Proxy@" + Integer.toHexString(System.identityHashCode(proxy));
            default:         return null;
        }
    }
}
