/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RedirectKey;
import com.retromod.loaderapi.LoaderApiRegistration;
import com.retromod.loaderapi.fabric.v26.net.LegacyChannels;
import com.retromod.loaderapi.fabric.v26.synth.BoundaryTypeSpec;

import java.util.List;

/** Registers batch N1: old play channels, typed packets and their handler types on 26.x hosts. */
public final class NetworkingRows {

    private static final String API = "net/fabricmc/fabric/api/";
    private static final String SHARED = "com/retromod/loaderapi/fabric/v26/net/shared/";
    private static final String BRIDGES = "com/retromod/loaderapi/fabric/v26/synth/";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String SENDER = "L" + API + "networking/v1/PacketSender;";
    private static final String PACKET = "L" + SHARED + "LegacyFabricPacket;";

    /** Old handler and packet types, each mapped to one shared type that every mod names. */
    public static final List<BoundaryTypeSpec> SHARED_TYPES = List.of(
            new BoundaryTypeSpec(API + "networking/v1/ServerPlayNetworking$PlayChannelHandler",
                    SHARED + "ServerPlayChannelHandler", "receive",
                    "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;"
                            + "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
                            + "Lnet/minecraft/network/FriendlyByteBuf;" + SENDER + ")V",
                    "receiveServerPlayChannel", "(" + OBJECT.repeat(5) + ")V",
                    BRIDGES + "ServerPlayChannelHandlerBridge"),
            new BoundaryTypeSpec(API + "client/networking/v1/ClientPlayNetworking$PlayChannelHandler",
                    SHARED + "ClientPlayChannelHandler", "receive",
                    "(Lnet/minecraft/client/Minecraft;"
                            + "Lnet/minecraft/client/multiplayer/ClientPacketListener;"
                            + "Lnet/minecraft/network/FriendlyByteBuf;" + SENDER + ")V",
                    "receiveClientPlayChannel", "(" + OBJECT.repeat(4) + ")V",
                    BRIDGES + "ClientPlayChannelHandlerBridge"),
            new BoundaryTypeSpec(API + "networking/v1/ServerPlayNetworking$PlayPacketHandler",
                    SHARED + "ServerPlayPacketHandler", "receive",
                    "(" + PACKET + "Lnet/minecraft/server/level/ServerPlayer;" + SENDER + ")V",
                    "receiveServerPlayPacket", "(" + OBJECT.repeat(3) + ")V",
                    BRIDGES + "ServerPlayPacketHandlerBridge"),
            new BoundaryTypeSpec(API + "client/networking/v1/ClientPlayNetworking$PlayPacketHandler",
                    SHARED + "ClientPlayPacketHandler", "receive",
                    "(" + PACKET + "Lnet/minecraft/client/player/LocalPlayer;" + SENDER + ")V",
                    "receiveClientPlayPacket", "(" + OBJECT.repeat(3) + ")V",
                    BRIDGES + "ClientPlayPacketHandlerBridge"),
            new BoundaryTypeSpec(API
                    + "client/networking/v1/ClientConfigurationNetworking$ConfigurationPacketHandler",
                    SHARED + "ClientConfigurationPacketHandler", "receive",
                    "(" + PACKET + SENDER + ")V", "receiveClientConfigurationPacket",
                    "(" + OBJECT.repeat(2) + ")V", BRIDGES + "ClientConfigurationPacketHandlerBridge"),
            new BoundaryTypeSpec(API + "networking/v1/FabricPacket", SHARED + "LegacyFabricPacket",
                    "write", "(Lnet/minecraft/network/FriendlyByteBuf;)V", "writeLegacyPacket",
                    "(" + OBJECT + ")V", BRIDGES + "FabricPacketBridge"));

    /** Compiled helpers that redirected calls land on. */
    public static final List<String> HELPERS = List.of(
            "com/retromod/loaderapi/fabric/v26/net/LegacyChannels",
            "com/retromod/loaderapi/fabric/v26/net/LegacyPacketSenders",
            "com/retromod/loaderapi/fabric/v26/net/TypedPackets",
            "com/retromod/loaderapi/fabric/v26/net/SendListeners",
            SHARED + "LegacyPacketType");

    private NetworkingRows() {}

    /** Registers the shared types and helper owners; the call rows come from the rename table after this. */
    public static void register(LoaderApiRegistration registration) {
        // The old names are Fabric API's, so the runtime takes them from whatever shim had them.
        for (BoundaryTypeSpec spec : SHARED_TYPES) {
            registration.claim(RedirectKey.type(spec.oldName()));
            spec.register(registration);
        }
        // A helper is a class, so an interface-static call sent to it must lose its interface flag.
        for (String helper : HELPERS) {
            registration.claim(RedirectKey.type(helper));
            registration.transformer().registerClassOwner(helper);
        }
        // Jars the old bridges still serve share these channels while the runtime is on.
        LegacyChannels.installOldBridgeDelegates();
    }
}
