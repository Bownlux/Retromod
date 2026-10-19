/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.shim.api.fabric.embedded.RawChannelPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Host payload handlers that call old mod handlers, and the way back from one to the other. */
final class ReceiverAdapters {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");
    private static final Map<String, Method> RECEIVE_METHODS = new ConcurrentHashMap<>();

    /** Hands one received payload and its host context to an old handler. */
    @FunctionalInterface
    interface Dispatch {
        void receive(Object payload, Object context) throws Throwable;
    }

    private ReceiverAdapters() {}

    /** A host handler for {@code endpoint} that runs {@code dispatch} and remembers {@code legacy}. */
    static Object hostHandler(Endpoint endpoint, Object legacy, Dispatch dispatch) {
        Class<?> handlerType = HostNet.type(endpoint.handler);
        return Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType},
                new Adapter(legacy, dispatch));
    }

    /** The old handler behind a host handler this class built, or null for any other handler. */
    static Object legacyHandlerOf(Object hostHandler) {
        if (hostHandler == null || !Proxy.isProxyClass(hostHandler.getClass())) return null;
        return Proxy.getInvocationHandler(hostHandler) instanceof Adapter adapter
                ? adapter.legacy : null;
    }

    /** The bytes of a received raw payload; empty when it is not a Retromod payload. */
    static byte[] bytesOf(Object payload) {
        byte[] data = RawChannelPayloads.bytesOf(payload);
        return data != null ? data : new byte[0];
    }

    /**
     * Calls an old handler's {@code receive} by reflection. This serves handlers that are not a
     * shared type: a jar translated before the runtime, or a host interface whose old shape a
     * lambda still implements under its old method type.
     */
    static void invokeReceive(Object handler, Object... args) throws Throwable {
        Method receive = RECEIVE_METHODS.computeIfAbsent(
                handler.getClass().getName() + "#" + args.length,
                key -> findReceive(handler.getClass(), args.length));
        if (receive == null) {
            throw new IllegalStateException(handler.getClass().getName()
                    + " has no receive method that takes " + args.length + " arguments");
        }
        try {
            receive.invoke(handler, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Finds {@code receive} with {@code arity} parameters. An interface method the handler implements
     * comes first; a declared method serves the rest.
     */
    private static Method findReceive(Class<?> type, int arity) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                for (Method method : iface.getMethods()) {
                    if (isReceive(method, arity) && implementedBy(type, method)) return method;
                }
            }
        }
        // A jar translated before the runtime built its lambdas with the old typed method, which the
        // old bridge's interface never declared, so only the lambda's own method can run.
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!isReceive(method, arity)) continue;
                try {
                    method.setAccessible(true);
                } catch (RuntimeException e) {
                    LOGGER.debug("Could not open {} for the networking bridge: {}", method, e.toString());
                }
                return method;
            }
        }
        return null;
    }

    /** Whether {@code type} has a body for {@code method}, not just the interface's declaration. */
    private static boolean implementedBy(Class<?> type, Method method) {
        try {
            return !Modifier.isAbstract(type.getMethod(method.getName(), method.getParameterTypes())
                    .getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean isReceive(Method method, int arity) {
        return method.getName().equals("receive") && method.getParameterCount() == arity
                && !method.isSynthetic();
    }

    /** Backs one host handler: its receive method runs the dispatch, and it equals only itself. */
    private static final class Adapter implements InvocationHandler {
        final Object legacy;
        final Dispatch dispatch;

        Adapter(Object legacy, Dispatch dispatch) {
            this.legacy = legacy;
            this.dispatch = dispatch;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("receive") && args != null && args.length == 2) {
                dispatch.receive(args[0], args[1]);
                return null;
            }
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args != null ? args[0] : null);
                case "toString" -> "RetromodLegacyReceiver[" + legacy + "]";
                default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args)
                        : null;
            };
        }
    }
}
