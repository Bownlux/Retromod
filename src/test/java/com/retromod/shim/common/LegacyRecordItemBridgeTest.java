/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #408 (Monke Madness, Forge 1.18.2 on Forge 1.20.1): a 1.18 music disc has no length, which 1.19 made a constructor argument. */
class LegacyRecordItemBridgeTest {

    private static final String RECORD = "net/minecraft/world/item/RecordItem";
    private static final String PROPERTIES = "Lnet/minecraft/world/item/Item$Properties;";
    private static final String SUPPLIER_CTOR = "(ILjava/util/function/Supplier;" + PROPERTIES + ")V";
    private static final String SOUND_CTOR = "(ILnet/minecraft/sounds/SoundEvent;" + PROPERTIES + ")V";
    private static final String MOD = "test/discs/ModItems";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyRecordItemBridge.register(transformer, LegacyRecordItemBridge.MOJANG);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new RecordItem(analog, soundSupplier, properties) builds the disc through the helper")
    void forgeSupplierDiscUsesTheHelper() {
        MethodInsnNode call = onlyCall(build(SUPPLIER_CTOR, "java/util/function/Supplier"));
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyRecordItemBridge.HELPER, call.owner);
        assertEquals("(ILjava/util/function/Supplier;" + PROPERTIES + ")L" + RECORD + ";", call.desc);
    }

    @Test
    @DisplayName("the vanilla constructor taking the sound itself goes through the helper too")
    void vanillaDiscUsesTheHelper() {
        MethodInsnNode call = onlyCall(build(SOUND_CTOR, "net/minecraft/sounds/SoundEvent"));
        assertEquals(LegacyRecordItemBridge.HELPER, call.owner);
    }

    @Test
    @DisplayName("a disc subclass passes the default length to its super call, in ticks for Forge's supplier constructor")
    void subclassSuperCallGetsTheLength() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, RECORD, null);
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", SUPPLIER_CTOR, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ILOAD, 1);
        ctor.visitVarInsn(ALOAD, 2);
        ctor.visitVarInsn(ALOAD, 3);
        ctor.visitMethodInsn(INVOKESPECIAL, RECORD, "<init>", SUPPLIER_CTOR, false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), MOD)).accept(cn, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        cn.methods.get(0).instructions.forEach(insns::add);
        FieldInsnNode length = insns.stream().filter(FieldInsnNode.class::isInstance).map(FieldInsnNode.class::cast)
                .findFirst().orElseThrow();
        assertEquals(LegacyRecordItemBridge.HELPER, length.owner);
        assertEquals("LENGTH_TICKS", length.name);
        assertEquals("(ILjava/util/function/Supplier;" + PROPERTIES + "I)V", onlyCall(insns).desc);
    }

    @Test
    @DisplayName("the helper passes seconds to the vanilla constructor and ticks to Forge's")
    void helperUsesEachConstructorsUnit() {
        byte[] helper = LegacyRecordItemBridge.generateHelper(LegacyRecordItemBridge.MOJANG);
        new ClassReader(helper).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode cn = new ClassNode();
        new ClassReader(helper).accept(cn, 0);
        for (MethodNode method : cn.methods) {
            if (!method.name.equals("create")) continue;
            Object length = null;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc) length = ldc.cst;
            }
            int expected = method.desc.contains("Supplier") ? 180 * 20 : 180;
            assertEquals(expected, length, method.desc);
        }
        byte[] fabric = LegacyRecordItemBridge.generateHelper(LegacyRecordItemBridge.INTERMEDIARY);
        ClassNode intermediary = new ClassNode();
        new ClassReader(fabric).accept(intermediary, 0);
        assertEquals(1, intermediary.methods.stream().filter(m -> m.name.equals("create")).count(),
                "vanilla has no supplier constructor");
    }

    private List<AbstractInsnNode> build(String ctor, String soundType) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(L" + soundType + ";" + PROPERTIES + ")V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, RECORD);
        mv.visitInsn(DUP);
        mv.visitIntInsn(BIPUSH, 15);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, RECORD, "<init>", ctor, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), MOD)).accept(cn, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals("run")) m.instructions.forEach(insns::add);
        }
        return insns;
    }

    private static MethodInsnNode onlyCall(List<AbstractInsnNode> insns) {
        List<MethodInsnNode> calls = insns.stream().filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).toList();
        assertEquals(1, calls.size(), "expected one call, found " + calls.size());
        return calls.get(0);
    }
}
