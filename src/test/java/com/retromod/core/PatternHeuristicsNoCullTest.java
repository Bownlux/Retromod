/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.shim.common.Mc26_2To26_3CoreMoves;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The {@code *NoCull} pattern rule describes the pre-26.1 {@code RenderType} factories. The 26.x
 * {@code RenderTypes} holder still declares {@code armorCutoutNoCull} on 26.1 through 26.3, and the
 * rule used to rename that valid call to the missing {@code armorCutout}, so a 26.1 mod's armor
 * layer failed on 26.2 and 26.3 (#349).
 */
class PatternHeuristicsNoCullTest {

    private static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";
    private static final String HELPER = "test/LayerHelper";
    private static final String TEXTURE_FACTORY =
            "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedTarget;

    @BeforeEach
    void setUp() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The rule leaves the 26.x RenderTypes factories alone")
    void renderTypesFactoryIsNotARuleMatch() {
        assertNull(new PatternHeuristics().resolveMethod(RENDER_TYPES, "armorCutoutNoCull", TEXTURE_FACTORY),
                "RenderTypes.armorCutoutNoCull exists on every 26.x host");
    }

    @Test
    @DisplayName("The rule still covers the pre-26.1 RenderType factories")
    void legacyRenderTypeIsStillARuleMatch() {
        String legacyFactory = "(Lnet/minecraft/resources/ResourceLocation;)"
                + "Lnet/minecraft/client/renderer/RenderType;";
        assertNotNull(new PatternHeuristics().resolveMethod(
                "net/minecraft/client/renderer/RenderType", "entityCutoutNoCull", legacyFactory));
    }

    @Test
    @DisplayName("A 26.1 armor layer keeps armorCutoutNoCull on a 26.3 host")
    void armorCutoutNoCullSurvivesTheTransform() {
        // The layer's other call has a redirect, so the class is rewritten and this call is seen.
        transformer.registerMethodRedirect(HELPER, "old", "()V", HELPER, "renamed", "()V");
        byte[] out = transformer.transformClass(armorLayer(), "test/ArmorLayer");
        assertEquals("renamed", call(out, HELPER).name, "the class went through the rewrite");
        assertEquals("armorCutoutNoCull", call(out, RENDER_TYPES).name,
                "a call with no redirect must not be renamed");
    }

    @Test
    @DisplayName("The call also survives once the 26.3 shim registers other RenderTypes redirects")
    void armorCutoutNoCullSurvivesThe26_3Shim() {
        Mc26_2To26_3CoreMoves.register(transformer);
        assertEquals("armorCutoutNoCull", call(transformer.transformClass(armorLayer(), "test/ArmorLayer"), RENDER_TYPES).name,
                "RenderTypes having redirected members must not expose this call to the rule");
    }

    private static byte[] armorLayer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/ArmorLayer", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "armorType",
                TEXTURE_FACTORY, null, null);
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, HELPER, "old", "()V", false);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, RENDER_TYPES, "armorCutoutNoCull", TEXTURE_FACTORY, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode call(byte[] bytes, String owner) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) return call;
        }
        throw new AssertionError("no " + owner + " call left");
    }
}
