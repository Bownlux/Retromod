/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Delivers one packet to a server receiver through the host's own receiver and payload codec. */
final class N1Loopback {

    // This mod compiles against 1.20.1, so it reaches the 26.x networking API by reflection.
    private static final String REGISTRY = "net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry";
    private static final String PAYLOAD = "net.minecraft.network.protocol.common.custom.CustomPacketPayload";
    private static final String HANDLER = "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayPayloadHandler";
    private static final String CONTEXT = "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$Context";

    private N1Loopback() {}

    /** What a fake response sender was asked to send, and the listeners it completed. */
    static final class Capture {
        final List<Object> sent = new ArrayList<>();
        final PacketSender sender;

        Capture() {
            this.sender = (PacketSender) Proxy.newProxyInstance(N1Loopback.class.getClassLoader(),
                    new Class<?>[]{PacketSender.class}, handler());
        }

        private InvocationHandler handler() {
            return (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPacket" -> {
                        sent.add(args[0]);
                        // A listener argument is completed as if the packet left successfully.
                        if (args.length == 2 && args[1] != null) complete(args[1]);
                        return null;
                    }
                    case "createPacket" -> {
                        return createClientboundPacket(args[0]);
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "toString" -> {
                        return "N1CaptureSender";
                    }
                    default -> {
                        return method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
                    }
                }
            };
        }

        /** The bytes of the last payload sent on {@code channel}, as the client would read them. */
        byte[] lastBytes(Identifier channel) throws Exception {
            for (int i = sent.size() - 1; i >= 0; i--) {
                Object payload = sent.get(i);
                if (!Class.forName(PAYLOAD).isInstance(payload)) continue;
                return encode(codec("clientboundPlay", channel), payload);
            }
            throw new IllegalStateException("nothing was sent on " + channel + "; sent " + sent);
        }
    }

    /** Hands {@code data} on {@code channel} to the host's registered receiver, from {@code player}. */
    static Capture receive(MinecraftServer server, ServerPlayerEntity player, Identifier channel, byte[] data)
            throws Exception {
        Object hostHandler = takeHostHandler(channel);
        Object payload = decode(codec("serverboundPlay", channel), data);
        Capture capture = new Capture();
        Object context = Proxy.newProxyInstance(N1Loopback.class.getClassLoader(),
                new Class<?>[]{Class.forName(CONTEXT)}, (proxy, method, args) -> switch (method.getName()) {
                    case "server" -> server;
                    case "player" -> player;
                    case "responseSender" -> capture.sender;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        Method receive = Class.forName(HANDLER).getMethod("receive", Class.forName(PAYLOAD),
                Class.forName(CONTEXT));
        try {
            receive.invoke(hostHandler, payload, context);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("the receiver threw " + e.getCause(), e.getCause());
        }
        return capture;
    }

    /**
     * The host's receiver for {@code channel}, read by unregistering it and registering it again.
     * The 26.x method takes the channel id and returns the host's handler type.
     */
    private static Object takeHostHandler(Identifier channel) throws Exception {
        Method unregister = null;
        Method register = null;
        for (Method method : ServerPlayNetworking.class.getMethods()) {
            if (method.getName().equals("unregisterGlobalReceiver") && method.getParameterCount() == 1
                    && method.getReturnType().getName().equals(HANDLER)) unregister = method;
            if (method.getName().equals("registerGlobalReceiver") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0].getName().equals(PAYLOAD + "$Type")) register = method;
        }
        if (unregister == null || register == null) {
            throw new IllegalStateException("this host has no 26.x ServerPlayNetworking");
        }
        Object handler = unregister.invoke(null, channel);
        if (handler == null) throw new IllegalStateException("no host receiver on " + channel);
        Object type = Class.forName(PAYLOAD + "$Type").getConstructor(Identifier.class).newInstance(channel);
        register.invoke(null, type, handler);
        return handler;
    }

    private static Object codec(String registryName, Identifier channel) throws Exception {
        Object registry = Class.forName(REGISTRY).getMethod(registryName).invoke(null);
        Object typeAndCodec = registry.getClass().getMethod("get", Identifier.class).invoke(registry, channel);
        if (typeAndCodec == null) {
            throw new IllegalStateException("no " + registryName + " payload type for " + channel);
        }
        return typeAndCodec.getClass().getMethod("codec").invoke(typeAndCodec);
    }

    private static Object decode(Object codec, byte[] data) throws Exception {
        return Class.forName("net.minecraft.network.codec.StreamDecoder").getMethod("decode", Object.class)
                .invoke(codec, new PacketByteBuf(Unpooled.wrappedBuffer(data)));
    }

    private static byte[] encode(Object codec, Object payload) throws Exception {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        Class.forName("net.minecraft.network.codec.StreamEncoder").getMethod("encode", Object.class, Object.class)
                .invoke(codec, buf, payload);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    private static Object createClientboundPacket(Object payload) throws Exception {
        for (Method method : ServerPlayNetworking.class.getMethods()) {
            if (method.getName().equals("createClientboundPacket") && method.getParameterCount() == 1) {
                return method.invoke(null, payload);
            }
        }
        throw new IllegalStateException("this host has no createClientboundPacket");
    }

    /**
     * Makes {@code connection} claim {@code channel}, as a client that registered a receiver for it
     * would, so a send to its player takes the host's own send path. A fake player's connection then
     * drops the packet. This reaches Fabric API's networking internals by reflection.
     */
    @SuppressWarnings("unchecked")
    static void markSendable(ServerPlayNetworkHandler connection, Identifier channel) throws Exception {
        Object addon = null;
        for (Method method : Class.forName("net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl")
                .getMethods()) {
            if (method.getName().equals("getAddon") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(connection)) {
                addon = method.invoke(null, connection);
            }
        }
        if (addon == null) throw new IllegalStateException("no networking addon for " + connection);
        Field sendable = Class.forName("net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon")
                .getDeclaredField("sendableChannels");
        sendable.setAccessible(true);
        ((Set<Object>) sendable.get(addon)).add(channel);
    }

    /** Completes a netty listener with a succeeded future, as a delivered packet would. */
    private static void complete(Object listener) throws Exception {
        Object future = new EmbeddedChannel().newSucceededFuture();
        Class<?> listenerType = Class.forName("io.netty.util.concurrent.GenericFutureListener");
        listenerType.getMethod("operationComplete", Class.forName("io.netty.util.concurrent.Future"))
                .invoke(listener, future);
    }

    static byte[] intBytes(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    static int readInt(byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes).readInt();
    }

    static PacketByteBuf buf(int value) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(value);
        return buf;
    }
}
