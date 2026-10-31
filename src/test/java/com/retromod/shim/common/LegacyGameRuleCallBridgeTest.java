/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE reads {@code level.getGameRules().getBoolean(rule)}, which 26.x answers only on a server level with {@code get(rule)}. */
class LegacyGameRuleCallBridgeTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Level game rules and typed rule reads reach the generated helpers")
    void ruleReadsAreRedirected() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyGameRuleCallBridge.register(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(reader(), "mod/Rules.class")).accept(node, 0);

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        String helper = LegacyGameRuleCallBridge.CALLS;
        assertEquals(List.of(helper + ".gameRules", helper + ".getBoolean"), calls);
    }

    @Test
    @DisplayName("The generated helper verifies")
    void generatedHelperVerifies() {
        new ClassReader(LegacyGameRuleCallBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    /** The 1.21.1 shape, after the 26.1 class move. */
    private static byte[] reader() {
        String rules = "net/minecraft/world/level/gamerules/GameRules";
        String rule = "net/minecraft/world/level/gamerules/GameRule";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Rules", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "decay", "(Lnet/minecraft/world/level/Level;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/Level", "getGameRules", "()L" + rules + ";", false);
        mv.visitFieldInsn(GETSTATIC, rules, "TNT_EXPLOSION_DROP_DECAY", "L" + rule + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, rules, "getBoolean", "(L" + rule + ";)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
