/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** An old Fabric elytra item gets the glider component and the chest slot through its properties. */
class LegacyFabricElytraItemBridgeTest {

    private static final String PROPS = LegacyFabricElytraItemBridge.PROPERTIES;

    @Test
    @DisplayName("the properties an elytra item hands its super constructor gain the glider")
    void elytraPropertiesGainGlider() throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/SoulElytra", null, "net/minecraft/world/item/Item",
                new String[] {LegacyFabricElytraItemBridge.STAND_IN});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + PROPS + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/world/item/Item", "<init>", "(L" + PROPS + ";)V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 2);
        init.visitEnd();
        cw.visitEnd();

        ClassNode fixed = new ClassNode();
        new ClassReader(LegacyFabricElytraItemBridge.apply(cw.toByteArray())).accept(fixed, 0);
        List<String> calls = new ArrayList<>();
        fixed.methods.get(0).instructions.forEach(i -> { if (i instanceof MethodInsnNode c) calls.add(c.owner + "." + c.name); });
        assertEquals(List.of(LegacyFabricElytraItemBridge.STAND_IN + ".withGlider", "net/minecraft/world/item/Item.<init>"), calls);

        ClassNode standIn = new ClassNode();
        new ClassReader(LegacyFabricElytraItemBridge.generate()).accept(standIn, 0);
        for (MethodNode method : standIn.methods) new Analyzer<>(new BasicVerifier()).analyze(standIn.name, method);
    }
}
