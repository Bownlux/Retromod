/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.loaderapi.fabric.v26.net.LegacyChannels.Flow;
import com.retromod.loaderapi.fabric.v26.net.LegacyChannels.Phase;
import com.retromod.loaderapi.fabric.v26.net.shared.ClientConfigurationPacketHandler;
import com.retromod.loaderapi.fabric.v26.net.shared.ClientPlayPacketHandler;
import com.retromod.loaderapi.fabric.v26.net.shared.LegacyFabricPacket;
import com.retromod.loaderapi.fabric.v26.net.shared.LegacyPacketType;
import com.retromod.loaderapi.fabric.v26.net.shared.ServerPlayPacketHandler;

import java.util.function.Function;

/** Old typed packets ({@code FabricPacket}, {@code PacketType}, 1.19.4 to 1.20.4) on 26.x. */
public final class TypedPackets {

    private TypedPackets() {}

    /**
     * Old {@code PacketType.create(Identifier, Function)}. A mod builds its types in static
     * initializers, usually at startup, so both play directions are registered here before anyone
     * connects. A configuration-phase type is registered when it is first used.
     */
    public static LegacyPacketType<?> createType(Object id, Function<?, ?> reader) {
        LegacyPacketType<?> type = LegacyPacketType.create(id, reader);
        LegacyChannels.claim(Phase.PLAY, Flow.CLIENTBOUND, id);
        LegacyChannels.claim(Phase.PLAY, Flow.SERVERBOUND, id);
        return type;
    }

    // ---- Server play -----------------------------------------------------------------------

    /** Old {@code ServerPlayNetworking.registerGlobalReceiver(PacketType, PlayPacketHandler)}. */
    public static boolean serverRegisterGlobalReceiver(Object type, Object handler) {
        return register(Endpoint.SERVER_PLAY, null, type, handler);
    }

    /** Old {@code ServerPlayNetworking.registerReceiver(ServerGamePacketListenerImpl, PacketType, PlayPacketHandler)}. */
    public static boolean serverRegisterReceiver(Object listener, Object type, Object handler) {
        return register(Endpoint.SERVER_PLAY, listener, type, handler);
    }

    /** Old {@code ServerPlayNetworking.unregisterGlobalReceiver(PacketType)}. */
    public static Object serverUnregisterGlobalReceiver(Object type) {
        return LegacyChannels.unregisterReceiver(Endpoint.SERVER_PLAY, null, idOf(type));
    }

    /** Old {@code ServerPlayNetworking.unregisterReceiver(ServerGamePacketListenerImpl, PacketType)}. */
    public static Object serverUnregisterReceiver(Object listener, Object type) {
        return LegacyChannels.unregisterReceiver(Endpoint.SERVER_PLAY, listener, idOf(type));
    }

    /** Old {@code ServerPlayNetworking.send(ServerPlayer, FabricPacket)}. */
    public static void serverSend(Object player, Object packet) {
        LegacyChannels.send(Endpoint.SERVER_PLAY, player, channelOf(packet), encode(packet));
    }

    /** Old {@code ServerPlayNetworking.createS2CPacket(FabricPacket)}. */
    public static Object serverCreatePacket(Object packet) {
        return LegacyChannels.createPacket(Endpoint.SERVER_PLAY, channelOf(packet), encode(packet));
    }

    /** Old {@code ServerPlayNetworking.canSend(ServerPlayer or ServerGamePacketListenerImpl, PacketType)}. */
    public static boolean serverCanSend(Object target, Object type) {
        return LegacyChannels.canSend(Endpoint.SERVER_PLAY, target, idOf(type));
    }

    // ---- Client play -----------------------------------------------------------------------

    /** Old {@code ClientPlayNetworking.registerGlobalReceiver(PacketType, PlayPacketHandler)}. */
    public static boolean clientRegisterGlobalReceiver(Object type, Object handler) {
        return register(Endpoint.CLIENT_PLAY, null, type, handler);
    }

    /** Old {@code ClientPlayNetworking.registerReceiver(PacketType, PlayPacketHandler)}. */
    public static boolean clientRegisterReceiver(Object type, Object handler) {
        return register(Endpoint.CLIENT_PLAY, LegacyChannels.NO_LISTENER, type, handler);
    }

    /** Old {@code ClientPlayNetworking.unregisterGlobalReceiver(PacketType)}. */
    public static Object clientUnregisterGlobalReceiver(Object type) {
        return LegacyChannels.unregisterReceiver(Endpoint.CLIENT_PLAY, null, idOf(type));
    }

    /** Old {@code ClientPlayNetworking.unregisterReceiver(PacketType)}. */
    public static Object clientUnregisterReceiver(Object type) {
        return LegacyChannels.unregisterReceiver(Endpoint.CLIENT_PLAY, LegacyChannels.NO_LISTENER,
                idOf(type));
    }

    /** Old {@code ClientPlayNetworking.send(FabricPacket)}. */
    public static void clientSend(Object packet) {
        LegacyChannels.send(Endpoint.CLIENT_PLAY, null, channelOf(packet), encode(packet));
    }

    /** Old {@code ClientPlayNetworking.createC2SPacket(FabricPacket)}. */
    public static Object clientCreatePacket(Object packet) {
        return LegacyChannels.createPacket(Endpoint.CLIENT_PLAY, channelOf(packet), encode(packet));
    }

    /** Old {@code ClientPlayNetworking.canSend(PacketType)}. */
    public static boolean clientCanSend(Object type) {
        return LegacyChannels.canSend(Endpoint.CLIENT_PLAY, null, idOf(type));
    }

    // ---- Server configuration (1.20.2 to 1.20.4) ------------------------------------------

    /** Old {@code ServerConfigurationNetworking.registerGlobalReceiver(PacketType, ConfigurationPacketHandler)}. */
    public static boolean configServerRegisterGlobalReceiver(Object type, Object handler) {
        return register(Endpoint.SERVER_CONFIGURATION, null, type, handler);
    }

    /** Old {@code ServerConfigurationNetworking.registerReceiver(listener, PacketType, ConfigurationPacketHandler)}. */
    public static boolean configServerRegisterReceiver(Object listener, Object type, Object handler) {
        return register(Endpoint.SERVER_CONFIGURATION, listener, type, handler);
    }

    /** Old {@code ServerConfigurationNetworking.unregisterGlobalReceiver(PacketType)}. */
    public static Object configServerUnregisterGlobalReceiver(Object type) {
        return LegacyChannels.unregisterReceiver(Endpoint.SERVER_CONFIGURATION, null, idOf(type));
    }

    /** Old {@code ServerConfigurationNetworking.unregisterReceiver(listener, PacketType)}. */
    public static Object configServerUnregisterReceiver(Object listener, Object type) {
        return LegacyChannels.unregisterReceiver(Endpoint.SERVER_CONFIGURATION, listener, idOf(type));
    }

    /** Old {@code ServerConfigurationNetworking.send(listener, FabricPacket)}. */
    public static void configServerSend(Object listener, Object packet) {
        LegacyChannels.send(Endpoint.SERVER_CONFIGURATION, listener, channelOf(packet),
                encode(packet));
    }

    /** Old {@code ServerConfigurationNetworking.createS2CPacket(FabricPacket)}. */
    public static Object configServerCreatePacket(Object packet) {
        return LegacyChannels.createPacket(Endpoint.SERVER_CONFIGURATION, channelOf(packet),
                encode(packet));
    }

    /** Old {@code ServerConfigurationNetworking.canSend(listener, PacketType)}. */
    public static boolean configServerCanSend(Object listener, Object type) {
        return LegacyChannels.canSend(Endpoint.SERVER_CONFIGURATION, listener, idOf(type));
    }

    /**
     * A mod's own call to the old {@code ConfigurationPacketHandler.receive(FabricPacket, listener,
     * PacketSender)}. The host interface kept its name with a new method, so the old method is
     * reached by reflection.
     */
    public static void configServerReceive(Object handler, Object packet, Object listener,
            Object sender) throws Throwable {
        ReceiverAdapters.invokeReceive(handler, packet, listener, sender);
    }

    // ---- Client configuration (1.20.2 to 1.20.4) ------------------------------------------

    /** Old {@code ClientConfigurationNetworking.registerGlobalReceiver(PacketType, ConfigurationPacketHandler)}. */
    public static boolean configClientRegisterGlobalReceiver(Object type, Object handler) {
        return register(Endpoint.CLIENT_CONFIGURATION, null, type, handler);
    }

    /** Old {@code ClientConfigurationNetworking.registerReceiver(PacketType, ConfigurationPacketHandler)}. */
    public static boolean configClientRegisterReceiver(Object type, Object handler) {
        return register(Endpoint.CLIENT_CONFIGURATION, LegacyChannels.NO_LISTENER, type, handler);
    }

    /** Old {@code ClientConfigurationNetworking.unregisterGlobalReceiver(PacketType)}. */
    public static Object configClientUnregisterGlobalReceiver(Object type) {
        return LegacyChannels.unregisterReceiver(Endpoint.CLIENT_CONFIGURATION, null, idOf(type));
    }

    /** Old {@code ClientConfigurationNetworking.unregisterReceiver(PacketType)}. */
    public static Object configClientUnregisterReceiver(Object type) {
        return LegacyChannels.unregisterReceiver(Endpoint.CLIENT_CONFIGURATION,
                LegacyChannels.NO_LISTENER, idOf(type));
    }

    /** Old {@code ClientConfigurationNetworking.send(FabricPacket)}. */
    public static void configClientSend(Object packet) {
        LegacyChannels.send(Endpoint.CLIENT_CONFIGURATION, null, channelOf(packet), encode(packet));
    }

    /** Old {@code ClientConfigurationNetworking.canSend(PacketType)}. */
    public static boolean configClientCanSend(Object type) {
        return LegacyChannels.canSend(Endpoint.CLIENT_CONFIGURATION, null, idOf(type));
    }

    // ---- PacketSender ------------------------------------------------------------------------

    /** Old {@code PacketSender.sendPacket(FabricPacket)}. */
    public static void senderSend(Object sender, Object packet) {
        senderSendWithListener(sender, packet, null);
    }

    /** Old {@code PacketSender.sendPacket(FabricPacket, listener)}, either listener type. */
    public static void senderSendWithListener(Object sender, Object packet, Object listener) {
        Object payload = LegacyPacketSenders.payloadFor(sender, channelOf(packet), encode(packet));
        if (payload != null) {
            LegacyPacketSenders.sendPayload(sender, payload, SendListeners.toHost(listener));
        }
    }

    /** Old {@code PacketSender.createPacket(FabricPacket)}. */
    public static Object senderCreatePacket(Object sender, Object packet) {
        Object buf = HostNet.newEmptyBuf();
        ((LegacyFabricPacket) packet).writeLegacyPacket(buf);
        return LegacyPacketSenders.createRawPacket(sender, channelOf(packet), buf);
    }

    // ---- Shared steps -----------------------------------------------------------------------

    /** Registers a receiver that reads each payload with the type's reader before the handler sees it. */
    private static boolean register(Endpoint endpoint, Object listener, Object typeObject,
            Object handler) {
        LegacyPacketType<?> type = (LegacyPacketType<?>) typeObject;
        return LegacyChannels.registerReceiver(endpoint, listener, type.getId(), handler,
                (payload, context) -> {
                    Object packet = type.read(HostNet.newBuf(ReceiverAdapters.bytesOf(payload)));
                    deliver(endpoint, handler, packet, context);
                });
    }

    /** Hands a read packet to an old handler with the arguments its side used to get. */
    private static void deliver(Endpoint endpoint, Object handler, Object packet, Object context)
            throws Throwable {
        Phase phase = endpoint.phase;
        Flow replies = endpoint.sends();
        Object sender = SenderRoles.remember(HostNet.call(endpoint.context("responseSender",
                HostNet.SENDER), context), phase, replies);
        switch (endpoint) {
            case SERVER_PLAY -> {
                Object player = HostNet.call(endpoint.context("player", HostNet.SERVER_PLAYER), context);
                if (handler instanceof ServerPlayPacketHandler<?> typed) {
                    typed.receiveServerPlayPacket(packet, player, sender);
                } else {
                    ReceiverAdapters.invokeReceive(handler, packet, player, sender);
                }
            }
            case CLIENT_PLAY -> {
                Object player = HostNet.call(endpoint.context("player",
                        "net/minecraft/client/player/LocalPlayer"), context);
                if (handler instanceof ClientPlayPacketHandler<?> typed) {
                    typed.receiveClientPlayPacket(packet, player, sender);
                } else {
                    ReceiverAdapters.invokeReceive(handler, packet, player, sender);
                }
            }
            case SERVER_CONFIGURATION -> {
                Object listener = HostNet.call(endpoint.context("packetListener",
                        HostNet.SERVER_CONFIG_LISTENER), context);
                ReceiverAdapters.invokeReceive(handler, packet, listener, sender);
            }
            case CLIENT_CONFIGURATION -> {
                if (handler instanceof ClientConfigurationPacketHandler<?> typed) {
                    typed.receiveClientConfigurationPacket(packet, sender);
                } else {
                    ReceiverAdapters.invokeReceive(handler, packet, sender);
                }
            }
        }
    }

    private static Object idOf(Object type) {
        return ((LegacyPacketType<?>) type).getId();
    }

    // Each packet travels as a raw payload on its type's channel, as it did on the wire before.
    private static Object channelOf(Object packet) {
        return ((LegacyFabricPacket) packet).getType().getId();
    }

    /** The bytes a packet writes, as old Fabric API put them on the wire. */
    private static byte[] encode(Object packet) {
        Object buf = HostNet.newEmptyBuf();
        ((LegacyFabricPacket) packet).writeLegacyPacket(buf);
        return HostNet.copyReadable(buf);
    }
}
