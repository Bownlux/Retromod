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

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 26.2 deleted MultiBufferSource/BufferSource/Tesselator and replaced VertexFormat$Mode with
 * PrimitiveTopology (verified against the real 26.1.2 and 26.2 client jars). A 26.1-translated
 * mod referencing any of them dies NoClassDefFoundError at class load on 26.2. These tests lock
 * the stand-in shapes and the call-site rewiring.
 */
class RenderBufferSyntheticsTest {

    private static final String OLD_MBS = "net/minecraft/client/renderer/MultiBufferSource";
    private static final String OLD_BS = OLD_MBS + "$BufferSource";
    private static final String OLD_TES = "com/mojang/blaze3d/vertex/Tesselator";
    private static final String OLD_MODE = "com/mojang/blaze3d/vertex/VertexFormat$Mode";
    private static final String RT = "net/minecraft/client/renderer/rendertype/RenderType";
    private static final String VC = "com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String VF = "com/mojang/blaze3d/vertex/VertexFormat";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("stand-in shapes: 26.1 surface, real 26.2 delegation targets")
    void syntheticShapes() {
        ClassNode itf = new ClassNode();
        new ClassReader(RenderBufferSynthetics.generateInterface()).accept(itf, 0);
        assertTrue((itf.access & ACC_INTERFACE) != 0, "MultiBufferSource stand-in is an interface");
        assertTrue(itf.methods.stream().anyMatch(m -> m.name.equals("getBuffer")
                        && m.desc.equals("(L" + RT + ";)L" + VC + ";")),
                "getBuffer must take the 26.x rendertype/RenderType (the name 26.1 mods link against)");
        assertTrue(itf.methods.stream().anyMatch(m -> m.name.equals("immediate")
                        && (m.access & ACC_STATIC) != 0),
                "the immediate(...) static factory must exist");

        ClassNode bs = new ClassNode();
        new ClassReader(RenderBufferSynthetics.generateBufferSource()).accept(bs, 0);
        assertTrue(bs.interfaces.contains(RenderBufferSynthetics.MBS),
                "BufferSource implements the stand-in interface");
        // getBuffer must construct a REAL 26.2 BufferBuilder from the RenderType's own
        // format() + primitiveTopology() (both verified on the 26.2 jar)
        MethodNode gb = bs.methods.stream().filter(m -> m.name.equals("getBuffer"))
                .findFirst().orElseThrow();
        boolean callsFormat = false, callsTopology = false, buildsBuffer = false;
        for (AbstractInsnNode in : gb.instructions.toArray()) {
            if (in instanceof MethodInsnNode mi) {
                if (mi.owner.equals(RT) && mi.name.equals("format")) callsFormat = true;
                if (mi.owner.equals(RT) && mi.name.equals("primitiveTopology")) callsTopology = true;
                if (mi.owner.equals("com/mojang/blaze3d/vertex/BufferBuilder")
                        && mi.name.equals("<init>")) buildsBuffer = true;
            }
        }
        assertTrue(callsFormat && callsTopology && buildsBuffer,
                "getBuffer must build a real BufferBuilder from the RenderType's format/topology");
        for (String end : new String[]{"endBatch", "endLastBatch"}) {
            assertTrue(bs.methods.stream().anyMatch(m -> m.name.equals(end)),
                    "the staged " + end + " must exist (warn + drop, never crash)");
        }

        ClassNode tes = new ClassNode();
        new ClassReader(RenderBufferSynthetics.generateTesselator()).accept(tes, 0);
        assertTrue(tes.methods.stream().anyMatch(m -> m.name.equals("getInstance")
                        && (m.access & ACC_STATIC) != 0),
                "Tesselator.getInstance() must exist");
        assertTrue(tes.methods.stream().anyMatch(m -> m.name.equals("begin")
                        && m.desc.equals("(Lcom/mojang/blaze3d/PrimitiveTopology;L" + VF
                                + ";)Lcom/mojang/blaze3d/vertex/BufferBuilder;")),
                "begin must take PrimitiveTopology (the Mode redirect's target) and return a real BufferBuilder");
    }

    @Test
    @DisplayName("1.20.x immediate mode links on the stand-in and keeps a valid stack after the Fabric 26.1 shim")
    void preImmediateModeLinksOnTheStandIn() throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new com.retromod.shim.fabric.Fabric_1_21_11_to_26_1().registerRedirects(t);
        RenderBufferSynthetics.register(t);
        String builder = "com/mojang/blaze3d/vertex/BufferBuilder";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "com/example/OldOverlay", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "()V", null, null);
        mv.visitCode();
        // BufferBuilder b = Tesselator.getInstance().getBuilder(); b.begin(Mode.QUADS, fmt); b.end();
        mv.visitMethodInsn(INVOKESTATIC, OLD_TES, "getInstance", "()L" + OLD_TES + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_TES, "getBuilder", "()L" + builder + ";", false);
        mv.visitVarInsn(ASTORE, 0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, OLD_MODE, "QUADS", "L" + OLD_MODE + ";");
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "begin", "(L" + OLD_MODE + ";L" + VF + ";)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "end", "()Lcom/mojang/blaze3d/vertex/MeshData;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(t.transformClass(cw.toByteArray(), "com/example/OldOverlay")).accept(out, 0);
        MethodNode draw = out.methods.stream().filter(m -> m.name.equals("draw")).findFirst().orElseThrow();
        java.util.List<String> calls = new java.util.ArrayList<>();
        for (AbstractInsnNode in : draw.instructions) {
            if (in instanceof MethodInsnNode mi) calls.add(mi.owner + "." + mi.name + mi.desc);
        }
        assertTrue(calls.contains(RenderBufferSynthetics.TES + ".getBuilder()L" + builder + ";"),
                "getBuilder() takes no arguments, so it must stay getBuilder on the stand-in: " + calls);
        assertTrue(calls.contains(RenderBufferSynthetics.TES + ".legacyBegin" + RenderBufferSynthetics.LEGACY_BEGIN), calls.toString());
        assertTrue(calls.contains(builder + ".build()Lcom/mojang/blaze3d/vertex/MeshData;"), calls.toString());
        new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicInterpreter())
                .analyze(out.name, draw);

        ClassNode tes = new ClassNode();
        new ClassReader(RenderBufferSynthetics.generateTesselator()).accept(tes, 0);
        for (String name : new String[]{"getBuilder", "end", "legacyBegin"}) {
            assertTrue(tes.methods.stream().anyMatch(m -> m.name.equals(name)), "the stand-in needs " + name);
        }
    }

    /** A 26.1-translated HUD class using the whole deleted surface, as javac emits it. */
    private static byte[] fixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "com/example/HudRenderer", null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE, "source", "L" + OLD_BS + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw",
                "(L" + OLD_MBS + ";L" + RT + ";)V", null, null);
        mv.visitCode();
        // VertexConsumer vc = source.getBuffer(rt)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_MBS, "getBuffer", "(L" + RT + ";)L" + VC + ";", true);
        mv.visitInsn(POP);
        // Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, fmt)
        mv.visitMethodInsn(INVOKESTATIC, OLD_TES, "getInstance", "()L" + OLD_TES + ";", false);
        mv.visitFieldInsn(GETSTATIC, OLD_MODE, "QUADS", "L" + OLD_MODE + ";");
        mv.visitInsn(ACONST_NULL);
        mv.visitTypeInsn(CHECKCAST, VF);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_TES, "begin",
                "(L" + OLD_MODE + ";L" + VF + ";)Lcom/mojang/blaze3d/vertex/BufferBuilder;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("call sites rewire onto the stand-ins; no deleted-type reference survives")
    void callSitesRewired() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        RenderBufferSynthetics.register(t);

        byte[] out = t.transformClass(fixture(), "com/example/HudRenderer.class");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);

        assertTrue(cn.fields.stream().anyMatch(f ->
                        f.desc.equals("L" + RenderBufferSynthetics.BS + ";")),
                "BufferSource-typed field must point at the stand-in impl");
        boolean sawMbs = false, sawTes = false, sawTopo = false;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in instanceof MethodInsnNode mi) {
                    assertFalse(mi.owner.equals(OLD_MBS) || mi.owner.equals(OLD_TES),
                            "deleted-type call survived: " + mi.owner + "." + mi.name);
                    if (mi.owner.equals(RenderBufferSynthetics.MBS)) sawMbs = true;
                    if (mi.owner.equals(RenderBufferSynthetics.TES)) sawTes = true;
                    if (mi.desc.contains("PrimitiveTopology")) sawTopo = true;
                }
                if (in instanceof org.objectweb.asm.tree.FieldInsnNode fi) {
                    assertFalse(fi.owner.equals(OLD_MODE),
                            "VertexFormat$Mode constant read survived: " + fi.owner);
                }
            }
        }
        assertTrue(sawMbs, "getBuffer must route to the stand-in interface");
        assertTrue(sawTes, "Tesselator calls must route to the stand-in");
        assertTrue(sawTopo, "begin's Mode parameter must arrive as PrimitiveTopology");
        assertTrue(t.getSyntheticClasses().containsKey(RenderBufferSynthetics.MBS)
                        && t.getSyntheticClasses().containsKey(RenderBufferSynthetics.BS)
                        && t.getSyntheticClasses().containsKey(RenderBufferSynthetics.TES),
                "all three stand-ins must be registered for per-mod embedding");
    }
}
