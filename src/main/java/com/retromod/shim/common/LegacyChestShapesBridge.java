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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Gives a chest subclass the shape constants ChestBlock dropped when 1.21.5 rebuilt block shapes. */
public final class LegacyChestShapesBridge {

    static final String SHAPES = "com/retromod/generated/LegacyChestShapes";
    static final String CHEST = "net/minecraft/world/level/block/ChestBlock";
    static final String SHAPE = "Lnet/minecraft/world/phys/shapes/VoxelShape;";

    /**
     * Each old constant, its SRG id, and its box in pixels: a single chest is inset one pixel and 14
     * tall, and each half of a double chest reaches the side it joins.
     */
    static final Object[][] BOXES = {
            {"AABB", "f_51485_", new double[] {1, 0, 1, 15, 14, 15}},
            {"NORTH_AABB", "f_51481_", new double[] {1, 0, 0, 15, 14, 15}},
            {"SOUTH_AABB", "f_51482_", new double[] {1, 0, 1, 15, 14, 16}},
            {"WEST_AABB", "f_51483_", new double[] {0, 0, 1, 15, 14, 15}},
            {"EAST_AABB", "f_51484_", new double[] {1, 0, 1, 16, 14, 15}}};

    private LegacyChestShapesBridge() {}

    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector::read, RetromodVersion.TARGET_MC_VERSION);
    }

    static void register(RetromodTransformer transformer, Function<String, ClassNode> host, String target) {
        if (!hostDropped(host.apply(CHEST), target)) return;
        transformer.registerSyntheticClass(SHAPES, generate());
        // Lootr reads AABB through its own chest class, so the read names the subclass.
        for (Object[] box : BOXES) {
            for (String name : new String[] {(String) box[0], (String) box[1]}) {
                transformer.registerInheritedStaticFieldRedirect(CHEST, name, SHAPE, SHAPES, (String) box[0], "()" + SHAPE);
            }
        }
    }

    /** Whether the host's ChestBlock lacks AABB; with no host class to read, from 1.21.5. */
    static boolean hostDropped(ClassNode chest, String target) {
        if (chest == null) return target != null && !RetromodVersion.mcVersionExceeds("1.21.5", target);
        for (FieldNode field : chest.fields) {
            if (field.name.equals("AABB")) return false;
        }
        return true;
    }

    /** One static getter per old constant, each built once with {@code Block.box}. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SHAPES, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        for (Object[] box : BOXES) {
            String name = (String) box[0];
            cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, name.toLowerCase(java.util.Locale.ROOT), SHAPE, null, null).visitEnd();
            for (double value : (double[]) box[2]) init.visitLdcInsn(value);
            init.visitMethodInsn(INVOKESTATIC, "net/minecraft/world/level/block/Block", "box", "(DDDDDD)" + SHAPE, false);
            init.visitFieldInsn(PUTSTATIC, SHAPES, name.toLowerCase(java.util.Locale.ROOT), SHAPE);

            MethodVisitor getter = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()" + SHAPE, null, null);
            getter.visitCode();
            getter.visitFieldInsn(GETSTATIC, SHAPES, name.toLowerCase(java.util.Locale.ROOT), SHAPE);
            getter.visitInsn(ARETURN);
            getter.visitMaxs(0, 0);
            getter.visitEnd();
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
