/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.LegacyColorClasses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.*;

/** A Fabric mod's BlockColors.getColor reaches the tint-source lookup on 26.x, and a host that still has it keeps the call. */
class FabricBlockColorsGetColorTest {

    private static final String DESC = "(" + LegacyColorClasses.COLOR_ARGS + ")I";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("getColor goes to the helper that reads the host's tint sources")
    void getColorIsBridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyColorClasses.registerHostGetColor(t, "26.1");

        List<String> calls = calls(t.transformClass(callsGetColor(), "test/colors/Old"));

        // A class with no mod version counts as older than 1.21, so it gets the RGB answer; a 1.21 mod gets getColor.
        String args = "(L" + LegacyColorClasses.BLOCK_COLORS + ";" + LegacyColorClasses.COLOR_ARGS + ")I";
        assertTrue(calls.contains(LegacyColorClasses.CALLS + ".getColor" + args) || calls.contains(LegacyColorClasses.CALLS + ".getColorRgb" + args),
                calls.toString());
        assertFalse(calls.contains(LegacyColorClasses.BLOCK_COLORS + ".getColor" + DESC), calls.toString());
    }

    @Test
    @DisplayName("the bridge applies only where BlockColors lost the old getColor")
    void gate() {
        ClassNode old = new ClassNode();
        old.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getColor", DESC, null, null));
        assertFalse(Fabric_1_21_11_to_26_1.blockColorsLostGetColor(old));
        assertTrue(Fabric_1_21_11_to_26_1.blockColorsLostGetColor(new ClassNode()));
    }

    private static byte[] callsGetColor() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/colors/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
        mv.visitCode();
        for (int i = 0; i < 4; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyColorClasses.BLOCK_COLORS, "getColor", DESC, false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(5, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
