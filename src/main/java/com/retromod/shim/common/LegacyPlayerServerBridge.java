/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/** Keeps a mod's read of {@code ServerPlayer.server} working where the field is private. */
public final class LegacyPlayerServerBridge {

    static final String HELPER = "com/retromod/generated/LegacyPlayerServer";
    static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LEVEL = "net/minecraft/world/level/Level";

    static final String READ = "(L" + SERVER_PLAYER + ";)L" + SERVER + ";";
    static final String WRITE = "(L" + SERVER_PLAYER + ";L" + SERVER + ";)V";

    /** The first version whose {@code ServerPlayer.server} is private. */
    static final String PRIVATE_SINCE = "1.21.6";

    private LegacyPlayerServerBridge() {}

    /** Bridges the field only on a host where it is not public. */
    public static void register(RetromodTransformer transformer) {
        // 1.21.6 made the field private, and nothing on ServerPlayer returns it. MCreator's
        // advancement and command procedures read it on every run, such as
        // player.server.getAdvancements(), and failed with IllegalAccessError.
        ClassNode player = ClassResourceInspector.read(SERVER_PLAYER);
        // Offline, where the host class cannot be read, the host version decides.
        boolean hidden = player == null
                ? RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, PRIVATE_SINCE) >= 0
                : fieldHidden(player);
        if (hidden) registerRedirects(transformer);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerFieldStaticBridge(SERVER_PLAYER, "server", HELPER, "server", READ, "setServer", WRITE);
    }

    /** The host still declares the field, but a mod can no longer read it. */
    static boolean fieldHidden(ClassNode player) {
        for (FieldNode field : player.fields) {
            if (field.name.equals("server") && field.desc.equals("L" + SERVER + ";")) {
                return (field.access & Opcodes.ACC_PUBLIC) == 0;
            }
        }
        return false;
    }

    /** A helper class with a static read and write that stand in for the field. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);

        // The read becomes player.level().getServer(). A server player's level is always a server
        // level of that same server.
        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "server", READ, null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "level", "()L" + LEVEL + ";", false);
        read.visitTypeInsn(Opcodes.CHECKCAST, SERVER_LEVEL);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL, "getServer", "()L" + SERVER + ";", false);
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        // A mod cannot assign the final field, so a write fails as it did before.
        MethodVisitor write = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setServer", WRITE, null, null);
        write.visitCode();
        write.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalAccessError");
        write.visitInsn(Opcodes.DUP);
        write.visitLdcInsn("ServerPlayer.server is final");
        write.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalAccessError", "<init>", "(Ljava/lang/String;)V", false);
        write.visitInsn(Opcodes.ATHROW);
        write.visitMaxs(0, 0);
        write.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
