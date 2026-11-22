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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 mod's int[] quad calls reach the rebuilt quad view on a 26.x host of any loader. */
class LegacyQuadGettersBridgeTest {

    private static final String OLD_QUAD = LegacyQuadGettersBridge.OLD_QUAD;
    private static final String VIEW = LegacyQuadGettersBridge.QUAD_VERTICES;
    private static final String SPRITE = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";
    private static final String DIRECTION = "Lnet/minecraft/core/Direction;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("getters, getDirection and the constructor go to the quad view on the 26.x record")
    void quadCallsReachTheView() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyQuadGettersBridge.register(t, name -> name.equals(LegacyQuadGettersBridge.QUAD) ? recordQuad() : null);

        List<String> calls = calls(t.transformClass(oldQuadCalls(), "test/render/OldQuads"));

        String quad = "L" + LegacyQuadGettersBridge.QUAD + ";";
        assertTrue(calls.contains(VIEW + ".create([II" + DIRECTION + SPRITE + "Z)" + quad), calls.toString());
        assertTrue(calls.contains(VIEW + ".getSprite(" + quad + ")" + SPRITE), calls.toString());
        assertTrue(calls.contains(VIEW + ".getVertices(" + quad + ")[I"), calls.toString());
        assertTrue(calls.contains(VIEW + ".getTintIndex(" + quad + ")I"), calls.toString());
        assertTrue(calls.contains(LegacyQuadGettersBridge.QUAD + ".direction()" + DIRECTION), calls.toString());
    }

    @Test
    @DisplayName("a host whose quad still has the int[] getters gets no redirect")
    void olderHostKeepsItsQuad() {
        ClassNode old = new ClassNode();
        old.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getVertices", "()[I", null, null));
        assertFalse(LegacyQuadGettersBridge.isRecordQuad(old));
        ClassNode record1215 = new ClassNode();
        record1215.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "vertices", "()[I", null, null));
        assertFalse(LegacyQuadGettersBridge.isRecordQuad(record1215), "the 1.21.5 record still has its int[]");
        assertTrue(LegacyQuadGettersBridge.isRecordQuad(recordQuad()));
    }

    private static ClassNode recordQuad() {
        ClassNode node = new ClassNode();
        node.name = LegacyQuadGettersBridge.QUAD;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "materialInfo",
                "()L" + LegacyQuadGettersBridge.QUAD + "$MaterialInfo;", null, null));
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "direction", "()" + DIRECTION, null, null));
        return node;
    }

    private static byte[] oldQuadCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/render/OldQuads", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, OLD_QUAD);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OLD_QUAD, "<init>", "([II" + DIRECTION + SPRITE + "Z)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        for (String[] getter : new String[][]{{"getSprite", SPRITE}, {"getVertices", "[I"}, {"getTintIndex", "I"},
                {"getDirection", DIRECTION}}) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OLD_QUAD, getter[0], "()" + getter[1], false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }));
        return calls;
    }
}
