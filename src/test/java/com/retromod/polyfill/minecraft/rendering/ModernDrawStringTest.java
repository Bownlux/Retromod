/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.rendering;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillRegistry;
import com.retromod.shim.fabric.Fabric_1_19_4_to_1_20;
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
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #281 (Hold My Items 5.1.1 for 1.21.11 on Fabric 26.3): the mod died at client init with
 * {@code VerifyError: GuiGraphicsExtractor is not assignable to GuiComponentShim}. A polyfill keyed
 * on {@code GuiComponent.drawString(Font, String, int, int, int)V}, a signature the old class never
 * had, was re-keyed onto {@code GuiGraphics} by the 1.20 class redirect and caught the ordinary
 * modern call, which returns void from 1.21.6.
 */
class ModernDrawStringTest {

    private static final String GUI = "net/minecraft/client/gui/GuiGraphics";
    private static final String DRAW_DESC =
            "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a modern GuiGraphics.drawString is never routed into the old GuiComponent shim")
    void modernDrawStringStaysOnGuiGraphics() {
        new Fabric_1_19_4_to_1_20().registerRedirects(transformer);
        PolyfillRegistry polyfills = new PolyfillRegistry();
        polyfills.setEnabled(true);
        polyfills.loadAndRegister(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Hud", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw",
                "(L" + GUI + ";Lnet/minecraft/client/gui/Font;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn("text");
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_M1);
        mv.visitMethodInsn(INVOKEVIRTUAL, GUI, "drawString", DRAW_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Hud")).accept(out, 0);
        for (MethodNode m : out.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("drawString")) {
                    assertFalse(call.owner.startsWith("com/retromod/"),
                            "the modern call was hijacked into " + call.owner + call.desc);
                }
            }
        }
    }
}
