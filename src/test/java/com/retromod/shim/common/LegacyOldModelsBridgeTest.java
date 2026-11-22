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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Old models built on HierarchicalModel and AgeableListModel move onto the rebuilt bases and keep loading. */
class LegacyOldModelsBridgeTest {

    private static final String PART = "net/minecraft/client/model/geom/ModelPart";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a hierarchical model extends the rebuilt base, and its final-clashing overrides and root() calls are renamed")
    void hierarchicalModelMovesToRebuiltBase() {
        RetromodTransformer t = registered();

        ClassNode model = read(t.transformClass(oldModel(LegacyOldModelsBridge.HIERARCHICAL, "()V"), "test/models/Bat"));

        assertEquals(LegacyOldModelsBridge.HIERARCHICAL_BASE, model.superName);
        List<String> declared = model.methods.stream().map(m -> m.name + m.desc).toList();
        assertTrue(declared.contains("legacyRoot" + LegacyOldModelsBridge.ROOT_DESC), declared.toString());
        assertTrue(declared.contains("legacyRenderToBuffer" + LegacyOldModelsBridge.DRAW_DESC), declared.toString());
        assertFalse(declared.contains("root" + LegacyOldModelsBridge.ROOT_DESC), "the host's root() is final: " + declared);
        List<String> calls = calls(model);
        assertTrue(calls.contains("test/models/Bat.legacyRoot" + LegacyOldModelsBridge.ROOT_DESC)
                || calls.contains(LegacyOldModelsBridge.HIERARCHICAL_BASE + ".legacyRoot" + LegacyOldModelsBridge.ROOT_DESC), calls.toString());
        assertTrue(calls.contains(LegacyOldModelsBridge.HIERARCHICAL_BASE + ".<init>()V"), calls.toString());
    }

    @Test
    @DisplayName("an ageable list model extends the rebuilt base with its old constructor")
    void ageableModelMovesToRebuiltBase() {
        RetromodTransformer t = registered();

        ClassNode model = read(t.transformClass(oldModel(LegacyOldModelsBridge.AGEABLE, "(ZFF)V"), "test/models/Bat"));

        assertEquals(LegacyOldModelsBridge.AGEABLE_BASE, model.superName);
        assertTrue(calls(model).contains(LegacyOldModelsBridge.AGEABLE_BASE + ".<init>(ZFF)V"), calls(model).toString());
        assertTrue(model.methods.stream().anyMatch(m -> m.name.equals("legacyRenderToBuffer")), "the final draw override is renamed");
    }

    @Test
    @DisplayName("a host that still has the old bases keeps them")
    void hostWithOldBasesKeepsThem() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyOldModelsBridge.register(t, Map.of(LegacyOldModelsBridge.HIERARCHICAL, new ClassNode(),
                LegacyOldModelsBridge.AGEABLE, new ClassNode())::get);

        ClassNode model = read(t.transformClass(oldModel(LegacyOldModelsBridge.HIERARCHICAL, "()V"), "test/models/Bat"));

        assertEquals(LegacyOldModelsBridge.HIERARCHICAL, model.superName);
        assertFalse(t.getSyntheticClasses().containsKey(LegacyOldModelsBridge.HIERARCHICAL_BASE));
    }

    @Test
    @DisplayName("the old draw without a color goes to the five-argument draw with white where the host dropped it")
    void fourArgumentDrawUsesWhite() throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ClassNode host = new ClassNode();
        host.name = LegacyOldModelsBridge.MODEL;
        LegacyOldModelsBridge.registerFourArgumentDraw(t, host);

        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/models/Drawer", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor m = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "draw",
                "(L" + LegacyOldModelsBridge.MODEL + ";Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V", null, null);
        m.visitCode();
        m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
        m.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
        m.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
        m.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, LegacyOldModelsBridge.MODEL, "renderToBuffer", LegacyOldModelsBridge.OLD_DRAW_DESC, false);
        m.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        List<String> calls = calls(read(t.transformClass(cw.toByteArray(), "test/models/Drawer")));
        assertTrue(calls.contains(LegacyOldModelsBridge.DRAW_HELPER + ".renderToBuffer(L" + LegacyOldModelsBridge.MODEL + ";"
                + LegacyOldModelsBridge.OLD_DRAW_DESC.substring(1)), calls.toString());
        ClassNode helper = read(t.getSyntheticClasses().get(LegacyOldModelsBridge.DRAW_HELPER));
        for (MethodNode method : helper.methods) {
            new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(helper.name, method);
        }
    }

    private static RetromodTransformer registered() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyOldModelsBridge.register(t, name -> null);
        assertTrue(t.getSyntheticClasses().containsKey(LegacyOldModelsBridge.HIERARCHICAL_BASE), "the rebuilt base is added to the mod");
        return t;
    }

    /** An old model: a constructor calling the base, root() returning a field, a draw override, and a call to root(). */
    private static byte[] oldModel(String base, String superCtor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/models/Bat", null, base, null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "part", "L" + PART + ";", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + PART + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        if (superCtor.equals("(ZFF)V")) {
            init.visitInsn(Opcodes.ICONST_0);
            init.visitInsn(Opcodes.FCONST_0);
            init.visitInsn(Opcodes.FCONST_0);
        }
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", superCtor, false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, "test/models/Bat", "part", "L" + PART + ";");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(4, 2);
        init.visitEnd();
        MethodVisitor root = writer.visitMethod(Opcodes.ACC_PUBLIC, "root", LegacyOldModelsBridge.ROOT_DESC, null, null);
        root.visitCode();
        root.visitVarInsn(Opcodes.ALOAD, 0);
        root.visitFieldInsn(Opcodes.GETFIELD, "test/models/Bat", "part", "L" + PART + ";");
        root.visitInsn(Opcodes.ARETURN);
        root.visitMaxs(1, 1);
        root.visitEnd();
        MethodVisitor draw = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderToBuffer", LegacyOldModelsBridge.DRAW_DESC, null, null);
        draw.visitCode();
        draw.visitInsn(Opcodes.RETURN);
        draw.visitMaxs(0, 6);
        draw.visitEnd();
        MethodVisitor reset = writer.visitMethod(Opcodes.ACC_PUBLIC, "reset", "()V", null, null);
        reset.visitCode();
        reset.visitVarInsn(Opcodes.ALOAD, 0);
        reset.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "test/models/Bat", "root", LegacyOldModelsBridge.ROOT_DESC, false);
        reset.visitInsn(Opcodes.POP);
        reset.visitInsn(Opcodes.RETURN);
        reset.visitMaxs(1, 1);
        reset.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
