/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.shim.api.fabric.embedded.RawChannelPayloads;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The host Minecraft and Fabric API calls the legacy channels make, by 26.x name. */
final class HostNet {

    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
    static final String PAYLOAD_TYPE = PAYLOAD + "$Type";
    static final String DISCARDED_PAYLOAD =
            "net/minecraft/network/protocol/common/custom/DiscardedPayload";
    static final String STREAM_CODEC = "net/minecraft/network/codec/StreamCodec";
    static final String FRIENDLY_BUF = "net/minecraft/network/FriendlyByteBuf";
    static final String BYTE_BUF = "io/netty/buffer/ByteBuf";
    static final String UNPOOLED = "io/netty/buffer/Unpooled";
    static final String PACKET = "net/minecraft/network/protocol/Packet";
    static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String GAME_LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    static final String SERVER_CONFIG_LISTENER =
            "net/minecraft/server/network/ServerConfigurationPacketListenerImpl";
    static final String MINECRAFT_SERVER = "net/minecraft/server/MinecraftServer";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String CLIENT_LISTENER = "net/minecraft/client/multiplayer/ClientPacketListener";
    static final String SEND_LISTENER = "net/minecraft/network/PacketSendListener";
    static final String CHANNEL_FUTURE_LISTENER = "io/netty/channel/ChannelFutureListener";
    static final String GENERIC_FUTURE_LISTENER = "io/netty/util/concurrent/GenericFutureListener";
    static final String FUTURE = "io/netty/util/concurrent/Future";

    static final String FABRIC = "net/fabricmc/fabric/api/";
    static final String REGISTRY = FABRIC + "networking/v1/PayloadTypeRegistry";
    static final String SENDER = FABRIC + "networking/v1/PacketSender";
    static final String LOGIN_SENDER = FABRIC + "networking/v1/LoginPacketSender";

    private static final Map<String, Object> TYPES = new ConcurrentHashMap<>();

    private HostNet() {}

    /** Calls a host member, rethrowing what it threw as an unchecked exception. */
    static Object call(Member member, Object... args) {
        MethodHandle handle = HostLink.require(member);
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(member.owner() + "." + member.name() + " failed", e);
        }
    }

    /** The host class for {@code internalName}, or an error that names the missing class. */
    static Class<?> type(String internalName) {
        return HostLink.type(internalName).orElseThrow(() -> new IllegalStateException(
                "This Minecraft host has no " + internalName + "."));
    }

    /** Whether {@code value} is an instance of the host class {@code internalName}. */
    static boolean isInstance(String internalName, Object value) {
        return value != null && HostLink.type(internalName).map(type -> type.isInstance(value))
                .orElse(false);
    }

    /** One {@code CustomPacketPayload.Type} per channel id, shared by every mod. */
    static Object payloadType(Object id) {
        return TYPES.computeIfAbsent(id.toString(), key -> call(
                Member.constructor(PAYLOAD_TYPE, "(L" + IDENTIFIER + ";)V"), id));
    }

    /** A raw payload of {@code type} carrying {@code data}. */
    static Object newPayload(Object type, byte[] data) {
        return RawChannelPayloads.newPayload(type(PAYLOAD), type, data);
    }

    /**
     * A payload on {@code retromod:discarded}, which no registry knows. Minecraft writes such a
     * payload as its id alone, and the other side reads it back as discarded and ignores it.
     */
    static Object discardedPayload() {
        Object id = call(Member.staticMethod(IDENTIFIER, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";"), "retromod", "discarded");
        return call(Member.constructor(DISCARDED_PAYLOAD, "(L" + IDENTIFIER + ";)V"), id);
    }

    /** A raw codec bound to {@code type}. */
    static Object newCodec(Object type) {
        return RawChannelPayloads.newCodec(type(STREAM_CODEC), type(PAYLOAD), type);
    }

    /**
     * The readable bytes of a mod's buffer, left unread. Old Fabric API copied the bytes when it
     * wrote the packet, so a mod could send one buffer to several players.
     */
    static byte[] copyReadable(Object buf) {
        int count = (Integer) call(Member.virtual(BYTE_BUF, "readableBytes", "()I"), buf);
        int start = (Integer) call(Member.virtual(BYTE_BUF, "readerIndex", "()I"), buf);
        byte[] data = new byte[count];
        call(Member.virtual(BYTE_BUF, "getBytes", "(I[B)L" + BYTE_BUF + ";"), buf, start, data);
        return data;
    }

    /** A {@code FriendlyByteBuf} over {@code data}, as a handler receives it. */
    static Object newBuf(byte[] data) {
        Object wrapped = call(Member.staticMethod(UNPOOLED, "wrappedBuffer", "([B)L" + BYTE_BUF + ";"),
                (Object) data);
        return call(Member.constructor(FRIENDLY_BUF, "(L" + BYTE_BUF + ";)V"), wrapped);
    }

    /** An empty {@code FriendlyByteBuf} for a packet to write itself into. */
    static Object newEmptyBuf() {
        Object buffer = call(Member.staticMethod(UNPOOLED, "buffer", "()L" + BYTE_BUF + ";"));
        return call(Member.constructor(FRIENDLY_BUF, "(L" + BYTE_BUF + ";)V"), buffer);
    }

    /** The payload registry for one phase and direction. */
    static Object registry(LegacyChannels.Phase phase, LegacyChannels.Flow flow) {
        String name = (flow == LegacyChannels.Flow.CLIENTBOUND ? "clientbound" : "serverbound")
                + (phase == LegacyChannels.Phase.PLAY ? "Play" : "Configuration");
        return call(Member.staticMethod(REGISTRY, name, "()L" + REGISTRY + ";"));
    }

    /** Registers {@code type} with Retromod's raw codec; throws when the id is taken. */
    static void register(Object registry, Object type) {
        call(Member.virtual(REGISTRY, "register", "(L" + PAYLOAD_TYPE + ";L" + STREAM_CODEC
                + ";)L" + PAYLOAD + "$TypeAndCodec;"), registry, type, newCodec(type));
    }

    /**
     * The codec registered for {@code id}, or null when the host cannot say. Fabric API's
     * registry answers through a public {@code get(Identifier)} on its implementation class.
     */
    static Object registeredCodec(Object registry, Object id) {
        try {
            Method get = registry.getClass().getMethod("get", type(IDENTIFIER));
            Object typeAndCodec = get.invoke(registry, id);
            if (typeAndCodec == null) return null;
            return typeAndCodec.getClass().getMethod("codec").invoke(typeAndCodec);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The server of a play connection, reached through its player's level. */
    static Object serverOf(Object listener) {
        Object player = call(Member.virtual(GAME_LISTENER, "getPlayer", "()L" + SERVER_PLAYER + ";"),
                listener);
        Object level = call(Member.virtual(SERVER_PLAYER, "level", "()L" + SERVER_LEVEL + ";"), player);
        return call(Member.virtual(SERVER_LEVEL, "getServer", "()L" + MINECRAFT_SERVER + ";"), level);
    }

    /** A player's play connection, or null for no player. */
    static Object connectionOf(Object player) {
        if (player == null) return null;
        return call(Member.virtual(SERVER_PLAYER, "connection", "L" + GAME_LISTENER + ";"), player);
    }

    /** The client's play connection, or null when it has none. */
    static Object clientConnection(Object client) {
        if (client == null) return null;
        return call(Member.virtual(MINECRAFT, "getConnection", "()L" + CLIENT_LISTENER + ";"), client);
    }
}
