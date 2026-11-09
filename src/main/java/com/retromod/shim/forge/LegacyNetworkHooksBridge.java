/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Sends the menu and spawn calls of Forge's removed {@code NetworkHooks} to what Forge 26.2 keeps. */
public final class LegacyNetworkHooksBridge {

    static final String HOOKS = "net/minecraftforge/network/NetworkHooks";
    static final String HELPER = "com/retromod/generated/LegacyNetworkHooks";
    static final String SERVER_PLAYER_EXTENSION = "net/minecraftforge/common/extensions/IForgeServerPlayer";
    static final String FORGE_HOOKS = "net/minecraftforge/common/ForgeHooks";
    static final String EXTRA_SPAWN_DATA = "net/minecraftforge/entity/IEntityAdditionalSpawnData";
    static final String ADD_ENTITY_PACKET = "net/minecraft/network/protocol/game/ClientboundAddEntityPacket";

    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String L_PLAYER = "Lnet/minecraft/server/level/ServerPlayer;";
    private static final String L_PROVIDER = "Lnet/minecraft/world/MenuProvider;";
    private static final String L_POS = "Lnet/minecraft/core/BlockPos;";
    private static final String L_CONSUMER = "Ljava/util/function/Consumer;";
    private static final String L_PACKET = "Lnet/minecraft/network/protocol/Packet;";
    static final String SPAWN_DESC = "(L" + ENTITY + ";)" + L_PACKET;
    static final String ADD_ENTITY_DESC = "(ILjava/util/UUID;DDDFFLnet/minecraft/world/entity/EntityType;"
            + "ILnet/minecraft/world/phys/Vec3;D)V";

    private LegacyNetworkHooksBridge() {}

    /** Registers where the Forge host lacks NetworkHooks; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        if (!ForgeHostShape.matches(List.of(SERVER_PLAYER_EXTENSION, EXTRA_SPAWN_DATA), List.of(HOOKS))) return;
        registerRedirects(t);
    }

    static void registerRedirects(RetromodTransformer t) {
        t.registerSyntheticClass(HELPER, generateHelper());
        for (String extra : new String[] {"", L_POS, L_CONSUMER}) {
            String desc = "(" + L_PLAYER + L_PROVIDER + extra + ")V";
            t.registerMethodRedirect(HOOKS, "openScreen", desc, HELPER, "openScreen", desc);
        }
        t.registerMethodRedirect(HOOKS, "getEntitySpawningPacket", SPAWN_DESC, HELPER, "getEntitySpawningPacket", SPAWN_DESC);
    }

    /** The static stand-ins, plus a no-op extra-data writer for the two-argument {@code openScreen}. */
    static byte[] generateHelper() {
        // Both branches of the spawn packet return, so no frame merges two types and nothing is loaded.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object",
                new String[] {"java/util/function/Consumer"});

        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // Forge 1.20.1 sent no extra data for the two-argument form, so the writer adds nothing.
        MethodVisitor accept = cw.visitMethod(ACC_PUBLIC, "accept", "(Ljava/lang/Object;)V", null, null);
        accept.visitCode();
        accept.visitInsn(RETURN);
        accept.visitMaxs(0, 0);
        accept.visitEnd();

        emitOpenScreen(cw, "", L_CONSUMER);
        emitOpenScreen(cw, L_POS, L_POS);
        emitOpenScreen(cw, L_CONSUMER, L_CONSUMER);
        emitSpawnPacket(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code openScreen(player, provider[, extra])}: the player's own Forge {@code openMenu}, which
     * keeps Forge's packet so the client's menu factory still reads the extra data.
     */
    private static void emitOpenScreen(ClassWriter cw, String extra, String openMenuExtra) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "openScreen",
                "(" + L_PLAYER + L_PROVIDER + extra + ")V", null, null);
        mv.visitCode();
        // ServerPlayer implements the extension, and the interface call does not depend on how it is patched in.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, SERVER_PLAYER_EXTENSION);
        mv.visitVarInsn(ALOAD, 1);
        if (extra.isEmpty()) {
            mv.visitTypeInsn(NEW, HELPER);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, HELPER, "<init>", "()V", false);
        } else {
            mv.visitVarInsn(ALOAD, 2);
        }
        mv.visitMethodInsn(INVOKEINTERFACE, SERVER_PLAYER_EXTENSION, "openMenu",
                "(" + L_PROVIDER + openMenuExtra + ")V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code getEntitySpawningPacket(entity)}. Forge 26.2's spawn packet refuses an entity without
     * extra spawn data, which Forge 1.20.1 accepted and most mods pass, so such an entity gets
     * vanilla's add-entity packet with the same position, rotation and motion.
     */
    private static void emitSpawnPacket(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getEntitySpawningPacket", SPAWN_DESC, null, null);
        mv.visitCode();
        Label vanilla = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(INSTANCEOF, EXTRA_SPAWN_DATA);
        mv.visitJumpInsn(IFEQ, vanilla);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, FORGE_HOOKS, "getEntitySpawnPacket", SPAWN_DESC, false);
        mv.visitInsn(ARETURN);

        // new ClientboundAddEntityPacket(id, uuid, x, y, z, xRot, yRot, type, 0, motion, headYaw)
        mv.visitLabel(vanilla);
        mv.visitTypeInsn(NEW, ADD_ENTITY_PACKET);
        mv.visitInsn(DUP);
        entityGetter(mv, "getId", "()I");
        entityGetter(mv, "getUUID", "()Ljava/util/UUID;");
        entityGetter(mv, "getX", "()D");
        entityGetter(mv, "getY", "()D");
        entityGetter(mv, "getZ", "()D");
        entityGetter(mv, "getXRot", "()F");
        entityGetter(mv, "getYRot", "()F");
        entityGetter(mv, "getType", "()Lnet/minecraft/world/entity/EntityType;");
        mv.visitInsn(ICONST_0);
        entityGetter(mv, "getDeltaMovement", "()Lnet/minecraft/world/phys/Vec3;");
        entityGetter(mv, "getYHeadRot", "()F");
        mv.visitInsn(F2D);
        mv.visitMethodInsn(INVOKESPECIAL, ADD_ENTITY_PACKET, "<init>", ADD_ENTITY_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void entityGetter(MethodVisitor mv, String name, String desc) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, ENTITY, name, desc, false);
    }
}
