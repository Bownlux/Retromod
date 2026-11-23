/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Forge's KeyModifier lookups reach NeoForge's only where the host declares NeoForge's forms. */
class KeyModifierLookupsTest {

    private static final String MODIFIER = ForgeNeoForgeSameShapeMoves.KEY_MODIFIER;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a host with getActiveModifiers takes the bridge; one without it keeps the call")
    void hostDecides() {
        ClassNode host = new ClassNode();
        host.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getActiveModifiers", "()Ljava/util/List;", null, null));
        host.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getKeyModifier",
                "(Lcom/mojang/blaze3d/platform/InputConstants$Key;)L" + MODIFIER + ";", null, null));
        assertTrue(calls(host).contains(ForgeNeoForgeSameShapeMoves.KEY_MODIFIERS + ".getActiveModifier"));
        assertTrue(calls(new ClassNode()).contains(MODIFIER + ".getActiveModifier"));
    }

    private static List<String> calls(ClassNode host) {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ForgeNeoForgeSameShapeMoves.registerKeyModifierLookups(t, host);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Keys", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "held", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MODIFIER, "getActiveModifier", "()L" + MODIFIER + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode out = new ClassNode();
        new ClassReader(t.transformClass(cw.toByteArray(), "test/Keys")).accept(out, 0);
        List<String> calls = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insn -> { if (insn instanceof MethodInsnNode c) calls.add(c.owner + "." + c.name); }));
        return calls;
    }
}
