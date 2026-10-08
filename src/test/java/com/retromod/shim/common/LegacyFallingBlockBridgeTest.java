/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.20.3 folded {@code SandBlock} and {@code GravelBlock} into {@code ColoredFallingBlock}, which
 * takes a {@code ColorRGBA}. Old mods still build sand with an {@code int} color and subclass both.
 */
class LegacyFallingBlockBridgeTest {

    private static final String L_PROPERTIES = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void oldSandAndGravelConstructorsLinkAgainstTheGeneratedBlock() {
        RetromodTransformer transformer = registeredOn("1.21.1");
        ClassNode generated = read(transformer.getSyntheticClasses().get(LegacyFallingBlockBridge.GENERATED));
        assertEquals("net/minecraft/world/level/block/ColoredFallingBlock", generated.superName);

        ClassNode mod = read(transformer.transformClass(blockFactory(), "com/example/ModBlocks"));
        int constructors = 0;
        for (MethodNode method : mod.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("<init>")
                        && !call.owner.equals("java/lang/Object")) {
                    assertEquals(LegacyFallingBlockBridge.GENERATED, call.owner);
                    assertTrue(declares(generated, call.desc),
                            "the generated block must declare " + call.desc);
                    constructors++;
                }
            }
        }
        assertEquals(2, constructors, "the sand and the gravel constructor");
    }

    @Test
    void sandConstructorWrapsTheColorInTheRecord() {
        ClassNode generated = read(LegacyFallingBlockBridge.generate());
        MethodNode sand = generated.methods.stream()
                .filter(m -> m.desc.equals("(I" + L_PROPERTIES + ")V")).findFirst().orElseThrow();
        MethodInsnNode wrap = null;
        for (AbstractInsnNode insn : sand.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("net/minecraft/util/ColorRGBA")) wrap = call;
        }
        assertNotNull(wrap, "the int color must become a ColorRGBA");
        assertEquals("(I)V", wrap.desc);
    }

    @Test
    void registersNothingBefore1203() {
        RetromodTransformer transformer = registeredOn("1.20.2");
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyFallingBlockBridge.GENERATED));
        assertNull(transformer.getClassRedirects().get(LegacyFallingBlockBridge.SAND),
                "1.20.2 still has SandBlock");
    }

    private static RetromodTransformer registeredOn(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyFallingBlockBridge.register(transformer);
        return transformer;
    }

    // new SandBlock(color, properties); new GravelBlock(properties)
    private static byte[] blockFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/ModBlocks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "(" + L_PROPERTIES + ")V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, LegacyFallingBlockBridge.SAND);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(10708917);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyFallingBlockBridge.SAND, "<init>", "(I" + L_PROPERTIES + ")V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, LegacyFallingBlockBridge.GRAVEL);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyFallingBlockBridge.GRAVEL, "<init>", "(" + L_PROPERTIES + ")V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean declares(ClassNode node, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(desc));
    }
}
