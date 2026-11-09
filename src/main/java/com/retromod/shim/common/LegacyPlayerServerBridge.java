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
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/** Keeps old {@code player.server} reads and {@code getServer()} calls working on hosts that hid or removed them. */
public final class LegacyPlayerServerBridge {

    static final String HELPER = "com/retromod/generated/LegacyPlayerServer";
    static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";

    /** The names one helper is written in: Mojang names, or a Fabric host's intermediary. */
    record Names(String player, String field, String helper, String levelOwner, String levelName,
            String levelReturn, String serverLevel, String getServer) {
        String read() {
            return "(L" + player + ";)L" + SERVER + ";";
        }

        String write() {
            return "(L" + player + ";L" + SERVER + ";)V";
        }
    }

    static final Names MOJANG = new Names(SERVER_PLAYER, "server", HELPER, ENTITY, "level", LEVEL, SERVER_LEVEL,
            "getServer");

    // A Fabric 1.21.6 to 1.21.11 host runs intermediary names. ServerPlayer's own level getter,
    // method_51469, is used because Entity.level() changed its id at 1.21.9 and this one did not.
    static final Names INTERMEDIARY = new Names("net/minecraft/class_3222", "field_13995",
            "com/retromod/generated/LegacyIntermediaryPlayerServer", "net/minecraft/class_3222", "method_51469",
            "net/minecraft/class_3218", "net/minecraft/class_3218", "method_8503");

    /** The first version whose {@code ServerPlayer.server} is private. */
    static final String PRIVATE_SINCE = "1.21.6";

    /** The names a rebuilt {@code Entity.getServer()} is written in, and the owners mods call it on. */
    record ServerGetter(String helper, String entity, String levelName, String level, String getServer,
            String removedName, List<String> owners) {
        String helperDesc() {
            return "(L" + entity + ";)L" + SERVER + ";";
        }
    }

    static final ServerGetter MOJANG_GETTER = new ServerGetter("com/retromod/generated/LegacyEntityServer",
            ENTITY, "level", LEVEL, "getServer", "getServer", List.of(ENTITY, PLAYER, SERVER_PLAYER));

    // A Fabric 1.21.9 to 1.21.11 host names Entity.level() method_73183 and Level.getServer()
    // method_8503. The removed getter was method_5682. Checked against the 1.21.8 to 1.21.11 tables.
    static final ServerGetter INTERMEDIARY_GETTER = new ServerGetter(
            "com/retromod/generated/LegacyIntermediaryEntityServer", "net/minecraft/class_1297", "method_73183",
            "net/minecraft/class_1937", "method_8503", "method_5682",
            List.of("net/minecraft/class_1297", "net/minecraft/class_1657", "net/minecraft/class_3222"));

    /** The first version without {@code Entity.getServer()}. */
    static final String GETTER_REMOVED_IN = "1.21.9";

    private LegacyPlayerServerBridge() {}

    /** Bridges the field only on a host where it is not public. */
    public static void register(RetromodTransformer transformer) {
        // 1.21.6 made the field private, and nothing on ServerPlayer returns it. MCreator's
        // advancement and command procedures read it on every run, such as
        // player.server.getAdvancements(), and failed with IllegalAccessError.
        register(transformer, MOJANG);
    }

    /** The same bridge in the intermediary names a Fabric 1.21.6 to 1.21.11 host runs. */
    public static void registerIntermediary(RetromodTransformer transformer) {
        register(transformer, INTERMEDIARY);
    }

    private static void register(RetromodTransformer transformer, Names names) {
        ClassNode player = ClassResourceInspector.read(names.player());
        // Offline, where the host class cannot be read, the host version decides.
        boolean hidden = player == null
                ? RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, PRIVATE_SINCE) >= 0
                : fieldHidden(player, names);
        if (hidden) registerRedirects(transformer, names);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        registerRedirects(transformer, MOJANG);
    }

    static void registerRedirects(RetromodTransformer transformer, Names names) {
        transformer.registerSyntheticClass(names.helper(), generateHelper(names));
        transformer.registerFieldStaticBridge(names.player(), names.field(), names.helper(), "server", names.read(),
                "setServer", names.write());
    }

    /** Rebuilds {@code Entity.getServer()} on a host that removed it. */
    public static void registerGetServer(RetromodTransformer transformer) {
        // 1.21.9 removed it. Server code calls it on a player to reach commands, advancements, or
        // the player list, and failed with NoSuchMethodError.
        registerGetServer(transformer, MOJANG_GETTER);
    }

    /** The same getter in the intermediary names a Fabric 1.21.9 to 1.21.11 host runs. */
    public static void registerGetServerIntermediary(RetromodTransformer transformer) {
        registerGetServer(transformer, INTERMEDIARY_GETTER);
    }

    private static void registerGetServer(RetromodTransformer transformer, ServerGetter names) {
        ClassNode entity = ClassResourceInspector.read(names.entity());
        // Offline, where the host class cannot be read, the host version decides.
        boolean removed = entity == null
                ? RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, GETTER_REMOVED_IN) >= 0
                : !declaresGetServer(entity, names);
        if (removed) registerGetServerRedirects(transformer, names);
    }

    static void registerGetServerRedirects(RetromodTransformer transformer, ServerGetter names) {
        transformer.registerSyntheticClass(names.helper(), generateGetServerHelper(names));
        // The call names whatever entity type the mod holds, so each common owner gets the rule,
        // and a mod's own entity class reaches it through the hierarchy.
        for (String owner : names.owners()) {
            transformer.registerInheritedMethodRedirect(owner, names.removedName(), "()L" + SERVER + ";",
                    names.helper(), "getServer", names.helperDesc());
        }
    }

    /** Whether the host's entity class still has the old getter. */
    static boolean declaresGetServer(ClassNode entity, ServerGetter names) {
        for (MethodNode method : entity.methods) {
            if (method.name.equals(names.removedName()) && method.desc.equals("()L" + SERVER + ";")) return true;
        }
        return false;
    }

    /** {@code getServer(entity)}: {@code entity.level().getServer()}, null on a client level as before. */
    static byte[] generateGetServerHelper(ServerGetter names) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, names.helper(), null,
                "java/lang/Object", null);
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getServer", names.helperDesc(),
                null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.entity(), names.levelName(), "()L" + names.level() + ";",
                false);
        get.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.level(), names.getServer(), "()L" + SERVER + ";", false);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The host still declares the field, but a mod can no longer read it. */
    static boolean fieldHidden(ClassNode player, Names names) {
        for (FieldNode field : player.fields) {
            if (field.name.equals(names.field()) && field.desc.equals("L" + SERVER + ";")) {
                return (field.access & Opcodes.ACC_PUBLIC) == 0;
            }
        }
        return false;
    }

    /** A helper class with a static read and write that stand in for the field. */
    static byte[] generateHelper(Names names) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, names.helper(), null,
                "java/lang/Object", null);

        // The read becomes player.level().getServer(). A server player's level is always a server
        // level of that same server.
        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "server", names.read(), null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.levelOwner(), names.levelName(),
                "()L" + names.levelReturn() + ";", false);
        // Entity.level() returns a plain Level; the player's own getter already returns a server level.
        if (!names.levelReturn().equals(names.serverLevel())) {
            read.visitTypeInsn(Opcodes.CHECKCAST, names.serverLevel());
        }
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.serverLevel(), names.getServer(), "()L" + SERVER + ";", false);
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        // A mod cannot assign the final field, so a write fails as it did before.
        MethodVisitor write = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setServer", names.write(), null, null);
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
