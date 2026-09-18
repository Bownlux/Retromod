/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

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
 * On NeoForge every shim and polyfill shares one transformer. The Fabric polyfill folds
 * {@code FabricBlockSettings.of()} into {@code Properties.of()}, and the alias it left behind replaced
 * the NeoForge id bridge on {@code Properties.of()}. Macaw's Bridges then failed at registration with
 * {@code Block id not set}. The offline CLI loads no Fabric polyfill, so only the game showed it.
 */
class MethodRedirectAliasTest {

    private static final String PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String WRAPPER = "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings";
    private static final String BRIDGE = "test/IdBridge";

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
    @DisplayName("a wrapper folded into its target does not erase that target's own redirect")
    void identityAliasKeepsExistingRedirect() {
        transformer.registerMethodRedirect(PROPS, "of", "()L" + PROPS + ";",
                BRIDGE, "blockOf", "()L" + PROPS + ";");

        transformer.registerClassRedirect(WRAPPER, PROPS);
        transformer.registerMethodRedirect(WRAPPER, "of", "()L" + WRAPPER + ";",
                PROPS, "of", "()L" + PROPS + ";");

        MethodInsnNode call = firstCall(transformer.transformClass(callerOf(PROPS), "test/Caller"));
        assertEquals(BRIDGE, call.owner, "Properties.of() must still reach the id bridge");
        assertEquals("blockOf", call.name);
    }

    private static byte[] callerOf(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "()Ljava/lang/Object;",
                null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, owner, "of", "()L" + PROPS + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode firstCall(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) return call;
            }
        }
        return fail("the call disappeared");
    }
}
