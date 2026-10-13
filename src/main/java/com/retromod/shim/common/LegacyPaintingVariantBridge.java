/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyPaintings;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps {@code new PaintingVariant(width, height)} from before 1.21, which sized a painting in
 * pixels and named its texture by registry id. 1.21 made variants data with an asset id and sizes
 * in blocks. The old constructor builds a variant of the same size; the legacy worldgen pack then
 * writes it as a data file under its registry id.
 */
public final class LegacyPaintingVariantBridge {

    static final String CALLS = "com/retromod/generated/LegacyPaintingCalls";
    static final String HELPER = "com/retromod/shim/common/embedded/LegacyPaintings";
    static final String VARIANT = "net/minecraft/world/entity/decoration/painting/PaintingVariant";

    private LegacyPaintingVariantBridge() {}

    public static void register(RetromodTransformer transformer) {
        ClassNode variant = ClassResourceInspector.read(VARIANT);
        if (variant == null || variant.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals("(II)V"))) return;
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyPaintings.class);
        transformer.registerSyntheticClass(CALLS, generateCalls());
        transformer.registerConstructorRedirect(VARIANT, "(II)V", CALLS, "create", "(II)L" + VARIANT + ";");
    }

    static byte[] generateCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", "(II)L" + VARIANT + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ILOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, HELPER, "create", "(II)Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, VARIANT);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
