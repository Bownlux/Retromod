/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Runtime half of the removed Fabric v0 {@code ClientTickCallback} bridge (#129, Chat Bubbles).
 *
 * <p>{@code net.fabricmc.fabric.api.event.client.ClientTickCallback} (SAM {@code tick(MinecraftClient)},
 * static {@code EVENT}) was deleted from Fabric API around 1.16, replaced by
 * {@code ClientTickEvents.END_CLIENT_TICK} (SAM {@code onEndTick(MinecraftClient)}). A mod referencing
 * the old class fails to even load ({@code NoClassDefFoundError}), so the shim embeds a synthetic
 * {@code ClientTickCallback} interface whose {@code <clinit>} calls {@link #installEvent(Class)} here.
 *
 * <p>We build an array-backed v1 {@code Event} for the mod's {@code EVENT.register(...)} calls, then
 * register ONE listener on {@code ClientTickEvents.END_CLIENT_TICK} that, each client tick, pulls the
 * event's combined invoker and fans the tick out to every registered v0 listener. If the modern
 * lifecycle event can't be reached (e.g. loaded server-side) we log and leave the callbacks inert
 * rather than crash.
 */
public final class ClientTickCallbackBridge {

    private ClientTickCallbackBridge() {}

    private static final String TAG = "[Retromod] ClientTickCallbackBridge: ";

    private static final String EVENT_FACTORY   = "net.fabricmc.fabric.api.event.EventFactory";
    private static final String EVENT           = "net.fabricmc.fabric.api.event.Event";
    private static final String CLIENT_TICK      = "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents";
    private static final String END_TICK         = CLIENT_TICK + "$EndTick";

    /**
     * Build the v0 {@code Event} and wire its invoker to {@code END_CLIENT_TICK}; called once from the
     * synthetic {@code ClientTickCallback}'s {@code <clinit>}. Returns the {@code Event} (or null on
     * failure, so the field is null and registrations no-op rather than crash the class load).
     */
    public static Object installEvent(Class<?> v0Type) {
        try {
            ClassLoader cl = v0Type.getClassLoader();

            Class<?> eventFactory = Class.forName(EVENT_FACTORY, true, cl);
            Method createArrayBacked = eventFactory.getMethod("createArrayBacked", Class.class, Function.class);

            final Method v0Sam = sam(v0Type); // tick(Minecraft)
            Function<Object, Object> invokerFactory = (listenersObj) -> {
                final Object[] listeners = (Object[]) listenersObj;
                return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{v0Type}, (proxy, method, args) -> {
                    if (method.getDeclaringClass().isInterface()
                            && !"equals".equals(method.getName())
                            && !"hashCode".equals(method.getName())
                            && !"toString".equals(method.getName())) {
                        for (Object l : listeners) {
                            try {
                                v0Sam.invoke(l, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause() != null ? e.getCause() : e;
                            }
                        }
                        return null;
                    }
                    switch (method.getName()) {
                        case "equals":   return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        default:         return "RetromodClientTickProxy";
                    }
                });
            };
            final Object event = createArrayBacked.invoke(null, v0Type, invokerFactory);

            try {
                Class<?> eventClass = Class.forName(EVENT, false, cl);
                final Method invokerMethod = eventClass.getMethod("invoker");

                Class<?> tickEvents = Class.forName(CLIENT_TICK, true, cl);
                Object endClientTick = tickEvents.getField("END_CLIENT_TICK").get(null);
                Class<?> endTickType = Class.forName(END_TICK, false, cl);

                // one EndTick listener: each tick, fan out to the current combined v0 invoker
                Object endTickListener = java.lang.reflect.Proxy.newProxyInstance(
                        cl, new Class<?>[]{endTickType}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == endTickType) {
                        Object v0Invoker = invokerMethod.invoke(event);
                        try {
                            v0Sam.invoke(v0Invoker, args); // tick(client)
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause() != null ? e.getCause() : e;
                        }
                        return null;
                    }
                    switch (method.getName()) {
                        case "equals":   return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        default:         return "RetromodEndTickProxy";
                    }
                });

                registerListener(eventClass, endClientTick, endTickListener);
            } catch (Throwable t) {
                System.out.println(TAG + "could not attach to ClientTickEvents.END_CLIENT_TICK (" + t + "); "
                        + "ClientTickCallback listeners will register but won't fire.");
            }
            return event;
        } catch (Throwable t) {
            System.out.println(TAG + "could not create the v0 event (" + t + "); "
                    + "ClientTickCallback.EVENT will be null.");
            return null;
        }
    }

    /** Invoke through the public Event API, since its runtime implementation is package-private. */
    static void registerListener(Class<?> eventType, Object event, Object listener)
            throws ReflectiveOperationException {
        eventType.getMethod("register", Object.class).invoke(event, listener);
    }

    private static Method sam(Class<?> declared) {
        for (Method m : declared.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) return m;
        }
        throw new IllegalStateException("no SAM on " + declared.getName());
    }
}
