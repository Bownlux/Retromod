/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** The one raw-bytes payload and codec Retromod registers for a legacy channel, shared by every bridge. */
public final class RawChannelPayloads {

    /** Lets the loader API runtime take over the old client bridge while it is on. */
    public interface ClientDelegate {
        boolean registerGlobalReceiver(Object id, Object handler);

        boolean registerReceiver(Object id, Object handler);

        void send(Object id, Object buf);

        boolean canSend(Object id);
    }

    private static volatile ClientDelegate clientDelegate;
    private static volatile Method readableBytes;
    private static volatile Method readBytes;
    private static volatile Method writeBytes;

    private RawChannelPayloads() {}

    /** Installs the runtime's client channels; the old bridge then forwards every call to them. */
    public static void installClientDelegate(ClientDelegate delegate) {
        clientDelegate = delegate;
    }

    /** The installed delegate, or null while the runtime is off. */
    public static ClientDelegate clientDelegate() {
        return clientDelegate;
    }

    /** A {@code CustomPacketPayload} carrying {@code type} and {@code data}. */
    public static Object newPayload(Class<?> payloadInterface, Object type, byte[] data) {
        return Proxy.newProxyInstance(payloadInterface.getClassLoader(),
                new Class<?>[]{payloadInterface}, new PayloadHandler(type, data));
    }

    /** A {@code StreamCodec} bound to {@code type} that copies raw bytes both ways. */
    public static Object newCodec(Class<?> codecInterface, Class<?> payloadInterface, Object type) {
        return Proxy.newProxyInstance(codecInterface.getClassLoader(),
                new Class<?>[]{codecInterface}, new CodecHandler(payloadInterface, type));
    }

    /** The bytes a Retromod payload carries, or null when {@code payload} is someone else's. */
    public static byte[] bytesOf(Object payload) {
        if (payload == null || !Proxy.isProxyClass(payload.getClass())) return null;
        return Proxy.getInvocationHandler(payload) instanceof PayloadHandler handler
                ? handler.data : null;
    }

    /** Whether {@code codec} is a Retromod raw codec, so a registration made with it can be shared. */
    public static boolean isRawCodec(Object codec) {
        return codec != null && Proxy.isProxyClass(codec.getClass())
                && Proxy.getInvocationHandler(codec) instanceof CodecHandler;
    }

    /** Reads and consumes every readable byte of a netty buffer. */
    public static byte[] drain(Object buf) throws ReflectiveOperationException {
        resolveBufferMethods(buf);
        int count = (Integer) readableBytes.invoke(buf);
        byte[] data = new byte[Math.max(0, count)];
        if (count > 0) readBytes.invoke(buf, (Object) data);
        return data;
    }

    private static void write(Object buf, byte[] data) throws ReflectiveOperationException {
        resolveBufferMethods(buf);
        writeBytes.invoke(buf, (Object) data);
    }

    /** Finds the netty buffer methods once, from the loader that owns the buffer. */
    private static void resolveBufferMethods(Object buf) throws ReflectiveOperationException {
        if (writeBytes != null) return;
        Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf", false,
                buf.getClass().getClassLoader());
        readableBytes = byteBuf.getMethod("readableBytes");
        readBytes = byteBuf.getMethod("readBytes", byte[].class);
        writeBytes = byteBuf.getMethod("writeBytes", byte[].class);
    }

    /** Backs one payload: its type answers {@code type()}, and it is equal only to itself. */
    private static final class PayloadHandler implements InvocationHandler {
        final Object type;
        final byte[] data;

        PayloadHandler(Object type, byte[] data) {
            this.type = type;
            this.data = data;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getParameterCount() == 0 && method.getReturnType().isInstance(type)) {
                return type;
            }
            switch (method.getName()) {
                case "type":
                    return type;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args != null ? args[0] : null);
                case "toString":
                    return "RetromodRawPayload";
                default:
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    return null;
            }
        }
    }

    /** Backs one codec: decode reads every remaining byte, encode writes a payload's bytes. */
    private static final class CodecHandler implements InvocationHandler {
        final Class<?> payloadInterface;
        final Object type;

        CodecHandler(Class<?> payloadInterface, Object type) {
            this.payloadInterface = payloadInterface;
            this.type = type;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && method.getReturnType() != Void.TYPE
                    && !method.isDefault()) {
                return newPayload(payloadInterface, type, drain(args[0]));
            }
            if (args != null && args.length == 2 && method.getReturnType() == Void.TYPE) {
                byte[] data = bytesOf(args[1]);
                if (data != null) write(args[0], data);
                return null;
            }
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args != null ? args[0] : null);
                case "toString":
                    return "RetromodRawCodec";
                default:
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    return null;
            }
        }
    }
}
