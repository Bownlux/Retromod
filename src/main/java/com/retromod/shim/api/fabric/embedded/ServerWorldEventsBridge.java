/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * Runtime half of the removed Fabric {@code ServerWorldEvents} bridge (server-level load/unload).
 *
 * <p>26.1 renamed the holder ({@code ServerWorldEvents} to {@code ServerLevelEvents}) and the SAM
 * methods ({@code onWorldLoad}/{@code onWorldUnload} to {@code onLevelLoad}/{@code onLevelUnload}).
 * A mod hard-codes {@code onWorldLoad}, which a class redirect onto {@code ServerLevelEvents$Load}
 * can't satisfy. Params only went {@code ServerWorld} to {@code ServerLevel} (remapped in the
 * lambda), so the forwarder replays the v2 call onto the v1 invoker unchanged.</p>
 *
 * <p>Reflection plus {@link Proxy}, embedded raw into each mod jar; goes inert on a reflective miss.
 * Mirrors {@link ItemGroupEventsBridge} but is duplicated so each bridge embeds standalone.</p>
 */
public final class ServerWorldEventsBridge {

    private ServerWorldEventsBridge() {}

    private static final String TAG = "[Retromod] ServerWorldEventsBridge: ";

    private static final String SERVER_LEVEL_EVENTS = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents";
    private static final String EVENT_FACTORY       = "net.fabricmc.fabric.api.event.EventFactory";

    // Snapshot.6 initially emitted these shared names. Keep the no-arg entrypoints below so
    // already transformed jars continue to load after the per-mod relocation fix.
    private static final String LEGACY_LOAD =
            "com.retromod.generated.legacylifecycle.ServerWorldLoad";
    private static final String LEGACY_UNLOAD =
            "com.retromod.generated.legacylifecycle.ServerWorldUnload";

    private static final String V2_LOAD   = SERVER_LEVEL_EVENTS + "$Load";
    private static final String V2_UNLOAD = SERVER_LEVEL_EVENTS + "$Unload";

    private static ClassLoader cl() {
        return ServerWorldEventsBridge.class.getClassLoader();
    }

    /** v1 {@code ServerWorldEvents.LOAD} as an {@code Event<Load>} wired to {@code ServerLevelEvents.LOAD}. */
    public static Object installLoad(Class<?> v1Type) {
        return install("LOAD", v1Type, V2_LOAD);
    }

    /** Compatibility entrypoint for jars transformed before listener types became per-mod. */
    public static Object installLoad() {
        return installLegacy("LOAD", LEGACY_LOAD, V2_LOAD);
    }

    /** v1 {@code ServerWorldEvents.UNLOAD} as an {@code Event<Unload>} wired to {@code ServerLevelEvents.UNLOAD}. */
    public static Object installUnload(Class<?> v1Type) {
        return install("UNLOAD", v1Type, V2_UNLOAD);
    }

    /** Compatibility entrypoint for jars transformed before listener types became per-mod. */
    public static Object installUnload() {
        return installLegacy("UNLOAD", LEGACY_UNLOAD, V2_UNLOAD);
    }

    private static Object installLegacy(String v2Field, String v1ClassName,
                                        String v2SamClassName) {
        try {
            return install(v2Field, Class.forName(v1ClassName, false, cl()), v2SamClassName);
        } catch (Throwable t) {
            System.out.println(TAG + "legacy " + v2Field + " wiring failed (" + t
                    + "); that lifecycle event is inert.");
            return null;
        }
    }

    private static Object install(String v2Field, Class<?> v1Type, String v2SamClassName) {
        try {
            Class<?> sle = Class.forName(SERVER_LEVEL_EVENTS, true, cl());
            Object v2Event = sle.getField(v2Field).get(null);
            return wire(v1Type, v2SamClassName, v2Event);
        } catch (Throwable t) {
            System.out.println(TAG + v2Field + " wiring failed (" + t + "); that lifecycle event is inert.");
            return createEmpty(v1Type);
        }
    }

    /** Build a v1 {@code Event} and register a forwarder on {@code v2Event} that replays each call onto the v1 invoker's SAM. */
    private static Object wire(Class<?> v1Type, String v2SamClassName, Object v2Event) throws Exception {
        ClassLoader cl = cl();
        Class<?> v2SamType = Class.forName(v2SamClassName, false, cl);

        Object v1Event = createArrayBacked(v1Type);
        // Resolve Event methods on the public interface; ArrayBackedEvent isn't public, lookups against it throw IllegalAccessException.
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
            if (Modifier.isAbstract(m.getModifiers()) && isSam(m)) return m;
        }
        for (Method m : declared.getMethods()) {
            if (isSam(m)) return m;
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
            case "toString": return "RetromodServerWorldEventsProxy@" + Integer.toHexString(System.identityHashCode(proxy));
            default:         return null;
        }
    }
}
