/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Points LocalPlayer.getServerBrand(), gone since 1.20.2, and ClientPacketListener.getId(), gone in 26.1, at rebuilt reads. */
public final class LegacyClientConnectionBridge {

    static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    static final String PACKET_LISTENER = "net/minecraft/client/multiplayer/ClientPacketListener";
    static final String COMMON_LISTENER = "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl";
    static final String GET_ID_DESC = "()Ljava/util/UUID;";
    public static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/client/multiplayer/LegacyClientConnection";

    private LegacyClientConnectionBridge() {}

    /** The brand read, for the 1.21 layer: 1.20.2 moved the brand from the player to the connection. */
    public static void registerServerBrand(RetromodTransformer t) {
        registerServerBrand(t, ClassResourceInspector::read);
    }

    static void registerServerBrand(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostMovedServerBrand(host)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        // JEI reads it while the player joins a world, so a missing one stops the join.
        t.registerMethodRedirect(LOCAL_PLAYER, "getServerBrand", "()Ljava/lang/String;",
                REBUILT, "serverBrand", "(L" + LOCAL_PLAYER + ";)Ljava/lang/String;", true);
    }

    /** The connection id, for the 26.1 layer: 1.21.11 still has getId(), and only a host without it takes the rebuilt one. */
    public static void registerConnectionId(RetromodTransformer t) {
        registerConnectionId(t, ClassResourceInspector::read);
    }

    static void registerConnectionId(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostDroppedConnectionId(host)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerMethodRedirect(PACKET_LISTENER, "getId", GET_ID_DESC,
                REBUILT, "id", "(L" + PACKET_LISTENER + ";)Ljava/util/UUID;", true);
    }

    /** Whether the host keeps the brand on the connection, not the player; a server or offline run reads neither. */
    static boolean hostMovedServerBrand(Function<String, ClassNode> host) {
        ClassNode player = host.apply(LOCAL_PLAYER);
        if (player == null) return true;
        if (declares(player, "getServerBrand", "()Ljava/lang/String;")) return false;
        ClassNode common = host.apply(COMMON_LISTENER);
        return common != null && declares(common, "serverBrand", "()Ljava/lang/String;");
    }

    /** Whether the host's connection lost getId(); a server or offline run reads nothing, and the 26.1 layer stands for it. */
    static boolean hostDroppedConnectionId(Function<String, ClassNode> host) {
        ClassNode listener = host.apply(PACKET_LISTENER);
        return listener == null || !declares(listener, "getId", GET_ID_DESC);
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
}
