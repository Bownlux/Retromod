/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Forge's {@code DungeonHooks}, which NeoForge replaced with the {@code monster_room_mobs} data map.
 * A mod that adds a monster room mob loads, and the mob is not added. {@code addDungeonMob} returns
 * the weight it was given, {@code removeDungeonMob} returns 0, and {@code getRandomDungeonMob} is
 * not provided.
 */
public final class LegacyDungeonHooksBridge implements Opcodes {

    static final String STAND_IN = "com/retromod/generated/LegacyDungeonHooks";
    private static final String ENTITY_TYPE = "Lnet/minecraft/world/entity/EntityType;";

    private LegacyDungeonHooksBridge() {}

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(STAND_IN, generate());
        transformer.registerClassRedirect("net/minecraftforge/common/DungeonHooks", STAND_IN);
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, STAND_IN, null, "java/lang/Object", null);
        MethodVisitor add = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addDungeonMob", "(" + ENTITY_TYPE + "I)F", null, null);
        add.visitCode();
        add.visitVarInsn(ILOAD, 1);
        add.visitInsn(I2F);
        add.visitInsn(FRETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();
        MethodVisitor remove = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "removeDungeonMob", "(" + ENTITY_TYPE + ")I", null, null);
        remove.visitCode();
        remove.visitInsn(ICONST_0);
        remove.visitInsn(IRETURN);
        remove.visitMaxs(0, 0);
        remove.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
