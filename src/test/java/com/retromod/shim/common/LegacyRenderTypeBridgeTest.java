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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.21.1 mod's render type holder reaches the rebuilt shards, state and resolver on a 26.3 host. */
class LegacyRenderTypeBridgeTest {

    private static final String MOD = "test/rt/ModRenderTypes";
    private static final String SHARD_TYPE = LegacyRenderTypeBridge.OLD_SHARD;
    private static final String STATE = LegacyRenderTypeBridge.OLD_STATE;
    private static final String BUILDER = STATE + "$CompositeStateBuilder";
    private static final String OLD_CREATE = "(Ljava/lang/String;" + LegacyRenderTypeBridge.HOST_FORMAT + LegacyRenderTypeBridge.HOST_TOPOLOGY
            + "IZZL" + STATE + ";)L" + LegacyRenderTypeBridge.OLD_COMPOSITE_TYPE + ";";
    private static final String OLD_CTOR = "(Ljava/lang/String;" + LegacyRenderTypeBridge.HOST_FORMAT + LegacyRenderTypeBridge.HOST_TOPOLOGY
            + "IZZLjava/lang/Runnable;Ljava/lang/Runnable;)V";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the holder is rebased and its state, shards and create(...) go to the rebuilt classes")
    void holderIsRebuilt() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRenderTypeBridge.register(t, Map.of(LegacyRenderTypeBridge.SETUP_BUILDER, new ClassNode())::get,
                LegacyRenderTypeBridge.Shape.V26_3);

        byte[] out = t.transformClass(holder(), MOD);
        ClassNode node = node(out);

        assertEquals(LegacyRenderTypeBridge.HOLDER, node.superName, "the holder must extend the rebuilt base");
        List<String> refs = references(node);
        assertTrue(refs.contains(LegacyRenderTypeBridge.STATE + ".builder()L" + LegacyRenderTypeBridge.STATE + "$CompositeStateBuilder;"),
                refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyRenderTypeBridge.HOLDER + ".create(")), refs.toString());
        assertTrue(refs.contains(LegacyRenderTypeBridge.HOLDER + ".<init>" + OLD_CTOR), "the super call must reach the holder: " + refs);
        assertTrue(refs.stream().anyMatch(r -> r.startsWith("F " + LegacyRenderTypeBridge.SHARD + ".RENDERTYPE_ENTITY_TRANSLUCENT_SHADER ")),
                refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains(SHARD_TYPE + ".") || r.contains(STATE + ".") || r.contains("$CompositeRenderType")),
                "no old shard, state or composite type may remain: " + refs);
    }

    @Test
    @DisplayName("render type calls the host no longer answers go to the resolver, and mode() to primitiveTopology()")
    void instanceCalls() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRenderTypeBridge.registerInstanceCalls(t, LegacyRenderTypeBridge.Shape.V26_3);

        List<String> refs = references(node(t.transformClass(method("test/rt/Calls", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderTypeBridge.HOST_TYPE, "setupRenderState", "()V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderTypeBridge.HOST_TYPE, "bufferSize", "()I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderTypeBridge.HOST_TYPE, "mode", "()" + LegacyRenderTypeBridge.HOST_TOPOLOGY, false);
            mv.visitInsn(Opcodes.POP);
        }), "test/rt/Calls")));

        String resolver = LegacyRenderTypeBridge.RESOLVER;
        assertTrue(refs.contains(resolver + ".setupRenderState(Ljava/lang/Object;)V"), refs.toString());
        assertTrue(refs.contains(resolver + ".bufferSize(Ljava/lang/Object;)I"), refs.toString());
        assertTrue(refs.contains(LegacyRenderTypeBridge.HOST_TYPE + ".primitiveTopology()" + LegacyRenderTypeBridge.HOST_TOPOLOGY), refs.toString());
    }

    @Test
    @DisplayName("each host gets its shape from its render setup builder, and a host with the old state gets none")
    void shapes() {
        ClassNode v261 = builder("setOutputTarget", "bufferSize");
        ClassNode v262 = builder("setOutputTarget");
        assertEquals(LegacyRenderTypeBridge.Shape.V26_1, LegacyRenderTypeBridge.shape(v261, null));
        assertEquals(LegacyRenderTypeBridge.Shape.V26_2, LegacyRenderTypeBridge.shape(v262, null));
        assertEquals(LegacyRenderTypeBridge.Shape.V26_3, LegacyRenderTypeBridge.shape(new ClassNode(), null));
        assertNull(LegacyRenderTypeBridge.shape(new ClassNode(), new ClassNode()), "a host with the old state keeps it");
        assertEquals(LegacyRenderTypeBridge.Shape.V26_1, LegacyRenderTypeBridge.shapeForVersion("1.21.11"),
                "1.21.11 has 26.1's render setup shape");
        assertNull(LegacyRenderTypeBridge.shapeForVersion("1.21.10"));
        assertTrue(LegacyRenderTypeBridge.shardConstants().contains("TRANSLUCENT_TRANSPARENCY"), "the constants are read from the rebuilt class");
    }

    @Test
    @DisplayName("on 26.1 the holder keeps the vertex mode, and the calls 26.1 still answers stay on the host")
    void holderOn261() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRenderTypeBridge.register(t, Map.of(LegacyRenderTypeBridge.SETUP_BUILDER, builder("setOutputTarget", "bufferSize"))::get,
                LegacyRenderTypeBridge.Shape.V26_1);

        ClassNode node = node(t.transformClass(holder261(), MOD));
        List<String> refs = references(node);

        assertEquals(LegacyRenderTypeBridge.HOLDER + "261", node.superName);
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyRenderTypeBridge.HOLDER + "261.<init>(Ljava/lang/String;"
                + LegacyRenderTypeBridge.OLD_FORMAT + LegacyRenderTypeBridge.OLD_MODE)), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyRenderTypeBridge.HOLDER + "261.create(")), refs.toString());
        assertTrue(refs.contains(LegacyRenderTypeBridge.HOST_TYPE + ".bufferSize()I"), "26.1 still has bufferSize(): " + refs);
        assertTrue(refs.contains(LegacyRenderTypeBridge.RESOLVER + ".setupRenderState(Ljava/lang/Object;)V"), refs.toString());
    }

    @Test
    @DisplayName("on 26.2 the holder takes the primitive topology, and bufferSize() and mode() are bridged")
    void instanceCallsOn262() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRenderTypeBridge.registerInstanceCalls(t, LegacyRenderTypeBridge.Shape.V26_2);
        String topology = LegacyRenderTypeBridge.Shape.V26_2.topology;

        List<String> refs = references(node(t.transformClass(method("test/rt/Calls262", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderTypeBridge.HOST_TYPE, "bufferSize", "()I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderTypeBridge.HOST_TYPE, "mode", "()" + topology, false);
            mv.visitInsn(Opcodes.POP);
        }), "test/rt/Calls262")));

        assertTrue(refs.contains(LegacyRenderTypeBridge.RESOLVER + ".bufferSize(Ljava/lang/Object;)I"), refs.toString());
        assertTrue(refs.contains(LegacyRenderTypeBridge.HOST_TYPE + ".primitiveTopology()" + topology), refs.toString());
    }

    private static ClassNode builder(String... methods) {
        ClassNode node = new ClassNode();
        for (String name : methods) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null));
        return node;
    }

    /** A 26.1 mod-side holder: the old constructor and create with the blaze3d format and vertex mode, and a bufferSize call. */
    private static byte[] holder261() {
        String ctor = "(Ljava/lang/String;" + LegacyRenderTypeBridge.OLD_FORMAT + LegacyRenderTypeBridge.OLD_MODE
                + "IZZLjava/lang/Runnable;Ljava/lang/Runnable;)V";
        String create = "(Ljava/lang/String;" + LegacyRenderTypeBridge.OLD_FORMAT + LegacyRenderTypeBridge.OLD_MODE
                + "IZZL" + STATE + ";)L" + LegacyRenderTypeBridge.OLD_COMPOSITE_TYPE + ";";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD, null, LegacyRenderTypeBridge.OLD_TYPE, null);
        MethodVisitor c = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", ctor, null, null);
        c.visitCode();
        for (int i = 0; i <= 8; i++) c.visitVarInsn(i >= 4 && i <= 6 ? Opcodes.ILOAD : Opcodes.ALOAD, i);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyRenderTypeBridge.OLD_TYPE, "<init>", ctor, false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(9, 9);
        c.visitEnd();
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "(L" + STATE + ";)I", null, null);
        mv.visitCode();
        mv.visitLdcInsn("old");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitIntInsn(Opcodes.SIPUSH, 256);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyRenderTypeBridge.OLD_TYPE, "create", create, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderTypeBridge.HOST_TYPE, "setupRenderState", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderTypeBridge.HOST_TYPE, "bufferSize", "()I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(8, 1);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A mod's holder: extends RenderType, builds a state from shards, and creates a type from it. */
    private static byte[] holder() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD, null, LegacyRenderTypeBridge.OLD_TYPE, null);
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", OLD_CTOR, null, null);
        ctor.visitCode();
        for (int i = 0; i <= 8; i++) ctor.visitVarInsn(i >= 4 && i <= 6 ? Opcodes.ILOAD : Opcodes.ALOAD, i);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyRenderTypeBridge.OLD_TYPE, "<init>", OLD_CTOR, false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(9, 9);
        ctor.visitEnd();
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glow",
                "()L" + LegacyRenderTypeBridge.OLD_COMPOSITE_TYPE + ";", null, null);
        mv.visitCode();
        mv.visitLdcInsn("glow");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitIntInsn(Opcodes.SIPUSH, 256);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, STATE, "builder", "()L" + BUILDER + ";", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, SHARD_TYPE, "RENDERTYPE_ENTITY_TRANSLUCENT_SHADER", "L" + SHARD_TYPE + "$ShaderStateShard;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "setShaderState", "(L" + SHARD_TYPE + "$ShaderStateShard;)L" + BUILDER + ";", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, "TRANSLUCENT_TRANSPARENCY", "L" + SHARD_TYPE + "$TransparencyStateShard;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "setTransparencyState",
                "(L" + SHARD_TYPE + "$TransparencyStateShard;)L" + BUILDER + ";", false);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "createCompositeState", "(Z)L" + STATE + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MOD, "create", OLD_CREATE, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(9, 0);
        mv.visitEnd();
        MethodVisitor direct = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "direct",
                "(L" + STATE + ";)L" + LegacyRenderTypeBridge.OLD_COMPOSITE_TYPE + ";", null, null);
        direct.visitCode();
        direct.visitLdcInsn("direct");
        direct.visitInsn(Opcodes.ACONST_NULL);
        direct.visitInsn(Opcodes.ACONST_NULL);
        direct.visitIntInsn(Opcodes.SIPUSH, 256);
        direct.visitInsn(Opcodes.ICONST_0);
        direct.visitInsn(Opcodes.ICONST_0);
        direct.visitVarInsn(Opcodes.ALOAD, 0);
        direct.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyRenderTypeBridge.OLD_TYPE, "create", OLD_CREATE, false);
        direct.visitInsn(Opcodes.ARETURN);
        direct.visitMaxs(8, 1);
        direct.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
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

    private static List<String> references(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            refs.add("D " + method.desc);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add("F " + field.owner + "." + field.name + " " + field.desc);
            }
        }
        return refs;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
