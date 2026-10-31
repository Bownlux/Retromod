/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

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

/**
 * #366: ProjectE builds one {@code Properties} for its torch and wall torch before either is
 * registered, so 26.2 refused both with "Block id not set".
 */
class ConstructorIdStamperTest {

    private static final String PROPERTIES = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private static final String BRIDGE = "com/retromod/shim/forge/embedded/RegistryIdBridge";

    @Test
    @DisplayName("A Minecraft block constructor takes its properties through the id bridge, a mod's own does not")
    void blockConstructorsAreStamped() {
        ClassNode torch = read(ConstructorIdStamper.apply(torch(), BRIDGE));

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : torch.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        assertEquals(List.of(BRIDGE + ".applyBlock", "net/minecraft/world/level/block/TorchBlock.<init>"), calls);
        new ClassReader(ConstructorIdStamper.apply(torch(), BRIDGE)).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("Properties before another argument, as in NeoForge's sword constructor, are stamped too")
    void propertiesBeforeTheLastArgumentAreStamped() {
        String item = "Lnet/minecraft/world/item/Item$Properties;";
        String tool = "Lnet/minecraft/world/item/component/Tool;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Sword", null, "com/retromod/generated/LegacySwordItem", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + item + tool + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitInsn(ACONST_NULL);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, "com/retromod/generated/LegacySwordItem", "<init>",
                "(Ljava/lang/Object;" + item + tool + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();

        byte[] stamped = ConstructorIdStamper.apply(cw.toByteArray(), BRIDGE);

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : read(stamped).methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        assertEquals(List.of(BRIDGE + ".applyItem", "com/retromod/generated/LegacySwordItem.<init>"), calls);
        new ClassReader(stamped).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    /** {@code class Torch extends TorchBlock { Torch(Properties p) { super(FLAME, p); } }}. */
    private static byte[] torch() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Torch", null, "net/minecraft/world/level/block/TorchBlock", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + PROPERTIES + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitInsn(ACONST_NULL);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/level/block/TorchBlock", "<init>",
                "(Lnet/minecraft/core/particles/SimpleParticleType;" + PROPERTIES + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
