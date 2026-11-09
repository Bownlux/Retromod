/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;

/** Sends Forge 1.20.2 to 1.21 channel networking ({@code ChannelBuilder}, {@code SimpleChannel}) through NetworkShim on NeoForge. */
final class LegacyForgeChannelBridge {

    static final String FORGE = "net/minecraftforge/network/";
    static final String CONTEXT = "net/minecraftforge/event/network/CustomPayloadEvent$Context";
    static final String SHIM = "com/retromod/shim/forge/embedded/NetworkShim";
    static final String BUILDER = SHIM + "$ChannelBuilder";
    static final String CHANNEL = SHIM + "$SimpleChannelWrapper";
    static final String MESSAGE = SHIM + "$MessageBuilder";
    static final String SHIM_CONTEXT = SHIM + "$Context";
    static final String PAYLOAD = SHIM + "$PayloadChannel";
    private static final String CONNECTION = "Lnet/minecraft/network/Connection;";

    private LegacyForgeChannelBridge() {}

    /** Registers the bridge. The caller applies it only on a NeoForge host, which has none of Forge's network classes. */
    static void register(RetromodTransformer t) {
        // Forge 1.20.2 rebuilt networking under these names, which NeoForge never had. PacketDistributor
        // and NetworkDirection keep their names, and Forge_1_20_to_NeoForge_1_21 already sends them to the shim.
        t.registerClassRedirect(FORGE + "ChannelBuilder", BUILDER);
        t.registerClassRedirect(FORGE + "SimpleChannel", CHANNEL);
        t.registerClassRedirect(FORGE + "Channel", CHANNEL);
        t.registerClassRedirect(FORGE + "SimpleChannel$MessageBuilder", MESSAGE);
        t.registerClassRedirect(FORGE + "SimpleChannel$MessageBuilder$ToBooleanBiFunction", SHIM + "$ToBooleanBiFunction");
        t.registerClassRedirect(FORGE + "Channel$VersionTest", SHIM + "$VersionTest");
        t.registerClassRedirect(FORGE + "Channel$VersionTest$Status", SHIM + "$VersionTest$Status");
        t.registerClassRedirect(CONTEXT, SHIM_CONTEXT);
        // Forge 1.21's payload channels: one builder stands in for the connection, protocol and flow.
        for (String type : new String[] {"PayloadConnection", "PayloadProtocol", "PayloadFlow"}) {
            t.registerClassRedirect(FORGE + "payload/" + type, PAYLOAD);
        }

        // Calls whose descriptors name Minecraft types go to the shim's Object forms, and their
        // results are cast back at the mod's call site. Each is keyed on Forge's owner and the shim's.
        for (String id : new String[] {"Lnet/minecraft/resources/ResourceLocation;", "Lnet/minecraft/resources/Identifier;"}) {
            for (String owner : new String[] {FORGE + "ChannelBuilder", BUILDER}) {
                for (String ret : new String[] {"L" + FORGE + "ChannelBuilder;", "L" + BUILDER + ";"}) {
                    t.registerMethodRedirect(owner, "named", "(" + id + ")" + ret, BUILDER, "named",
                            "(Ljava/lang/Object;)L" + BUILDER + ";");
                }
            }
        }
        for (String owner : new String[] {FORGE + "SimpleChannel", FORGE + "Channel", CHANNEL}) {
            t.registerMethodRedirect(owner, "send", "(Ljava/lang/Object;" + CONNECTION + ")V",
                    CHANNEL, "sendToConnection", "(Ljava/lang/Object;Ljava/lang/Object;)V");
            t.registerMethodRedirect(owner, "isRemotePresent", "(" + CONNECTION + ")Z",
                    CHANNEL, "isRemotePresent", "(Ljava/lang/Object;)Z");
        }
        for (String owner : new String[] {CONTEXT, SHIM_CONTEXT}) {
            t.registerMethodRedirect(owner, "getSender", "()Lnet/minecraft/server/level/ServerPlayer;",
                    SHIM_CONTEXT, "getSender", "()Ljava/lang/Object;");
            t.registerMethodRedirect(owner, "getConnection", "()" + CONNECTION,
                    SHIM_CONTEXT, "getConnection", "()Ljava/lang/Object;");
        }
        registerMessageBuilder(t);
        registerPayloadFlow(t);
    }

    /** The payload flow's add calls, whose type and codec are Minecraft's. */
    private static void registerPayloadFlow(RetromodTransformer t) {
        String args = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;"
                + "Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/BiConsumer;)";
        for (String name : new String[] {"add", "addMain"}) {
            for (String owner : new String[] {FORGE + "payload/PayloadFlow", PAYLOAD}) {
                for (String ret : new String[] {"L" + FORGE + "payload/PayloadFlow;", "L" + PAYLOAD + ";"}) {
                    t.registerMethodRedirect(owner, name, args + ret, PAYLOAD, name,
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiConsumer;)L" + PAYLOAD + ";");
                }
            }
        }
    }

    /** The message builder, whose handlers take the context itself and whose add() returns the channel. */
    private static void registerMessageBuilder(RetromodTransformer t) {
        String forgeBuilder = FORGE + "SimpleChannel$MessageBuilder";
        String forgeReturn = "L" + forgeBuilder + ";";
        // Keyed on Forge 1.20.2's owner only: Forge 1.20.1's builder has methods of the same erased
        // shape whose handlers take a supplier of the context, and those keep the shim's own methods.
        for (String name : new String[] {"consumer", "consumerMainThread", "consumerNetworkThread"}) {
            t.registerMethodRedirect(forgeBuilder, name, "(Ljava/util/function/BiConsumer;)" + forgeReturn,
                    MESSAGE, "contextConsumer", "(Ljava/util/function/BiConsumer;)L" + MESSAGE + ";");
        }
        t.registerMethodRedirect(forgeBuilder, "consumerNetworkThread",
                "(L" + forgeBuilder + "$ToBooleanBiFunction;)" + forgeReturn,
                MESSAGE, "contextConsumer", "(L" + SHIM + "$ToBooleanBiFunction;)L" + MESSAGE + ";");
        for (String ret : new String[] {forgeReturn, "L" + MESSAGE + ";"}) {
            for (String owner : new String[] {forgeBuilder, MESSAGE}) {
                t.registerMethodRedirect(owner, "codec", "(Lnet/minecraft/network/codec/StreamCodec;)" + ret,
                        MESSAGE, "codec", "(Ljava/lang/Object;)L" + MESSAGE + ";");
            }
        }
        for (String owner : new String[] {forgeBuilder, MESSAGE}) {
            for (String ret : new String[] {"L" + FORGE + "SimpleChannel;", "L" + CHANNEL + ";"}) {
                t.registerMethodRedirect(owner, "add", "()" + ret, MESSAGE, "added", "()L" + CHANNEL + ";");
            }
        }
    }
}
