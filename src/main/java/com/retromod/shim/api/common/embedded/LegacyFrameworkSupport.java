/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Runtime half of {@link com.retromod.shim.api.common.LegacyFrameworkNetworkBridge}: registers a
 * Framework 0.8 play message with Framework 0.13's builder.
 *
 * <p>Framework 0.8 built a message's codec from the message class itself: a no-argument instance
 * answered {@code encode}, {@code decode}, and {@code handle}. Framework 0.13 wants a name, a
 * {@code StreamCodec}, and a handler. This class makes one instance and adapts its three methods.
 * The calls are reflective because Retromod is not compiled against Minecraft or Framework.
 */
public final class LegacyFrameworkSupport {

    private static final String STREAM_CODEC = "net.minecraft.network.codec.StreamCodec";
    private static final String PACKET_FLOW = "net.minecraft.network.protocol.PacketFlow";
    private static final String BUILDER = "com.mrcrayfish.framework.api.network.FrameworkNetworkBuilder";

    private LegacyFrameworkSupport() {}

    /**
     * Registers {@code type} on {@code builder}.
     *
     * @param direction the old {@code MessageDirection} name, or null for a two-way message
     */
    public static void registerPlayMessage(Object builder, Class<?> type, String direction) {
        Object template = newTemplate(type);
        Method encode = findBridge(type, "encode", 2);
        Method decode = findBridge(type, "decode", 1);
        Method handle = findBridge(type, "handle", 2);
        ClassLoader loader = type.getClassLoader();
        try {
            Class<?> codecType = Class.forName(STREAM_CODEC, false, loader);
            Object codec = Proxy.newProxyInstance(codecType.getClassLoader(), new Class<?>[] {codecType},
                    codecHandler(template, encode, decode));
            BiConsumer<Object, Object> handler = (message, context) -> invoke(handle, template, message, context);
            String name = messageName(type);
            // Looked up on the public interface: the builder's own class is not exported.
            Class<?> builderType = Class.forName(BUILDER, false, builder.getClass().getClassLoader());
            if (direction == null) {
                findMethod(builderType, "registerPlayMessage", 4).invoke(builder, name, type, codec, handler);
            } else {
                Object flow = packetFlow(Class.forName(PACKET_FLOW, false, loader), direction);
                findMethod(builderType, "registerPlayMessage", 5)
                        .invoke(builder, name, type, codec, handler, flow);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot register the Framework message " + type.getName()
                    + " with this Framework version", e);
        }
    }

    /**
     * Accepts Framework 0.8 login data. Framework 0.13 has no login phase hook, and its
     * configuration messages need a codec the old data class does not describe, so nothing is sent.
     */
    public static void registerLoginData(Object id, Supplier<?> data) {
        // Intentionally empty: see the method comment.
    }

    /** A stable, valid resource path from the class name, the same on client and server. */
    static String messageName(Class<?> type) {
        return type.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }

    private static Object packetFlow(Class<?> flowType, String direction) {
        String constant = direction.contains("CLIENT") ? "CLIENTBOUND" : "SERVERBOUND";
        for (Object value : flowType.getEnumConstants()) {
            if (((Enum<?>) value).name().equals(constant)) return value;
        }
        throw new IllegalStateException("PacketFlow has no " + constant + " constant");
    }

    private static InvocationHandler codecHandler(Object template, Method encode, Method decode) {
        return (proxy, method, args) -> {
            String name = method.getName();
            int count = args == null ? 0 : args.length;
            if (name.equals("decode") && count == 1) return invoke(decode, template, args[0]);
            // StreamEncoder.encode(buffer, value); the old message took (value, buffer).
            if (name.equals("encode") && count == 2) return invoke(encode, template, args[1], args[0]);
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "LegacyFrameworkCodec[" + template.getClass().getName() + "]";
                };
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            throw new UnsupportedOperationException(name + " is not supported on a legacy Framework codec");
        };
    }

    private static Object newTemplate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Framework message " + type.getName()
                    + " needs the no-argument constructor Framework 0.8 required", e);
        }
    }

    /**
     * The erased method a generic message compiles to, such as {@code encode(Object, FriendlyByteBuf)}.
     * A message that is not generic declares only that one, so the first match by arity is used.
     */
    static Method findBridge(Class<?> type, String name, int parameters) {
        Method fallback = null;
        for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != parameters) continue;
                if (method.isBridge() || parameters == 1 || method.getParameterTypes()[0] == Object.class) {
                    method.setAccessible(true);
                    return method;
                }
                if (fallback == null) fallback = method;
            }
        }
        if (fallback == null) {
            throw new IllegalStateException("Framework message " + type.getName() + " has no " + name + " method");
        }
        fallback.setAccessible(true);
        return fallback;
    }

    private static Method findMethod(Class<?> owner, String name, int parameters) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters
                    && method.getParameterTypes()[0] == String.class) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + " with " + parameters + " parameters");
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call " + method, e);
        }
    }
}
