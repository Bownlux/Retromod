/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The 1.20.4 SynchedEntityData mixin shape Fossils and Archeology ships (#308). */
class LegacySynchedEntityDataMixinRepairTest {

    private static final String DATA = "net/minecraft/network/syncher/SynchedEntityData";
    private static final String ITEM = DATA + "$DataItem";
    private static final String ACCESSOR = "net/minecraft/network/syncher/EntityDataAccessor";
    private static final String MAP = "it/unimi/dsi/fastutil/ints/Int2ObjectMap";
    private static final String LOCK = "java/util/concurrent/locks/ReadWriteLock";
    private static final String MIXIN = "test/mixin/SyncedEntityDataMixin";

    @Test
    void retargetsTheLockAndMapShadowsOntoThe1205Shape() {
        byte[] out = LegacySynchedEntityDataMixinRepair.apply(mixin(false), () -> true);
        ClassNode node = read(out);
        assertTrue(node.fields.stream().noneMatch(f -> f.name.equals("lock")),
                "the lock shadow must go: 1.20.5 has no lock field to shadow");
        assertEquals("[L" + ITEM + ";", node.fields.stream()
                .filter(f -> f.name.equals("itemsById")).findFirst().orElseThrow().desc);
        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(LOCK), "no lock call may survive");
        assertFalse(text.contains(MAP), "no Int2ObjectMap call may survive");
        assertTrue(hasOpcode(method(node, "markDirty"), AALOAD), "get(id) becomes an array load");
    }

    @Test
    void unknownFieldUseLeavesTheMixinAlone() {
        byte[] in = mixin(true);
        assertSame(in, LegacySynchedEntityDataMixinRepair.apply(in, () -> true),
                "a shape the repair does not understand must still fail visibly");
    }

    @Test
    void hostThatStillHasTheLockIsLeftAlone() {
        byte[] in = mixin(false);
        assertSame(in, LegacySynchedEntityDataMixinRepair.apply(in, () -> false));
    }

    private static byte[] mixin(boolean extraMapUse) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, MIXIN, null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(DATA));
        targets.visitEnd();
        mixin.visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "lock", "L" + LOCK + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "itemsById", "L" + MAP + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE, "isDirty", "Z", null, null).visitEnd();

        MethodVisitor all = cw.visitMethod(ACC_PUBLIC, "markAllDirty", "()V", null, null);
        all.visitCode();
        lockCall(all, "lock");
        all.visitVarInsn(ALOAD, 0);
        all.visitFieldInsn(GETFIELD, MIXIN, "itemsById", "L" + MAP + ";");
        all.visitMethodInsn(INVOKEINTERFACE, MAP, "values", "()Lit/unimi/dsi/fastutil/objects/ObjectCollection;", true);
        all.visitMethodInsn(INVOKEINTERFACE, "it/unimi/dsi/fastutil/objects/ObjectCollection", "iterator",
                "()Lit/unimi/dsi/fastutil/objects/ObjectIterator;", true);
        all.visitVarInsn(ASTORE, 1);
        Label loop = new Label();
        Label done = new Label();
        all.visitLabel(loop);
        all.visitVarInsn(ALOAD, 1);
        all.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        all.visitJumpInsn(IFEQ, done);
        all.visitVarInsn(ALOAD, 1);
        all.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        all.visitTypeInsn(CHECKCAST, ITEM);
        all.visitInsn(ICONST_1);
        all.visitMethodInsn(INVOKEVIRTUAL, ITEM, "setDirty", "(Z)V", false);
        all.visitJumpInsn(GOTO, loop);
        all.visitLabel(done);
        lockCall(all, "unlock");
        if (extraMapUse) {
            all.visitVarInsn(ALOAD, 0);
            all.visitFieldInsn(GETFIELD, MIXIN, "itemsById", "L" + MAP + ";");
            all.visitMethodInsn(INVOKEINTERFACE, MAP, "size", "()I", true);
            all.visitInsn(POP);
        }
        all.visitInsn(RETURN);
        all.visitMaxs(0, 0);
        all.visitEnd();

        MethodVisitor one = cw.visitMethod(ACC_PUBLIC, "markDirty", "(L" + ACCESSOR + ";)V", null, null);
        one.visitCode();
        one.visitVarInsn(ALOAD, 0);
        one.visitFieldInsn(GETFIELD, MIXIN, "itemsById", "L" + MAP + ";");
        one.visitVarInsn(ALOAD, 1);
        one.visitMethodInsn(INVOKEVIRTUAL, ACCESSOR, "getId", "()I", false);
        one.visitMethodInsn(INVOKEINTERFACE, MAP, "get", "(I)Ljava/lang/Object;", true);
        one.visitTypeInsn(CHECKCAST, ITEM);
        one.visitInsn(ICONST_1);
        one.visitMethodInsn(INVOKEVIRTUAL, ITEM, "setDirty", "(Z)V", false);
        one.visitInsn(RETURN);
        one.visitMaxs(0, 0);
        one.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void lockCall(MethodVisitor m, String action) {
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, MIXIN, "lock", "L" + LOCK + ";");
        m.visitMethodInsn(INVOKEINTERFACE, LOCK, "readLock", "()Ljava/util/concurrent/locks/Lock;", true);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/concurrent/locks/Lock", action, "()V", true);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static boolean hasOpcode(MethodNode method, int opcode) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) return true;
        }
        return false;
    }
}
