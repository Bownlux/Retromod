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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 0 of the GUI 2D-transform migration: the immediate {@code guiGraphics.pose().pushPose()} /
 * {@code .popPose()} peephole becomes {@code pose():Matrix3x2fStack} + {@code pushMatrix()/popMatrix()}
 * + {@code POP}; anything non-adjacent (a stored stack) is left exactly as-is.
 */
class Gui2DTransformMigrationTest {

    private static final String GUI = "net/minecraft/client/gui/GuiGraphics";
    private static final String POSE = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String M3 = "org/joml/Matrix3x2fStack";
    private static final String POSE_OLD = "()Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String M3_DESC = "()Lorg/joml/Matrix3x2fStack;";

    private static byte[] drawClass(Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/G", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "(L" + GUI + ";)V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void poseCall(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKEVIRTUAL, GUI, "pose", POSE_OLD, false);
    }

    private static List<MethodInsnNode> calls(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        List<MethodInsnNode> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (var i : m.instructions.toArray()) if (i instanceof MethodInsnNode mi) out.add(mi);
        }
        return out;
    }

    private static int pops(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        int p = 0;
        for (MethodNode m : cn.methods) for (var i : m.instructions.toArray()) if (i.getOpcode() == POP) p++;
        return p;
    }

    @Test
    @DisplayName("immediate pose().pushPose()/popPose() -> pose():Matrix3x2fStack + pushMatrix()/popMatrix() + POP")
    void immediateChainMigrated() {
        byte[] in = drawClass(mv -> {
            mv.visitVarInsn(ALOAD, 0); poseCall(mv);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "pushPose", "()V", false);
            mv.visitVarInsn(ALOAD, 0); poseCall(mv);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "popPose", "()V", false);
        });
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertNotEquals(in.length == out.length && java.util.Arrays.equals(in, out), true,
                "the immediate chain must be rewritten");

        List<MethodInsnNode> c = calls(out);
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("pushMatrix") && mi.desc.equals(M3_DESC)),
                "pushPose -> Matrix3x2fStack.pushMatrix()");
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("popMatrix")),
                "popPose -> Matrix3x2fStack.popMatrix()");
        assertFalse(c.stream().anyMatch(mi -> mi.name.equals("pushPose") || mi.name.equals("popPose")),
                "the old void ops must be gone");
        assertTrue(c.stream().filter(mi -> mi.name.equals("pose")).allMatch(mi -> mi.desc.equals(M3_DESC)),
                "each consumed pose() is retyped to return the 2D stack");
        assertEquals(2, pops(out), "each fluent op's return is popped (the old call was void)");

        // structurally re-readable (COMPUTE_FRAMES succeeded, else migrate would have returned the input)
        assertDoesNotThrow(() -> new ClassReader(out).accept(new ClassNode(), 0));
    }

    @Test
    @DisplayName("Phase 2: a stored pose stack (var p = g.pose(); p.pushPose(); p.translate(..); p.popPose()) is migrated")
    void storedStackMigrated() {
        byte[] in = drawClass(mv -> {
            mv.visitVarInsn(ALOAD, 0); poseCall(mv); mv.visitVarInsn(ASTORE, 1); // var p = g.pose();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "pushPose", "()V", false);    // p.pushPose();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(FCONST_1); mv.visitInsn(FCONST_2); mv.visitLdcInsn(3.0f);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "translate", "(FFF)V", false); // p.translate(1,2,3);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "popPose", "()V", false);     // p.popPose();
        });
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertFalse(java.util.Arrays.equals(in, out), "the stored-stack chain must be migrated");

        List<MethodInsnNode> c = calls(out);
        assertTrue(c.stream().filter(mi -> mi.name.equals("pose")).allMatch(mi -> mi.desc.equals(M3_DESC)),
                "the stored pose() is retyped to the 2D stack");
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("pushMatrix")));
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("popMatrix")));
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("translate")
                && mi.desc.equals("(FF)Lorg/joml/Matrix3x2f;")), "translate(FFF) -> 2D translate(FF)");
        assertFalse(c.stream().anyMatch(mi -> mi.owner.equals(POSE)), "no 3D PoseStack op may remain");
        assertEquals(4, pops(out), "pushMatrix + popMatrix pop their result (2), translate drops z + pops its result (2)");
        assertDoesNotThrow(() -> new ClassReader(out).accept(new ClassNode(), 0),
                "COMPUTE_FRAMES succeeded, so the retyped local + rewritten ops balance");
    }

    @Test
    @DisplayName("Phase 2 SAFETY: a stored stack passed to a method (a foreign use) is NOT migrated")
    void storedStackForeignUseNotMigrated() {
        byte[] in = drawClass(mv -> {
            mv.visitVarInsn(ALOAD, 0); poseCall(mv); mv.visitVarInsn(ASTORE, 1); // var p = g.pose();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "pushPose", "()V", false);    // p.pushPose();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "test/Sink", "use", "(L" + POSE + ";)V", false); // use(p);
        });
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertArrayEquals(in, out, "a load used as a real 3D PoseStack (method arg) must bail the whole slot");
    }

    @Test
    @DisplayName("Phase 2 SAFETY: a slot reassigned (>1 store) is NOT migrated")
    void storedStackReassignedNotMigrated() {
        byte[] in = drawClass(mv -> {
            mv.visitVarInsn(ALOAD, 0); poseCall(mv); mv.visitVarInsn(ASTORE, 1); // p = g.pose();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "pushPose", "()V", false);
            mv.visitVarInsn(ALOAD, 0); poseCall(mv); mv.visitVarInsn(ASTORE, 1); // p = g.pose(); (2nd store)
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "popPose", "()V", false);
        });
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertArrayEquals(in, out, "a reused/reassigned slot (two stores) must not be retyped");
    }

    @Test
    @DisplayName("Phase 2 SAFETY: a 3D PoseStack from another source stored to a local is NOT touched")
    void foreign3DStackStoredNotTouched() {
        // render(PoseStack ps) { var p = ps; p.pushPose(); p.translate(1,2,3); }  -- p holds a 3D stack.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/World2", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "render", "(L" + POSE + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ASTORE, 1);   // var p = ps; (store is NOT a pose())
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "pushPose", "()V", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(FCONST_1); mv.visitInsn(FCONST_2); mv.visitLdcInsn(3.0f);
        mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "translate", "(FFF)V", false);
        mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        cw.visitEnd();
        byte[] in = cw.toByteArray();
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertArrayEquals(in, out, "the local's store isn't a pose() -> it's a 3D stack, leave it alone");
    }

    @Test
    @DisplayName("a class that never mentions PoseStack is returned as-is (cheap pre-filter)")
    void unrelatedClassUntouched() {
        // no pose()/PoseStack anywhere -> the byte pre-filter skips the parse entirely.
        byte[] in = drawClass(mv -> { mv.visitVarInsn(ALOAD, 0); mv.visitInsn(POP); });
        assertSame(in, Gui2DTransformMigration.migrate(in), "no PoseStack -> no parse, same array back");
    }

    @Test
    @DisplayName("Phase 1: pose().translate(x,y,z)/scale(x,y,z) -> 2D translate(x,y)/scale(x,y) + drop z + pop")
    void argOpsMigrated() {
        byte[] in = drawClass(mv -> {
            // guiGraphics.pose().translate(1f, 2f, 3f);
            mv.visitVarInsn(ALOAD, 0); poseCall(mv);
            mv.visitInsn(FCONST_1); mv.visitInsn(FCONST_2); mv.visitLdcInsn(3.0f);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "translate", "(FFF)V", false);
            // guiGraphics.pose().scale(2f, 2f, 1f);
            mv.visitVarInsn(ALOAD, 0); poseCall(mv);
            mv.visitInsn(FCONST_2); mv.visitInsn(FCONST_2); mv.visitInsn(FCONST_1);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "scale", "(FFF)V", false);
        });
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertFalse(java.util.Arrays.equals(in, out), "the pose().translate/scale chains must be rewritten");

        List<MethodInsnNode> c = calls(out);
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("translate")
                && mi.desc.equals("(FF)Lorg/joml/Matrix3x2f;")), "translate(FFF) -> 2D translate(FF)");
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("scale")
                && mi.desc.equals("(FF)Lorg/joml/Matrix3x2f;")), "scale(FFF) -> 2D scale(FF)");
        assertFalse(c.stream().anyMatch(mi -> mi.owner.equals(POSE)
                && (mi.name.equals("translate") || mi.name.equals("scale"))),
                "no 3D PoseStack translate/scale may remain");
        assertTrue(c.stream().filter(mi -> mi.name.equals("pose")).allMatch(mi -> mi.desc.equals(M3_DESC)),
                "each consumed pose() is retyped to the 2D stack");
        assertEquals(4, pops(out), "each op drops its z arg and pops its fluent result (2 ops x 2 pops)");
        assertDoesNotThrow(() -> new ClassReader(out).accept(new ClassNode(), 0),
                "COMPUTE_FRAMES succeeded, so the rewritten stack balances");
    }

    @Test
    @DisplayName("SAFETY: a genuine 3D PoseStack (not from GuiGraphics.pose()) is left untouched")
    void threeDPoseStackNotTouched() {
        // render(PoseStack ps) { ps.translate(1f, 2f, 3f); }  -- the receiver is a param, NOT pose().
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/World", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "render", "(L" + POSE + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0); // the PoseStack param (a real 3D stack)
        mv.visitInsn(FCONST_1); mv.visitInsn(FCONST_2); mv.visitLdcInsn(3.0f);
        mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "translate", "(FFF)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
        cw.visitEnd();
        byte[] in = cw.toByteArray();

        byte[] out = Gui2DTransformMigration.migrate(in);
        // references PoseStack (so it parses), but the receiver isn't pose() -> nothing rewritten.
        assertArrayEquals(in, out, "3D world-render PoseStack.translate must NOT be migrated (would corrupt 3D)");
    }

    /** Run ASM's BasicVerifier over every method: catches type/stack errors COMPUTE_FRAMES can hide. */
    private static void basicVerify(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            assertDoesNotThrow(() -> new org.objectweb.asm.tree.analysis.Analyzer<>(
                    new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(cn.name, m),
                    "BasicVerifier must pass for " + m.name);
        }
    }

    @Test
    @DisplayName("Phase 3: pose().translate(double,double,double) migrates with the D2F juggle")
    void doubleTranslateMigrated() {
        byte[] in = drawClass(mv -> {
            // guiGraphics.pose().translate(1.0, 2.0, 3.0);
            mv.visitVarInsn(ALOAD, 0); poseCall(mv);
            mv.visitLdcInsn(1.0d); mv.visitLdcInsn(2.0d); mv.visitLdcInsn(3.0d);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "translate", "(DDD)V", false);
        });
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertFalse(java.util.Arrays.equals(in, out), "the double translate must be rewritten");

        List<MethodInsnNode> c = calls(out);
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("translate")
                && mi.desc.equals("(FF)Lorg/joml/Matrix3x2f;")), "translate(DDD) -> 2D translate(FF)");
        assertTrue(c.stream().filter(mi -> mi.name.equals("pose")).allMatch(mi -> mi.desc.equals(M3_DESC)));
        // The juggle: POP2 (drop dz), D2F, DUP_X2, POP, D2F, SWAP -- provably balanced:
        basicVerify(out);
    }

    @Test
    @DisplayName("Phase 3: pose().mulPose(Quaternionf[c]) becomes 2D rotate(RetroQuat2D.zAngle)")
    void mulPoseMigrated() {
        byte[] in = drawClass(mv -> {
            // guiGraphics.pose().mulPose(quat);  (quat from a static helper so the stack is real)
            mv.visitVarInsn(ALOAD, 0); poseCall(mv);
            mv.visitMethodInsn(INVOKESTATIC, "test/Q", "quat", "()Lorg/joml/Quaternionfc;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "mulPose", "(Lorg/joml/Quaternionfc;)V", false);
        });
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertFalse(java.util.Arrays.equals(in, out), "the mulPose must be rewritten");

        List<MethodInsnNode> c = calls(out);
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals("com/retromod/generated/RetroQuat2D")
                && mi.name.equals("zAngle")), "the quat must be converted via RetroQuat2D.zAngle");
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("rotate")
                && mi.desc.equals("(F)Lorg/joml/Matrix3x2f;")), "mulPose -> 2D rotate(F)");
        assertFalse(c.stream().anyMatch(mi -> mi.name.equals("mulPose")), "no mulPose may remain");
        basicVerify(out);
    }

    @Test
    @DisplayName("Phase 3 SAFETY: mulPose on a 3D PoseStack param is left untouched")
    void mulPoseOn3DStackNotTouched() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/W", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "render",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Quaternionfc;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "mulPose", "(Lorg/joml/Quaternionfc;)V", false);
        mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd(); cw.visitEnd();
        byte[] in = cw.toByteArray();
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertArrayEquals(in, out, "a 3D world-render mulPose must NOT be migrated");
    }

    @Test
    @DisplayName("Phase 3: the stored-stack idiom migrates double translate + mulPose too")
    void storedStackPhase3Migrated() {
        byte[] in = drawClass(mv -> {
            // var p = gg.pose(); p.pushPose(); p.translate(1.0,2.0,3.0); p.mulPose(q); p.popPose();
            mv.visitVarInsn(ALOAD, 0); poseCall(mv);
            mv.visitVarInsn(ASTORE, 1);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "pushPose", "()V", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitLdcInsn(1.0d); mv.visitLdcInsn(2.0d); mv.visitLdcInsn(3.0d);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "translate", "(DDD)V", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "test/Q", "quat", "()Lorg/joml/Quaternionfc;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "mulPose", "(Lorg/joml/Quaternionfc;)V", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "popPose", "()V", false);
        });
        byte[] out = Gui2DTransformMigration.migrate(in);
        assertFalse(java.util.Arrays.equals(in, out), "the stored-stack Phase 3 ops must be rewritten");

        List<MethodInsnNode> c = calls(out);
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("translate")));
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("rotate")));
        assertTrue(c.stream().anyMatch(mi -> mi.owner.equals(M3) && mi.name.equals("pushMatrix")));
        assertFalse(c.stream().anyMatch(mi -> mi.owner.equals(POSE)), "no 3D op may remain");
        basicVerify(out);
    }
}
