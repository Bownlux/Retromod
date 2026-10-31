/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE reads {@code level.isClientSide} and {@code level.random}, which newer versions hid behind getters. */
class LegacyLevelFieldBridgeTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Field reads of isClientSide and random become their getters")
    void readsUseTheGetters() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyLevelFieldBridge.register(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(reader(), "mod/Reader.class")).accept(node, 0);

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.name);
            assertFalse(insn instanceof FieldInsnNode field && field.owner.equals("net/minecraft/world/level/Level"),
                    "no field read of Level is left");
        }
        assertEquals(List.of("isClientSide", "getRandom"), calls);
    }

    private static byte[] reader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Reader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(Lnet/minecraft/world/level/Level;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "net/minecraft/world/level/Level", "isClientSide", "Z");
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "net/minecraft/world/level/Level", "random", "Lnet/minecraft/util/RandomSource;");
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
