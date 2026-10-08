/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import static com.retromod.shim.common.LegacyTreeGrowerBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 mod's sapling and tree grower against the 1.20.3 {@code TreeGrower} design. */
class LegacyTreeGrowerBridgeTest {

    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void aLegacyGrowerSubclassExtendsTheStandIn() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer);

        ClassNode grower = node(transformer.transformClass(modGrower(), "test/PalmTreeGrower.class"));

        assertEquals(GROWER, grower.superName, "the mod grower must extend the generated stand-in");
    }

    @Test
    void newSaplingBlockWithALegacyGrowerGoesThroughTheFactory() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer);

        ClassNode blocks = node(transformer.transformClass(saplingCaller(), "test/ModBlocks.class"));

        boolean factory = false;
        for (AbstractInsnNode insn : method(blocks, "sapling").instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(SAPLING_FACTORY)
                    && call.name.equals("of")) {
                factory = true;
            }
            assertFalse(insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals(SAPLING), "the sapling must not be built by a direct constructor call");
        }
        assertTrue(factory, "the sapling must be built by the factory");
    }

    @Test
    void generatedClassesAreStructurallyValid() throws Exception {
        for (byte[] bytes : new byte[][] {generateGrower(), generateMegaGrower(), generateSaplingFactory()}) {
            ClassNode node = node(bytes);
            for (MethodNode method : node.methods) {
                if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
                    new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                }
            }
        }
    }

    private static byte[] modGrower() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/PalmTreeGrower", null, OLD_GROWER, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, OLD_GROWER, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] saplingCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ModBlocks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sapling",
                "(L" + PROPERTIES + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, SAPLING);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.NEW, "test/PalmTreeGrower");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "test/PalmTreeGrower", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SAPLING, "<init>",
                "(L" + OLD_GROWER + ";L" + PROPERTIES + ";)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
}
