/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Keeps the spawn egg constructor that took an entity type and two colors. */
public final class LegacySpawnEggBridge {

    private LegacySpawnEggBridge() {}

    /** Named like a spawn egg class so the color scan still reads its {@code create} calls. */
    public static final String INTERNAL = "com/retromod/shim/common/embedded/LegacySpawnEggItem";

    private static final String SPAWN_EGG = "net/minecraft/world/item/SpawnEggItem";
    private static final String ENTITY_TYPE = "Lnet/minecraft/world/entity/EntityType;";
    private static final String PROPS = "net/minecraft/world/item/Item$Properties";
    private static final String L_PROPS = "L" + PROPS + ";";
    /** The removed constructor, {@code (EntityType, int, int, Item.Properties)}. */
    static final String OLD_CONSTRUCTOR = "(" + ENTITY_TYPE + "II" + L_PROPS + ")V";
    static final String CREATE_DESC = "(" + ENTITY_TYPE + "II" + L_PROPS + ")L" + SPAWN_EGG + ";";
    static final String PROPERTIES_DESC = "(" + ENTITY_TYPE + "II" + L_PROPS + ")" + L_PROPS;

    /** Routes the old egg constructor and a matching {@code super(...)} here, on 26.2 and newer. */
    public static void register(RetromodTransformer transformer) {
        // Up to 1.21.4 a mod called new SpawnEggItem(type, base, spots, properties). 26.2 builds an
        // egg from its Item.Properties alone, so the old call failed with NoSuchMethodError while the
        // item class initialized, and every item after it went unregistered.
        transformer.registerSyntheticClass(INTERNAL, generate());
        transformer.registerConstructorRedirect(SPAWN_EGG, OLD_CONSTRUCTOR, INTERNAL, "create", CREATE_DESC);
        // The receiver is still uninitialized at super(...), so only the arguments can be rebuilt:
        // the four old ones become the one properties object the current constructor takes.
        transformer.registerSuperConstructorAdapter(SPAWN_EGG, OLD_CONSTRUCTOR, "(" + L_PROPS + ")V",
                mv -> mv.visitMethodInsn(INVOKESTATIC, INTERNAL, "properties", PROPERTIES_DESC, false));
    }

    /** The helper class, which puts the type on the properties and builds the egg the current way. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL, null, "java/lang/Object", null);

        // properties(type, base, spots, p) returns p.spawnEgg(type). The colors are dropped here:
        // 26.x tints an egg from its item definition, which the resource migration writes from the
        // same constructor call.
        MethodVisitor properties = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "properties", PROPERTIES_DESC, null, null);
        properties.visitCode();
        properties.visitVarInsn(ALOAD, 3);
        properties.visitVarInsn(ALOAD, 0);
        properties.visitMethodInsn(INVOKEVIRTUAL, PROPS, "spawnEgg", "(" + ENTITY_TYPE + ")" + L_PROPS, false);
        properties.visitInsn(ARETURN);
        properties.visitMaxs(0, 0);
        properties.visitEnd();

        // create(type, base, spots, p) returns new SpawnEggItem(properties(type, base, spots, p)).
        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", CREATE_DESC, null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, SPAWN_EGG);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 0);
        create.visitVarInsn(ILOAD, 1);
        create.visitVarInsn(ILOAD, 2);
        create.visitVarInsn(ALOAD, 3);
        create.visitMethodInsn(INVOKESTATIC, INTERNAL, "properties", PROPERTIES_DESC, false);
        create.visitMethodInsn(INVOKESPECIAL, SPAWN_EGG, "<init>", "(" + L_PROPS + ")V", false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
