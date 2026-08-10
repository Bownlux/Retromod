/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Bridges old Fabric networking onto the post-1.20.5 CustomPayload API.
 *
 * 1.20.5 moved custom payloads from PacketByteBuf to PacketCodec and removed
 * PacketType/FabricPacket; 26.1 dropped the PlayChannelHandler interfaces for
 * PlayPayloadHandler. This adapts the old 5-param handlers to the new 2-param API
 * via FabricNetworkingBridge.
 */
public class FabricNetworkingPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Fabric Networking API Changes";
    }

    @Override
    public String getCategory() {
        return "fabric_api";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            // Removed in 1.20.5: replaced by CustomPayload system
            "net/fabricmc/fabric/api/networking/v1/PacketType",
            "net/fabricmc/fabric/api/networking/v1/FabricPacket",

            // PlayChannelHandler removed from both server and client networking
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$PlayChannelHandler",
            "net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking$PlayChannelHandler"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{
            "com.retromod.polyfill.fabric.embedded.ServerPlayChannelHandler",
            "com.retromod.polyfill.fabric.embedded.ClientPlayChannelHandler",
            "com.retromod.polyfill.fabric.embedded.FabricNetworkingBridge"
        };
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/PacketType",
            "net/minecraft/network/protocol/common/custom/CustomPacketPayload$Type");

        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/FabricPacket",
            "net/minecraft/network/protocol/common/custom/CustomPacketPayload");

        // bridge interfaces carry the @FunctionalInterface so old-mod lambdas still build
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$PlayChannelHandler",
            "com/retromod/polyfill/fabric/embedded/ServerPlayChannelHandler");

        // FabricClientNetworkingV1Shim owns the client handler and its call redirects.
        // It selects an intermediary or Mojang synthetic SAM from the running host.

        // Source descriptors use post-remapping names: ClassRemapper (outer) runs before
        // RetromodClassVisitor (inner), so by the time the method visitor sees the bytecode
        // the intermediary names and PlayChannelHandler are already rewritten. Targets take
        // Object params since the bridge can't reference MC classes at compile time, and the
        // verifier accepts that since the specific types are assignable to Object.
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking",
            "registerGlobalReceiver",
            "(Lnet/minecraft/resources/Identifier;Lcom/retromod/polyfill/fabric/embedded/ServerPlayChannelHandler;)Z",
            "com/retromod/polyfill/fabric/embedded/FabricNetworkingBridge",
            "registerServerGlobalReceiver",
            "(Ljava/lang/Object;Ljava/lang/Object;)Z");

        // Server: send(ServerPlayer, Identifier, FriendlyByteBuf) -> void
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking",
            "send",
            "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/Identifier;Lnet/minecraft/network/FriendlyByteBuf;)V",
            "com/retromod/polyfill/fabric/embedded/FabricNetworkingBridge",
            "serverSend",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");

        // Register embedded shim classes
        for (String cls : getPolyfillClasses()) {
            transformer.registerEmbeddedShim(cls);
        }
    }
}
