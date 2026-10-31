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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.1 mods ask {@code ModList.get().isLoaded(id)}; Forge 26.2 made those methods static. */
class LegacyModListBridgeTest {

    private static final String MOD_LIST = LegacyModListBridge.MOD_LIST;
    private static final String HELPER = LegacyModListBridge.MODS.helper();

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old instance call reaches the helper, and a newer mod's static call is left alone")
    void instanceCallsReachTheHelper() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyModListBridge.registerBridge(t, LegacyModListBridge.MODS);

        List<String> calls = calls(t.transformClass(caller(), "mod/Compat.class"));

        assertTrue(calls.contains(INVOKESTATIC + " " + HELPER + ".get()L" + MOD_LIST + ";"), calls.toString());
        assertTrue(calls.contains(INVOKESTATIC + " " + HELPER + ".isLoaded(L" + MOD_LIST + ";Ljava/lang/String;)Z"), calls.toString());
        assertTrue(calls.contains(INVOKESTATIC + " " + MOD_LIST + ".isLoaded(Ljava/lang/String;)Z"),
                "the static call a newer mod makes has no receiver to pass: " + calls);
    }

    @Test
    @DisplayName("The generated helpers verify")
    void helpersVerify() {
        for (LegacyModListBridge.StaticApi api : List.of(LegacyModListBridge.MODS, LegacyModListBridge.LOADING_MODS)) {
            new ClassReader(LegacyModListBridge.generate(api)).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    /** {@code ModList.get().isLoaded("jei")}, then the static form a newer mod writes. */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Compat", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "check", "()Z", null, null);
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, MOD_LIST, "get", "()L" + MOD_LIST + ";", false);
        m.visitLdcInsn("jei");
        m.visitMethodInsn(INVOKEVIRTUAL, MOD_LIST, "isLoaded", "(Ljava/lang/String;)Z", false);
        m.visitInsn(POP);
        m.visitLdcInsn("jei");
        m.visitMethodInsn(INVOKESTATIC, MOD_LIST, "isLoaded", "(Ljava/lang/String;)Z", false);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
