/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.loaderapi.fabric.v26.HostLink.Member;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Old packet send listeners, which 26.x replaced with netty's {@code ChannelFutureListener}. */
public final class SendListeners {

    private static final Map<Object, Object> HOST_LISTENERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SendListeners() {}

    /**
     * Old {@code PacketSendListener.thenRun(Runnable)}. The mod keeps the result in a variable typed
     * {@code PacketSendListener}, now a class, so it gets a plain instance that stands for the host
     * listener the old call would have built.
     */
    public static Object thenRun(Runnable action) {
        Object hostListener = HostNet.call(Member.staticMethod(HostNet.SEND_LISTENER, "thenRun",
                "(Ljava/lang/Runnable;)L" + HostNet.CHANNEL_FUTURE_LISTENER + ";"), action);
        return standIn(hostListener);
    }

    /** Old {@code PacketSendListener.exceptionallySend(Supplier)}. */
    public static Object exceptionallySend(Supplier<?> packet) {
        Object hostListener = HostNet.call(Member.staticMethod(HostNet.SEND_LISTENER,
                "exceptionallySend", "(Ljava/util/function/Supplier;)L"
                        + HostNet.CHANNEL_FUTURE_LISTENER + ";"), packet);
        return standIn(hostListener);
    }

    private static Object standIn(Object hostListener) {
        Object standIn = HostNet.call(Member.constructor(HostNet.SEND_LISTENER, "()V"));
        HOST_LISTENERS.put(standIn, hostListener);
        return standIn;
    }

    /**
     * The {@code ChannelFutureListener} for an old listener argument, or null for none. A netty
     * {@code GenericFutureListener} is wrapped; a stand-in from {@link #thenRun} maps back to its
     * host listener.
     */
    static Object toHost(Object listener) {
        if (listener == null) return null;
        if (HostNet.isInstance(HostNet.CHANNEL_FUTURE_LISTENER, listener)) return listener;
        // Only a stand-in is looked up, so a mod's own listener is never hashed.
        if (HostNet.isInstance(HostNet.SEND_LISTENER, listener)) return HOST_LISTENERS.get(listener);
        if (!HostNet.isInstance(HostNet.GENERIC_FUTURE_LISTENER, listener)) return null;
        Class<?> channelListener = HostNet.type(HostNet.CHANNEL_FUTURE_LISTENER);
        return Proxy.newProxyInstance(channelListener.getClassLoader(),
                new Class<?>[]{channelListener}, new ForwardingListener(listener));
    }

    /** Forwards {@code operationComplete} to an old netty listener. */
    private static final class ForwardingListener implements InvocationHandler {
        final Object listener;

        ForwardingListener(Object listener) {
            this.listener = listener;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("operationComplete") && args != null && args.length == 1) {
                HostNet.call(Member.virtual(HostNet.GENERIC_FUTURE_LISTENER, "operationComplete",
                        "(L" + HostNet.FUTURE + ";)V"), listener, args[0]);
                return null;
            }
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args != null ? args[0] : null);
                case "toString" -> "RetromodListener[" + listener + "]";
                default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args)
                        : null;
            };
        }
    }
}
