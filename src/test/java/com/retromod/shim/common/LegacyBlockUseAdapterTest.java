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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Utami's Futon (#338), a 1.20.1 Fabric bed, overrides {@code use} and calls {@code super.use}.
 * 1.20.5 split that method into {@code useWithoutItem} and {@code useItemOn}, so on 1.21.1 the
 * override never ran and the super call named a method the bed no longer has.
 */
class LegacyBlockUseAdapterTest {

    private static final LegacyBlockUseAdapter.Names I = LegacyBlockUseAdapter.INTERMEDIARY;
    private static final LegacyBlockUseAdapter.Names M = LegacyBlockUseAdapter.MOJANG;

    @Test
    @DisplayName("an old use override gains useWithoutItem, and super.use reaches the bed's useWithoutItem")
    void futonShapedBlockIsRouted() throws Exception {
        byte[] futon = block("com/utami/FutonBlock", "net/minecraft/class_2244", I, true);
        ClassNode out = read(LegacyBlockUseAdapter.apply(futon, List.of(I), name -> null));

        MethodNode bridge = method(out, I.newName(), I.newDesc());
        assertNotNull(bridge, "the futon needs a useWithoutItem the game will call");
        MethodInsnNode call = first(bridge, MethodInsnNode.class);
        assertEquals("com/utami/FutonBlock", call.owner);
        assertEquals(I.oldName(), call.name);
        assertEquals("field_5808", first(bridge, FieldInsnNode.class).name, "the old method gets the main hand");

        MethodInsnNode superCall = first(method(out, I.oldName(), I.oldDesc()), MethodInsnNode.class);
        assertEquals("net/minecraft/class_2244", superCall.owner);
        assertEquals(I.newName(), superCall.name);
        assertEquals(I.newDesc(), superCall.desc);
        for (MethodNode method : out.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(out.name, method);
        }
    }

    @Test
    @DisplayName("Mojang names get the same repair, and a super call to a mod parent with use is kept")
    void modParentKeepsItsOwnUse() {
        byte[] parent = block("mod/BaseBlock", "net/minecraft/world/level/block/Block", M, false);
        byte[] child = block("mod/ChildBlock", "mod/BaseBlock", M, true);
        Map<String, byte[]> jar = Map.of("mod/BaseBlock", parent);

        ClassNode out = read(LegacyBlockUseAdapter.apply(child, List.of(M), jar::get));
        assertNotNull(method(out, M.newName(), M.newDesc()));
        MethodInsnNode superCall = first(method(out, M.oldName(), M.oldDesc()), MethodInsnNode.class);
        assertEquals(M.oldName(), superCall.name, "BaseBlock still has use, and gets its own bridge");
    }

    @Test
    @DisplayName("a class that already has useWithoutItem keeps it")
    void existingUseWithoutItemIsKept() {
        ClassWriter cw = writer("mod/Dual", "net/minecraft/world/level/block/Block");
        useMethod(cw, M, M.oldName(), M.oldDesc(), null);
        useMethod(cw, M, M.newName(), M.newDesc(), null);
        byte[] bytes = cw.toByteArray();
        assertSame(bytes, LegacyBlockUseAdapter.apply(bytes, List.of(M), name -> null));
    }

    @Test
    @DisplayName("offline, only 1.20.5 and newer targets have the split")
    void offlineGate() {
        assertFalse(LegacyBlockUseAdapter.offlineNeedsRepair("1.20.4"));
        assertTrue(LegacyBlockUseAdapter.offlineNeedsRepair("1.20.5"));
        assertTrue(LegacyBlockUseAdapter.offlineNeedsRepair("1.21.1"));
        assertTrue(LegacyBlockUseAdapter.offlineNeedsRepair("26.3"));
    }

    private static byte[] block(String name, String parent, LegacyBlockUseAdapter.Names names, boolean callsSuper) {
        ClassWriter cw = writer(name, parent);
        useMethod(cw, names, names.oldName(), names.oldDesc(), callsSuper ? parent : null);
        return cw.toByteArray();
    }

    private static ClassWriter writer(String name, String parent) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, parent, null);
        return cw;
    }

    /** A use method that returns {@code super.use(...)} when {@code superOwner} is given, else null. */
    private static void useMethod(ClassWriter cw, LegacyBlockUseAdapter.Names names, String name, String desc,
                                  String superOwner) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        if (superOwner != null) {
            for (int local = 0; local <= 6; local++) mv.visitVarInsn(ALOAD, local);
            mv.visitMethodInsn(INVOKESPECIAL, superOwner, names.oldName(), names.oldDesc(), false);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static <T> T first(MethodNode method, Class<T> type) {
        return Arrays.stream(method.instructions.toArray()).filter(type::isInstance).map(type::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " in " + method.name));
    }
}
