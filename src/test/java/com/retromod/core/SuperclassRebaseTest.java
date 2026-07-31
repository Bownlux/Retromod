/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

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

/**
 * #70 (Arcanus): {@code registerSuperclassRebase} must rewrite only the inheritance
 * edge (the {@code extends} clause and the {@code super(...)} constructor call) and
 * not other references to the base type. The old pre-1.17 model bridge used a plain
 * class redirect ({@code class_583 -> LegacyModelBase_583}), which also rewrote a
 * modern mod's mixin {@code @Inject} handler that captured the base as a parameter,
 * producing {@code InvalidInjectionException: Expected (class_583) but found
 * (LegacyModelBase_583)} and taking the game down.
 */
class SuperclassRebaseTest {

    private static final String OLD = "test/rebase/OldBase";
    private static final String NEW = "test/rebase/NewBase";

    private static ClassNode transformAndRead(byte[] in, String name) {
        byte[] out = RetromodTransformer.getInstance().transformClass(in, name);
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        return cn;
    }

    /** A subclass: extends OLD, ctor calls super(). */
    private static byte[] subclass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/rebase/ModelSub", null, OLD, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OLD, "<init>", "()V", false); // super()
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mixin-handler-like class that merely CAPTURES OLD as a method parameter. */
    private static byte[] paramUser() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/rebase/HandlerMixin", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInit", "(L" + OLD + ";F)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 3);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("rebase rewrites extends + super() to the new base")
    void rebaseRewritesInheritanceEdge() {
        RetromodTransformer.getInstance().registerSuperclassRebase(OLD, NEW);
        ClassNode cn = transformAndRead(subclass(), "test/rebase/ModelSub");

        assertEquals(NEW, cn.superName, "extends must be rebased to the new base");

        MethodNode ctor = cn.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        MethodInsnNode superCall = null;
        for (AbstractInsnNode insn : ctor.instructions) {
            if (insn instanceof MethodInsnNode m && m.name.equals("<init>")) { superCall = m; break; }
        }
        assertNotNull(superCall, "ctor must still have a super() call");
        assertEquals(NEW, superCall.owner, "super() owner must be rebased to the new base");
    }

    @Test
    @DisplayName("a non-<init> super.method() call resolves against the rebased super")
    void nonInitSuperCallTargetsNewBase() {
        // super.render(...)-style calls compile to INVOKESPECIAL OLD.render. After the
        // rebase the direct super is NEW, so the JVM verifier would reject the stale
        // owner. The existing emitMethodInsn fixup must retarget it to the EFFECTIVE
        // (rebased) super. This locks in that currentSuperName carries the new base.
        RetromodTransformer.getInstance().registerSuperclassRebase(OLD, NEW);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/rebase/RenderSub", null, OLD, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OLD, "render", "()V", false); // super.render()
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode cn = transformAndRead(cw.toByteArray(), "test/rebase/RenderSub");
        MethodNode render = cn.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : render.instructions) {
            if (insn instanceof MethodInsnNode m && m.name.equals("render")) { call = m; break; }
        }
        assertNotNull(call);
        assertEquals(NEW, call.owner,
                "super.render() must target the rebased super, or the verifier rejects the class");
    }

    @Test
    @DisplayName("#70: a parameter reference to the base is NOT rewritten (the Arcanus crash)")
    void paramReferenceUntouched() {
        RetromodTransformer.getInstance().registerSuperclassRebase(OLD, NEW);
        ClassNode cn = transformAndRead(paramUser(), "test/rebase/HandlerMixin");

        MethodNode handler = cn.methods.stream().filter(m -> m.name.equals("onInit")).findFirst().orElseThrow();
        assertEquals("(L" + OLD + ";F)V", handler.desc,
                "a method capturing the base as a param must keep the ORIGINAL type - "
                + "rebasing must not leak onto non-inheritance references (#70)");
    }
}
