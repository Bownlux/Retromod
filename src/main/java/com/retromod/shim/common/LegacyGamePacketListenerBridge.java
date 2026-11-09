/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.objectweb.asm.Opcodes.*;

/** Keeps the game packet listener constructor that 1.20.2 removed, which old fake player connections call. */
public final class LegacyGamePacketListenerBridge {

    static final String LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String COOKIE = "net/minecraft/server/network/CommonListenerCookie";
    static final String PROFILE = "com/mojang/authlib/GameProfile";
    private static final String ARGUMENTS = "Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;L"
            + SERVER_PLAYER + ";";
    /** {@code (MinecraftServer, Connection, ServerPlayer)}, removed in 1.20.2. */
    static final String OLD = "(" + ARGUMENTS + ")V";
    static final String CURRENT = "(" + ARGUMENTS + "L" + COOKIE + ";)V";
    static final String REMOVED_IN = "1.20.2";

    private LegacyGamePacketListenerBridge() {}

    /** Adds the cookie the current constructor takes, where the host lacks the old one; called from 26.2 shims only. */
    public static void register(RetromodTransformer transformer) {
        if (!hostLacksOldConstructor()) return;
        // Collective's, Create's and Steam 'n' Rails' fake players build their connection with
        // super(server, connection, player). The player is the last argument, so its profile gives
        // the cookie a first login would have. createInitial took this shape in 1.20.5.
        transformer.registerSuperConstructorAdapter(LISTENER, OLD, CURRENT, mv -> {
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKEVIRTUAL, SERVER_PLAYER, "getGameProfile", "()L" + PROFILE + ";", false);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKESTATIC, COOKIE, "createInitial", "(L" + PROFILE + ";Z)L" + COOKIE + ";", false);
        });
    }

    static boolean hostLacksOldConstructor() {
        ClassNode host = ClassResourceInspector.read(LISTENER);
        // Offline, where the host class cannot be read, the host version decides.
        if (host == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, REMOVED_IN) >= 0;
        }
        for (MethodNode method : host.methods) {
            if (method.name.equals("<init>") && method.desc.equals(OLD)) return false;
        }
        return true;
    }
}
