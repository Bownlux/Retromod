/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The legacy channel helpers against a fake 26.x server and client in one JVM. */
class LegacyChannelsTest {

    private static final String NET = "com.retromod.loaderapi.fabric.v26.net.";
    private static final String SHARED = NET + "shared.";
    private static final String CHANNELS = NET + "LegacyChannels";
    private static final String SENDERS = NET + "LegacyPacketSenders";
    private static final String TYPED = NET + "TypedPackets";
    private static final String WIRE = "net.fabricmc.fabric.impl.networking.FakeWire";
    private static final String REGISTRY = "net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl";
    private static final String OLD_BRIDGE = "com.retromod.shim.api.fabric.embedded.ClientPlayNetworkingV1Bridge";
    private static final String OLD_SERVER_BRIDGE = "com.retromod.polyfill.fabric.embedded.FabricNetworkingBridge";
    private static final String OLD_HANDLERS = "com.retromod.polyfill.fabric.embedded.";

    private FakeHost host;

    @BeforeEach
    void start() throws Exception {
        host = FakeHost.start();
    }

    @AfterEach
    void stop() throws Exception {
        host.close();
    }

    @Test
    @DisplayName("A server and a client exchange raw channel packets, and the server answers through its response sender")
    void rawChannelEcho() throws Throwable {
        Object echo = host.id("retromod-test:echo");
        Object reply = host.id("retromod-test:reply");
        Object serverHandler = handler(SHARED + "ServerPlayChannelHandler", args -> {
            record("server got", args[3]);
            sendRaw(args[4], reply, buf(41));
        });
        Object clientHandler = handler(SHARED + "ClientPlayChannelHandler", args -> record("client got", args[2]));

        assertEquals(true, host.call(CHANNELS, "serverRegisterGlobalReceiver", echo, serverHandler));
        assertEquals(false, host.call(CHANNELS, "serverRegisterGlobalReceiver", echo, serverHandler),
                "a second receiver on one channel is refused, as old Fabric API refused it");
        assertEquals(true, host.call(CHANNELS, "clientRegisterGlobalReceiver", reply, clientHandler));
        host.call(CHANNELS, "clientSend", echo, buf(7));

        assertEquals(List.of("server got 7", "client got 41"), host.events());
        assertEquals(List.of("delivered server-play retromod-test:echo 4",
                "delivered client-play retromod-test:reply 4"), host.wire());
    }

    @Test
    @DisplayName("A receiver registers its channel in both directions at startup, so a reply adds nothing during play")
    void receiverRegistersBothDirectionsAtStartup() throws Throwable {
        Object id = host.id("retromod-test:both-ways");
        host.call(CHANNELS, "serverRegisterGlobalReceiver", id,
                handler(SHARED + "ServerPlayChannelHandler", args -> { }));
        assertNotNull(registered("SERVERBOUND_PLAY", id), "a server receiver needs the serverbound type");
        assertNotNull(registered("CLIENTBOUND_PLAY", id), "the reply direction is registered with it");
        assertNull(registered("CLIENTBOUND_CONFIGURATION", id), "other phases are left alone");
        host.call(CHANNELS, "serverSend", host.field(WIRE, "PLAYER"), id, buf(1));
        assertEquals(Set.of(), lateChannels(), "nothing was registered while players were connected");
    }

    @Test
    @DisplayName("A packet type registers both play directions when it is built")
    void packetTypeRegistersBothPlayDirections() throws Throwable {
        Object id = host.id("retromod-test:typed-at-startup");
        host.call(TYPED, "createType", id, (Function<Object, Object>) buf -> null);
        assertNotNull(registered("SERVERBOUND_PLAY", id));
        assertNotNull(registered("CLIENTBOUND_PLAY", id));
        assertEquals(Set.of(), lateChannels());
    }

    @Test
    @DisplayName("A channel first registered by a send during play is noted once")
    void sendOnlyChannelIsNotedAsLate() throws Throwable {
        Object id = host.id("retromod-test:send-only");
        host.call(CHANNELS, "serverSend", host.field(WIRE, "PLAYER"), id, buf(1));
        host.call(CHANNELS, "serverSend", host.field(WIRE, "PLAYER"), id, buf(2));
        assertNotNull(registered("CLIENTBOUND_PLAY", id), "the send still registers what it needs");
        assertEquals(Set.of("retromod-test:send-only"), lateChannels());
    }

    @Test
    @DisplayName("One buffer sent to two players arrives whole both times")
    void bufferCanBeSentTwice() throws Throwable {
        Object id = host.id("retromod-test:twice");
        host.call(CHANNELS, "clientRegisterGlobalReceiver", id,
                handler(SHARED + "ClientPlayChannelHandler", args -> record("client got", args[2])));
        Object player = host.field(WIRE, "PLAYER");
        Object buf = buf(5);
        host.call(CHANNELS, "serverSend", player, id, buf);
        host.call(CHANNELS, "serverSend", player, id, buf);
        assertEquals(List.of("client got 5", "client got 5"), host.events());
    }

    @Test
    @DisplayName("A send on a channel the other side never registered is dropped, not thrown")
    void unregisteredChannelIsDropped() throws Throwable {
        host.call(CHANNELS, "serverSend", host.field(WIRE, "PLAYER"), host.id("retromod-test:nobody"), buf(1));
        assertEquals(List.of(), host.wire());
    }

    @Test
    @DisplayName("Unregistering returns the old handler, and a receiver another mod registered is put back")
    void unregisterReturnsTheOldHandler() throws Throwable {
        Object id = host.id("retromod-test:unregister");
        Object handler = handler(SHARED + "ServerPlayChannelHandler", args -> { });
        host.call(CHANNELS, "serverRegisterGlobalReceiver", id, handler);
        assertSame(handler, host.call(CHANNELS, "serverUnregisterGlobalReceiver", id));
        assertNull(host.call(CHANNELS, "serverUnregisterGlobalReceiver", id));

        Object nativeHandler = Proxy.newProxyInstance(host.loader(), new Class<?>[]{
                host.type("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayPayloadHandler")},
                (proxy, method, args) -> null);
        host.call("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking", "registerGlobalReceiver",
                payloadType(id), nativeHandler);
        assertNull(host.call(CHANNELS, "serverUnregisterGlobalReceiver", id),
                "another mod's receiver is not an old handler");
        assertSame(nativeHandler, host.call("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking",
                "unregisterGlobalReceiver", id), "and it is registered again");
    }

    @Test
    @DisplayName("Typed packets travel in both play directions and both configuration directions")
    void typedPackets() throws Throwable {
        Object playType = packetType("retromod-test:typed");
        Object configType = packetType("retromod-test:typed-config");
        host.call(TYPED, "serverRegisterGlobalReceiver", playType, handler(SHARED + "ServerPlayPacketHandler",
                args -> host.call("fixture.Recorder", "note", "server typed " + args[0])));
        host.call(TYPED, "clientRegisterGlobalReceiver", playType, handler(SHARED + "ClientPlayPacketHandler",
                args -> host.call("fixture.Recorder", "note", "client typed " + args[0])));
        host.call(TYPED, "configClientRegisterGlobalReceiver", configType,
                handler(SHARED + "ClientConfigurationPacketHandler",
                        args -> host.call("fixture.Recorder", "note", "client config " + args[0])));
        host.call(TYPED, "clientSend", packet(playType, 3));
        host.call(TYPED, "serverSend", host.field(WIRE, "PLAYER"), packet(playType, 4));
        host.call(TYPED, "configServerSend", host.field(WIRE, "SERVER_CONFIG_LISTENER"), packet(configType, 5));
        assertEquals(List.of("server typed packet 3", "client typed packet 4", "client config packet 5"),
                host.events());
        assertEquals(true, host.call(TYPED, "serverCanSend", host.field(WIRE, "PLAYER"), playType));
        assertEquals(true, host.call(TYPED, "serverCanSend", host.field(WIRE, "CONNECTION"), playType),
                "the connection form answers the same as the player form");
    }

    @Test
    @DisplayName("An old server configuration handler, a lambda of the renamed host interface, still receives")
    void oldServerConfigurationLambdaReceives() throws Throwable {
        Object type = packetType("retromod-test:configuration");
        Object handler = oldConfigurationLambda();
        assertEquals(true, host.call(TYPED, "configServerRegisterGlobalReceiver", type, handler));
        host.call(TYPED, "configClientSend", packet(type, 8));
        assertEquals(List.of("old configuration handler got packet 8"), host.events());
        assertSame(handler, host.call(TYPED, "configServerUnregisterGlobalReceiver", type));
    }

    @Test
    @DisplayName("The old bridge's channel and the runtime's channel share one registration, in either order")
    void oldBridgeAndRuntimeShareAChannel() throws Throwable {
        // The old bridge registers both directions with Retromod's raw codec, then the runtime adopts it.
        Object first = host.id("retromod-test:bridge-first");
        host.call(OLD_BRIDGE, "registerGlobalReceiver", first, oldClientHandler("bridge client got"));
        host.call(CHANNELS, "serverRegisterGlobalReceiver", first, handler(SHARED + "ServerPlayChannelHandler",
                args -> record("runtime server got", args[3])));
        host.call(OLD_BRIDGE, "send", first, buf(11));
        host.call(CHANNELS, "serverSend", host.field(WIRE, "PLAYER"), first, buf(12));

        // The runtime registers first, then the old bridge sends and receives on the same channel.
        Object second = host.id("retromod-test:runtime-first");
        host.call(CHANNELS, "serverRegisterGlobalReceiver", second, handler(SHARED + "ServerPlayChannelHandler",
                args -> record("runtime server got", args[3])));
        host.call(CHANNELS, "serverSend", host.field(WIRE, "PLAYER"), second, buf(0));
        host.call(OLD_BRIDGE, "registerGlobalReceiver", second, oldClientHandler("bridge client got"));
        host.call(OLD_BRIDGE, "send", second, buf(21));
        host.call(CHANNELS, "serverSend", host.field(WIRE, "PLAYER"), second, buf(22));

        assertEquals(List.of("runtime server got 11", "bridge client got 12", "runtime server got 21",
                "bridge client got 22"), host.events());
    }

    @Test
    @DisplayName("With the runtime's delegates installed, the old client bridge registers through the runtime")
    void oldBridgeForwardsToTheRuntime() throws Throwable {
        host.call(CHANNELS, "installOldBridgeDelegates");
        Object id = host.id("retromod-test:delegated");
        Object oldHandler = oldClientHandler("delegated client got");
        host.call(OLD_BRIDGE, "registerGlobalReceiver", id, oldHandler);
        host.call(CHANNELS, "serverSend", host.field(WIRE, "PLAYER"), id, buf(9));
        assertEquals(List.of("delegated client got 9"), host.events());
        assertSame(oldHandler, host.call(CHANNELS, "clientUnregisterGlobalReceiver", id),
                "only a receiver the runtime registered gives back the old handler");
    }

    @Test
    @DisplayName("With the runtime's delegates installed, the old server bridge receives and sends through the runtime")
    void oldServerBridgeForwardsToTheRuntime() throws Throwable {
        host.call(CHANNELS, "installOldBridgeDelegates");
        Object request = host.id("retromod-test:old-server-request");
        Object reply = host.id("retromod-test:old-server-reply");
        Object serverHandler = handler(OLD_HANDLERS + "ServerPlayChannelHandler",
                args -> record("old server bridge got", args[3]));
        assertEquals(true, host.call(OLD_SERVER_BRIDGE, "registerServerGlobalReceiver", request, serverHandler));
        assertEquals(true, host.call(OLD_SERVER_BRIDGE, "registerClientGlobalReceiver", reply,
                handler(OLD_HANDLERS + "ClientPlayChannelHandler", args -> record("old client bridge got", args[2]))));
        host.call(CHANNELS, "clientSend", request, buf(13));
        host.call(OLD_SERVER_BRIDGE, "serverSend", host.field(WIRE, "PLAYER"), reply, buf(14));
        host.call(OLD_SERVER_BRIDGE, "clientSend", request, buf(15));
        assertEquals(List.of("old server bridge got 13", "old client bridge got 14", "old server bridge got 15"),
                host.events());
        assertSame(serverHandler, host.call(CHANNELS, "serverUnregisterGlobalReceiver", request));
    }

    @Test
    @DisplayName("A channel another mod registered with its own payload type is left alone")
    void foreignChannelIsLeftAlone() throws Throwable {
        Object id = host.id("retromod-test:taken");
        Object type = payloadType(id);
        Object nativeCodec = Proxy.newProxyInstance(host.loader(),
                new Class<?>[]{host.type("net.minecraft.network.codec.StreamCodec")},
                (proxy, method, args) -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null);
        for (String registry : List.of("SERVERBOUND_PLAY", "CLIENTBOUND_PLAY")) {
            Object impl = host.field(REGISTRY, registry);
            impl.getClass().getMethod("register", host.type("net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type"),
                    host.type("net.minecraft.network.codec.StreamCodec")).invoke(impl, type, nativeCodec);
        }
        assertEquals(false, host.call(CHANNELS, "serverRegisterGlobalReceiver", id,
                handler(SHARED + "ServerPlayChannelHandler", args -> { })));
        host.call(CHANNELS, "serverSend", host.field(WIRE, "PLAYER"), id, buf(1));
        assertEquals(List.of(), host.wire(), "the old mod's packet is dropped");
        Object codec = registered("SERVERBOUND_PLAY", id).getClass().getMethod("codec")
                .invoke(registered("SERVERBOUND_PLAY", id));
        assertSame(nativeCodec, codec, "the other mod keeps its registration");
    }

    @Test
    @DisplayName("A lambda from a jar translated before the runtime, built with the old typed method, still receives")
    void oldBridgeTypedLambdaReceives() throws Throwable {
        host.call(CHANNELS, "installOldBridgeDelegates");
        Object id = host.id("retromod-test:old-typed-lambda");
        assertEquals(true, host.call(OLD_SERVER_BRIDGE, "registerServerGlobalReceiver", id, oldTypedServerLambda()));
        host.call(CHANNELS, "clientSend", id, buf(16));
        assertEquals(List.of("old typed lambda got 16"), host.events());
    }

    @Test
    @DisplayName("A packet built on a channel another mod owns carries an ignored payload, not one its codec cannot write")
    void foreignChannelBuildsADiscardedPacket() throws Throwable {
        Object id = host.id("retromod-test:taken-create");
        registerForeign(id, "SERVERBOUND_PLAY", "CLIENTBOUND_PLAY");

        Object packet = host.call(CHANNELS, "serverCreatePacket", id, buf(1));
        assertEquals("retromod:discarded", payloadId(packet));
        Object sender = host.field(WIRE, "SERVER_PLAY_SENDER");
        Object raw = host.call(SENDERS, "createRawPacket", sender, id, buf(2));
        assertEquals("retromod:discarded", payloadId(raw));

        Class<?> packetType = host.type("net.minecraft.network.protocol.Packet");
        Class<?> listenerType = host.type("io.netty.channel.ChannelFutureListener");
        Method send = host.type("net.fabricmc.fabric.api.networking.v1.PacketSender")
                .getMethod("sendPacket", packetType, listenerType);
        send.invoke(sender, packet, null);
        send.invoke(sender, raw, null);
        assertEquals(List.of("discarded client-play retromod:discarded",
                "discarded client-play retromod:discarded"), host.wire(),
                "the other mod's codec is never asked to write Retromod's payload");
    }

    @Test
    @DisplayName("A native mod that registers a channel after an old mod used it is refused, as Fabric API refuses any duplicate")
    void nativeRegistrationAfterAnOldModIsRefused() throws Throwable {
        Object id = host.id("retromod-test:old-first");
        host.call(CHANNELS, "serverRegisterGlobalReceiver", id,
                handler(SHARED + "ServerPlayChannelHandler", args -> { }));
        InvocationTargetException refused = assertThrows(InvocationTargetException.class,
                () -> registerForeign(id, "SERVERBOUND_PLAY"));
        assertTrue(refused.getCause() instanceof IllegalArgumentException, String.valueOf(refused.getCause()));
    }

    @Test
    @DisplayName("A raw send through a login sender stays a login query")
    void loginSenderStaysRaw() throws Throwable {
        host.call(SENDERS, "sendRaw", host.field(WIRE, "LOGIN_SENDER"), host.id("retromod-test:login"), buf(3));
        assertEquals(List.of("login retromod-test:login 4"), host.wire());
    }

    @Test
    @DisplayName("Old listeners reach the host: a netty listener is wrapped, and thenRun's stand-in maps back")
    void oldListenersRun() throws Throwable {
        Object id = host.id("retromod-test:listened");
        host.call(CHANNELS, "clientRegisterGlobalReceiver", id,
                handler(SHARED + "ClientPlayChannelHandler", args -> { }));
        Object sender = host.field(WIRE, "SERVER_PLAY_SENDER");
        Object nettyListener = Proxy.newProxyInstance(host.loader(),
                new Class<?>[]{host.type("io.netty.util.concurrent.GenericFutureListener")},
                (proxy, method, args) -> {
                    if (!method.getName().equals("operationComplete")) {
                        return method.getName().equals("hashCode") ? 0 : proxy == args[0];
                    }
                    host.call("fixture.Recorder", "note", "netty listener ran");
                    return null;
                });
        host.call(SENDERS, "sendRawWithListener", sender, id, buf(1), nettyListener);
        Runnable runnable = () -> {
            try {
                host.call("fixture.Recorder", "note", "thenRun ran");
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
        };
        Object standIn = host.call(NET + "SendListeners", "thenRun", runnable);
        assertTrue(host.type("net.minecraft.network.PacketSendListener").isInstance(standIn),
                "the stand-in must pass the mod's checkcast to PacketSendListener");
        host.call(SENDERS, "sendRawWithListener", sender, id, buf(2), standIn);
        assertEquals(List.of("netty listener ran", "thenRun ran"), host.events());
    }

    @Test
    @DisplayName("A sender Retromod did not hand out is placed by its Fabric API class name")
    void senderRoleFromClassName() throws Throwable {
        Object id = host.id("retromod-test:config-sender");
        host.call(SENDERS, "sendRaw", host.field(WIRE, "CLIENT_CONFIG_SENDER"), id, buf(1));
        assertNotNull(registered("SERVERBOUND_CONFIGURATION", id),
                "a client configuration sender sends serverbound configuration payloads");
        assertNull(registered("CLIENTBOUND_PLAY", id));
    }

    // ---- Helpers --------------------------------------------------------------------------

    /**
     * What a translated 1.20.2 mod builds: a lambda for the host's
     * {@code ServerConfigurationNetworking.ConfigurationPacketHandler} that still implements the old
     * three-argument method, which the host interface no longer declares.
     */
    private Object oldConfigurationLambda() throws Throwable {
        String owner = "fixture/OldConfiguration";
        String iface = "net/fabricmc/fabric/api/networking/v1/ServerConfigurationNetworking$ConfigurationPacketHandler";
        String sam = "(Lcom/retromod/loaderapi/fabric/v26/net/shared/LegacyFabricPacket;"
                + "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;"
                + "Lnet/fabricmc/fabric/api/networking/v1/PacketSender;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null);
        MethodVisitor make = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()L" + iface + ";", null, null);
        make.visitCode();
        make.visitInvokeDynamicInsn("receive", "()L" + iface + ";", new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;"
                + "Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getType(sam), new Handle(Opcodes.H_INVOKESTATIC, owner, "body", sam, false),
                org.objectweb.asm.Type.getType(sam));
        make.visitInsn(Opcodes.ARETURN);
        make.visitMaxs(0, 0);
        make.visitEnd();
        MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "body",
                sam, null, null);
        body.visitCode();
        body.visitLdcInsn("old configuration handler got ");
        body.visitVarInsn(Opcodes.ALOAD, 0);
        body.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        body.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        body.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/Recorder", "note", "(Ljava/lang/String;)V", false);
        body.visitInsn(Opcodes.RETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        cw.visitEnd();
        Class<?> fixture = Class.forName("fixture.OldConfiguration", true,
                host.defineMod(Map.of(owner, cw.toByteArray())));
        return fixture.getMethod("make").invoke(null);
    }

    /**
     * What a jar translated before the runtime builds for an old server receiver: a lambda of the
     * polyfill handler type whose method keeps the old typed descriptor, which that interface does
     * not declare.
     */
    private Object oldTypedServerLambda() throws Throwable {
        String owner = "fixture/OldTypedLambda";
        String iface = "com/retromod/polyfill/fabric/embedded/ServerPlayChannelHandler";
        String sam = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;"
                + "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/network/FriendlyByteBuf;"
                + "Lnet/fabricmc/fabric/api/networking/v1/PacketSender;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null);
        MethodVisitor make = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()L" + iface + ";", null, null);
        make.visitCode();
        make.visitInvokeDynamicInsn("receive", "()L" + iface + ";", new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;"
                + "Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getType(sam), new Handle(Opcodes.H_INVOKESTATIC, owner, "body", sam, false),
                org.objectweb.asm.Type.getType(sam));
        make.visitInsn(Opcodes.ARETURN);
        make.visitMaxs(0, 0);
        make.visitEnd();
        MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "body",
                sam, null, null);
        body.visitCode();
        body.visitLdcInsn("old typed lambda got");
        body.visitVarInsn(Opcodes.ALOAD, 3);
        body.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/Recorder", "record", "(Ljava/lang/String;Lio/netty/buffer/ByteBuf;)V", false);
        body.visitInsn(Opcodes.RETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        cw.visitEnd();
        Class<?> fixture = Class.forName("fixture.OldTypedLambda", true, host.defineMod(Map.of(owner, cw.toByteArray())));
        return fixture.getMethod("make").invoke(null);
    }

    /** A handler that throws nothing the test does not expect. */
    @FunctionalInterface
    interface Body {
        void run(Object[] args) throws Throwable;
    }

    /** A handler of a one-method interface; Object's own methods answer for the proxy. */
    private Object handler(String type, Body body) throws ClassNotFoundException {
        return Proxy.newProxyInstance(host.loader(), new Class<?>[]{host.type(type)},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() != Object.class) {
                        body.run(args);
                        return null;
                    }
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> type;
                    };
                });
    }

    /** An old client handler as the old bridge sees it: a jar translated before the runtime. */
    private Object oldClientHandler(String label) throws ClassNotFoundException {
        return handler(OLD_HANDLERS + "ClientPlayChannelHandler", args -> record(label, args[2]));
    }

    /** Registers {@code id} the way another mod would, with a codec that fails on any payload but its own. */
    private void registerForeign(Object id, String... registries) throws Throwable {
        Object type = payloadType(id);
        Object strictCodec = Proxy.newProxyInstance(host.loader(),
                new Class<?>[]{host.type("net.minecraft.network.codec.StreamCodec")},
                (proxy, method, args) -> {
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "another mod's codec";
                        default -> throw new ClassCastException("another mod's codec cannot handle "
                                + java.util.Arrays.toString(args));
                    };
                });
        for (String registry : registries) {
            Object impl = host.field(REGISTRY, registry);
            impl.getClass().getMethod("register", host.type("net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type"),
                    host.type("net.minecraft.network.codec.StreamCodec")).invoke(impl, type, strictCodec);
        }
    }

    /** The channel id of the payload a fake packet carries. */
    private String payloadId(Object packet) throws ReflectiveOperationException {
        Object payload = packet.getClass().getMethod("payload").invoke(packet);
        Class<?> payloadInterface = host.type("net.minecraft.network.protocol.common.custom.CustomPacketPayload");
        Object payloadType = payloadInterface.getMethod("type").invoke(payload);
        return String.valueOf(payloadType.getClass().getMethod("id").invoke(payloadType));
    }

    @SuppressWarnings("unchecked")
    private Set<String> lateChannels() throws ReflectiveOperationException {
        Method late = host.type(CHANNELS).getDeclaredMethod("lateChannels");
        late.setAccessible(true);
        return (Set<String>) late.invoke(null);
    }

    /** A host payload type, built the way another mod would build its own. */
    private Object payloadType(Object id) throws ReflectiveOperationException {
        return host.type("net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type")
                .getConstructor(host.type("net.minecraft.resources.Identifier")).newInstance(id);
    }

    private Object buf(int value) throws Throwable {
        return host.call("fixture.Recorder", "buf", value);
    }

    private void record(String label, Object buf) throws Throwable {
        host.call("fixture.Recorder", "record", label, buf);
    }

    private void sendRaw(Object sender, Object id, Object buf) throws Throwable {
        host.call(SENDERS, "sendRaw", sender, id, buf);
    }

    private Object registered(String registry, Object id) throws Throwable {
        Object impl = host.field(REGISTRY, registry);
        return impl.getClass().getMethod("get", host.type("net.minecraft.resources.Identifier")).invoke(impl, id);
    }

    /** A packet type whose packets are an int that reads back as {@code packet <n>}. */
    private Object packetType(String id) throws Throwable {
        Function<Object, Object> reader = buf -> {
            try {
                Object value = buf.getClass().getMethod("readInt").invoke(buf);
                return Proxy.newProxyInstance(host.loader(), new Class<?>[]{
                        host.type(SHARED + "LegacyFabricPacket")}, packetBody(null, (Integer) value));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        };
        return host.call(SHARED + "LegacyPacketType", "create", host.id(id), reader);
    }

    private Object packet(Object type, int value) throws ClassNotFoundException {
        return Proxy.newProxyInstance(host.loader(), new Class<?>[]{host.type(SHARED + "LegacyFabricPacket")},
                packetBody(type, value));
    }

    private static InvocationHandler packetBody(Object type, int value) {
        return (proxy, method, args) -> switch (method.getName()) {
            case "writeLegacyPacket" -> args[0].getClass().getMethod("writeInt", int.class).invoke(args[0], value);
            case "getType" -> type;
            case "toString" -> "packet " + value;
            default -> null;
        };
    }
}
