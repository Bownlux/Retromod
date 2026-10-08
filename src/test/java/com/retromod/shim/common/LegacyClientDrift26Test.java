/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Client calls Phantom Shapes and Hold My Items make that changed shape by 26.1 (#296, #281). */
class LegacyClientDrift26Test {

    private static final String PROBE = "probe/ClientCalls";
    private static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";

    private RetromodTransformer transformer;

    @BeforeEach
    void register() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Common_1_21_11_to_26_1_ClassMoves.registerClientAccessorRenames26_1(transformer);
        LegacyGuiDrawBridge.register(transformer);
    }

    @AfterEach
    void clear() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void cameraPitchAndYawUseTheRecordAccessorsNotTheFieldOfView() {
        List<MethodInsnNode> calls = calls(transformed(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/Camera", "getXRot", "()F", false);
            m.visitInsn(Opcodes.POP);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/Camera", "getYRot", "()F", false);
            m.visitInsn(Opcodes.POP);
        }));

        assertEquals(List.of("xRot", "yRot"), calls.stream().map(c -> c.name).toList());
    }

    @Test
    void drawStringBecomesTextAndPushesAPlaceholderWidth() {
        MethodNode method = transformed(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EXTRACTOR, "drawString",
                    "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I", false);
            m.visitInsn(Opcodes.POP);
        });
        MethodInsnNode text = calls(method).get(0);

        assertEquals("text", text.name);
        assertEquals("(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", text.desc,
                "26.1's text returns nothing");
        assertEquals(Opcodes.ICONST_0, text.getNext().getOpcode(),
                "the caller still pops an int, so one must be pushed");
    }

    @Test
    void windowHandleKeyPollGoesToTheVersionAwareHelper() {
        MethodInsnNode call = calls(transformed(m -> {
            m.visitInsn(Opcodes.LCONST_0);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/blaze3d/platform/InputConstants", "isKeyDown",
                    "(JI)Z", false);
            m.visitInsn(Opcodes.POP);
        })).get(0);

        assertEquals("com/retromod/polyfill/minecraft/LegacyGuiCalls", call.owner);
    }

    @Test
    void literalKeyCodeInAWindowHandlePollIsTranslatedForTheSdlHost() {
        String previousTarget = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.3";
        try {
            Mc26_2To26_3CoreMoves.register(transformer);
            List<MethodInsnNode> calls = calls(transformed(m -> {
                m.visitInsn(Opcodes.LCONST_0);
                m.visitIntInsn(Opcodes.SIPUSH, 340); // GLFW_KEY_LEFT_SHIFT
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/blaze3d/platform/InputConstants",
                        "isKeyDown", "(JI)Z", false);
                m.visitInsn(Opcodes.POP);
            }));

            assertEquals(List.of("hostKeyCode", "isKeyDown"), calls.stream().map(c -> c.name).toList(),
                    "26.3 numbers keys for SDL, so a literal GLFW code must be translated before the poll");
            assertEquals("com/retromod/polyfill/minecraft/RetroKeyMapping", calls.get(0).owner);
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = previousTarget;
        }
    }

    private MethodNode transformed(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PROBE, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(Ljava/lang/Object;)V", null, null);
        m.visitCode();
        body.accept(m);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), PROBE)).accept(node, 0);
        return node.methods.stream().filter(x -> x.name.equals("run")).findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
