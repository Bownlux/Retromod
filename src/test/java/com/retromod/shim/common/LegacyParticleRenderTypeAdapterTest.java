/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.1 turned {@code ParticleRenderType} into a final record and made {@code getLayer()} abstract.
 *
 * <p>NexusLib reads {@code ParticleRenderType.CUSTOM} in a {@code ParticleEngine} static-init mixin
 * and implements the old interface for its cloud sheet; the first crashed the 26.1.2 client with
 * {@code NoSuchFieldError} before the title screen. Bountiful Fares' particles have no
 * {@code getLayer()} and would throw {@code AbstractMethodError} when drawn.
 */
class LegacyParticleRenderTypeAdapterTest {

    private static final String RT = LegacyParticleRenderTypeAdapter.RENDER_TYPE;
    private static final String RT_DESC = LegacyParticleRenderTypeAdapter.RENDER_TYPE_DESC;

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyParticleRenderTypeAdapter.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("CUSTOM resolves to a constant that is still in the render order")
    void customConstantIsRedirected() {
        ClassNode out = transformed(readsConstant("CUSTOM"), "test/mod/ReadsCustom");

        FieldInsnNode read = firstField(out);
        assertEquals("ITEM_PICKUP", read.name,
                "NexusLib inserts its type at indexOf(CUSTOM); an absent anchor makes that -1 "
                + "and List.add(-1, ...) throws in ParticleEngine's static init");
    }

    @Test
    @DisplayName("the old sheet constants resolve to SINGLE_QUADS")
    void sheetConstantsAreRedirected() {
        ClassNode out = transformed(readsConstant("PARTICLE_SHEET_OPAQUE"), "test/mod/ReadsSheet");
        assertEquals("SINGLE_QUADS", firstField(out).name);
    }

    @Test
    @DisplayName("an implementation of the old interface stops naming the final record")
    void implementationLosesTheInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/CloudType", null, "java/lang/Object",
                new String[]{RT});
        cw.visitEnd();

        ClassNode out = read(LegacyParticleRenderTypeAdapter.apply(cw.toByteArray()));
        assertFalse(out.interfaces.contains(RT),
                "a class may not implement a final record; it fails to load");
    }

    @Test
    @DisplayName("a legacy implementation stored as a render type is converted to a record")
    void constructionIsConverted() {
        ClassNode out = read(LegacyParticleRenderTypeAdapter.apply(storesNewImplementation()));

        MethodNode clinit = out.methods.stream().filter(m -> m.name.equals("<clinit>"))
                .findFirst().orElseThrow();
        boolean converted = false;
        for (AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("renderTypeFor")) {
                converted = true;
            }
        }
        assertTrue(converted, "the field holds a final record on 26.1, so the stored value must "
                + "be one, or the class fails verification");
    }

    @Test
    @DisplayName("a quad particle without getLayer gains one; one that has it keeps its own")
    void missingLayerIsAdded() {
        ClassNode added = read(LegacyParticleRenderTypeAdapter.apply(quadParticle(false)));
        assertTrue(added.methods.stream().anyMatch(m -> m.name.equals("getLayer")),
                "getLayer is abstract on 26.1; a particle without it throws AbstractMethodError");

        byte[] own = quadParticle(true);
        assertSame(own, LegacyParticleRenderTypeAdapter.apply(own),
                "a particle that already picks its layer must be left alone");
    }

    private ClassNode transformed(byte[] input, String name) {
        return read(transformer.transformClass(input, name));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static FieldInsnNode firstField(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof FieldInsnNode f && f.owner.equals(RT)) return f;
            }
        }
        return fail("no ParticleRenderType field read survived the transform");
    }

    private static byte[] readsConstant(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Reader", null, "java/lang/Object",
                null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get",
                "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, RT, name, RT_DESC);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static ParticleRenderType CLOUD = new CloudType();}, NexusLib's shape. */
    private static byte[] storesNewImplementation() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/RenderTypes", null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CLOUD", RT_DESC, null, null)
                .visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "test/mod/CloudType");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "test/mod/CloudType", "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, "test/mod/RenderTypes", "CLOUD", RT_DESC);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] quadParticle(boolean declaresLayer) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/FlourParticle", null,
                LegacyParticleRenderTypeAdapter.SINGLE_QUAD, null);
        if (declaresLayer) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "getLayer",
                    "()" + LegacyParticleRenderTypeAdapter.LAYER_DESC, null, null);
            mv.visitCode();
            mv.visitFieldInsn(Opcodes.GETSTATIC, LegacyParticleRenderTypeAdapter.LAYER,
                    "TRANSLUCENT", LegacyParticleRenderTypeAdapter.LAYER_DESC);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
