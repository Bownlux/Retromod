/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.loaderapi.fabric.v26.net.LegacyChannels.Flow;
import com.retromod.loaderapi.fabric.v26.net.LegacyChannels.Phase;

import static com.retromod.loaderapi.fabric.v26.net.HostNet.FABRIC;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.IDENTIFIER;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.PACKET;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.PAYLOAD;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.PAYLOAD_TYPE;

/** One side of one connection phase, with the host Fabric API networking class that serves it. */
enum Endpoint {

    SERVER_PLAY(Phase.PLAY, true, FABRIC + "networking/v1/ServerPlayNetworking",
            "$PlayPayloadHandler", "net/minecraft/server/network/ServerGamePacketListenerImpl"),
    CLIENT_PLAY(Phase.PLAY, false, FABRIC + "client/networking/v1/ClientPlayNetworking",
            "$PlayPayloadHandler", null),
    SERVER_CONFIGURATION(Phase.CONFIGURATION, true,
            FABRIC + "networking/v1/ServerConfigurationNetworking", "$ConfigurationPacketHandler",
            "net/minecraft/server/network/ServerConfigurationPacketListenerImpl"),
    CLIENT_CONFIGURATION(Phase.CONFIGURATION, false,
            FABRIC + "client/networking/v1/ClientConfigurationNetworking",
            "$ConfigurationPayloadHandler", null);

    final Phase phase;
    final boolean server;
    final String networking;
    final String handler;
    final String listener;

    Endpoint(Phase phase, boolean server, String networking, String handlerSuffix, String listener) {
        this.phase = phase;
        this.server = server;
        this.networking = networking;
        this.handler = networking + handlerSuffix;
        this.listener = listener;
    }

    /** The direction this side receives. */
    Flow receives() {
        return server ? Flow.SERVERBOUND : Flow.CLIENTBOUND;
    }

    /** The direction this side sends. */
    Flow sends() {
        return server ? Flow.CLIENTBOUND : Flow.SERVERBOUND;
    }

    /** {@code registerGlobalReceiver(Type, Handler)}. */
    Member registerGlobal() {
        return Member.staticMethod(networking, "registerGlobalReceiver",
                "(L" + PAYLOAD_TYPE + ";L" + handler + ";)Z");
    }

    /** {@code registerReceiver([listener,] Type, Handler)}. */
    Member registerOnConnection() {
        return Member.staticMethod(networking, "registerReceiver",
                "(" + listenerParam() + "L" + PAYLOAD_TYPE + ";L" + handler + ";)Z");
    }

    /**
     * {@code unregisterGlobalReceiver}. The client configuration side takes the payload type; every
     * other side takes the channel id.
     */
    Member unregisterGlobal() {
        String key = this == CLIENT_CONFIGURATION ? PAYLOAD_TYPE : IDENTIFIER;
        return Member.staticMethod(networking, "unregisterGlobalReceiver",
                "(L" + key + ";)L" + handler + ";");
    }

    /** {@code unregisterReceiver([listener,] Identifier)}. */
    Member unregisterOnConnection() {
        return Member.staticMethod(networking, "unregisterReceiver",
                "(" + listenerParam() + "L" + IDENTIFIER + ";)L" + handler + ";");
    }

    /** {@code send([target,] CustomPacketPayload)}; the server play side sends to a player. */
    Member send() {
        String target = this == SERVER_PLAY ? "Lnet/minecraft/server/level/ServerPlayer;"
                : listenerParam();
        return Member.staticMethod(networking, "send", "(" + target + "L" + PAYLOAD + ";)V");
    }

    /** {@code canSend([target,] Identifier)}, where the target is a player or a connection. */
    Member canSend(boolean toPlayer) {
        String target = toPlayer ? "Lnet/minecraft/server/level/ServerPlayer;" : listenerParam();
        return Member.staticMethod(networking, "canSend", "(" + target + "L" + IDENTIFIER + ";)Z");
    }

    /** The packet a payload travels in, for the sides whose host can build one. */
    Member createPacket() {
        String name = server ? "createClientboundPacket" : "createServerboundPacket";
        return Member.staticMethod(networking, name, "(L" + PAYLOAD + ";)L" + PACKET + ";");
    }

    /** A getter on this side's handler context, such as {@code player()}. */
    Member context(String name, String returnType) {
        return Member.virtual(networking + "$Context", name, "()L" + returnType + ";");
    }

    private String listenerParam() {
        return listener == null ? "" : "L" + listener + ";";
    }
}
