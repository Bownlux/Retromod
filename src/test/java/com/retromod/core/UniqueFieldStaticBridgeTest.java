/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

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
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * More Armor, an MCreator 1.12.2 mod on NeoForge 1.21.1: its item template set
 * {@code this.maxStackSize} from its own class, a field 1.20.5 replaced with a component, so every
 * item constructor failed with {@code NoSuchFieldError}.
 */
class UniqueFieldStaticBridgeTest {

    private static final String BRIDGE = "com/retromod/mixin/runtime/ItemComponentsBridge";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a write and a read of the SRG field through the mod's own class become bridge calls")
    void srgFieldThroughModClassIsBridged() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerSrgNameMappings(Map.of(), Map.of("field_77777_bU", "maxStackSize"));
        transformer.registerUniqueFieldStaticBridge("field_77777_bU", "I", BRIDGE,
                "maxStackSize", "(Ljava/lang/Object;)I", "setMaxStackSize", "(Ljava/lang/Object;I)V");

        ClassNode out = read(transformer.transformClass(itemTemplate(), "mod/ItemCustom.class"));

        List<String> calls = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        for (MethodNode method : out.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) calls.add(call.name + call.desc);
                if (insn instanceof FieldInsnNode field) fields.add(field.name);
            }
        }
        assertEquals(List.of("setMaxStackSize(Ljava/lang/Object;I)V", "maxStackSize(Ljava/lang/Object;)I"), calls);
        assertEquals(List.of(), fields, "no access to the removed field may survive");
    }

    /** {@code ItemCustom() { this.maxStackSize = 64; int read = this.maxStackSize; }}, extending a mod base. */
    private static byte[] itemTemplate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/ItemCustom", null, "mod/ItemBase", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "init", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitIntInsn(BIPUSH, 64);
        mv.visitFieldInsn(PUTFIELD, "mod/ItemCustom", "field_77777_bU", "I");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "mod/ItemCustom", "field_77777_bU", "I");
        mv.visitInsn(IRETURN);
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
}
