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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Old BufferUploader and projection calls reach Retromod's helpers on hosts that removed them. */
class LegacyBufferUploaderBridgeTest {

    private static final String UPLOADER = LegacyBufferUploaderBridge.UPLOADER;
    private static final String RS = LegacyBufferUploaderBridge.RENDER_SYSTEM;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("every BufferUploader call the corpus makes goes to the helper")
    void uploaderCallsAreRedirected() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyBufferUploaderBridge.registerUploader(t);

        List<String> calls = calls(t.transformClass(oldUploaderCalls(), "test/render/OldUpload"));

        String helper = LegacyBufferUploaderBridge.HELPER;
        assertEquals(List.of(helper + ".drawWithShader(Ljava/lang/Object;)V", helper + ".draw(Ljava/lang/Object;)V",
                helper + ".reset()V", helper + ".reset()V"), calls);
    }

    @Test
    @DisplayName("the matrix projection calls go to the rebuilt projection state")
    void projectionCallsAreRedirected() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyBufferUploaderBridge.registerProjection(t);

        List<String> calls = calls(t.transformClass(oldProjectionCalls(), "test/render/OldProjection"));

        assertTrue(calls.stream().allMatch(call -> call.startsWith(LegacyBufferUploaderBridge.PROJECTION + ".")), calls.toString());
        assertEquals(3, calls.size(), calls.toString());
    }

    @Test
    @DisplayName("a host that still has the matrix setProjectionMatrix is left alone")
    void projectionGateFollowsTheHost() {
        ClassNode oldHost = renderSystem(LegacyBufferUploaderBridge.OLD_SET_PROJECTION);
        ClassNode newHost = renderSystem("(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V");

        assertFalse(LegacyBufferUploaderBridge.matrixProjectionMissing(oldHost, true));
        assertTrue(LegacyBufferUploaderBridge.matrixProjectionMissing(newHost, true));
        assertTrue(LegacyBufferUploaderBridge.matrixProjectionMissing(null, false), "offline the shim's host gate decides");
        assertFalse(LegacyBufferUploaderBridge.uploaderMissing(new ClassNode()), "a host with BufferUploader keeps its calls");
    }

    private static ClassNode renderSystem(String setProjectionDesc) {
        ClassNode node = new ClassNode();
        node.name = RS;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setProjectionMatrix", setProjectionDesc, null, null));
        return node;
    }

    private static byte[] oldUploaderCalls() {
        return method("test/render/OldUpload", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, UPLOADER, "drawWithShader", "(" + LegacyBufferUploaderBridge.MESH + ")V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, UPLOADER, "draw", "(" + LegacyBufferUploaderBridge.MESH + ")V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, UPLOADER, "reset", "()V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, UPLOADER, "invalidate", "()V", false);
        });
    }

    private static byte[] oldProjectionCalls() {
        return method("test/render/OldProjection", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "getProjectionMatrix", "()" + LegacyBufferUploaderBridge.MATRIX, false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "getVertexSorting", "()" + LegacyBufferUploaderBridge.SORTING, false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "setProjectionMatrix", LegacyBufferUploaderBridge.OLD_SET_PROJECTION, false);
        });
    }

    private static byte[] method(String name, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
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
