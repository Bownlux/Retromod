/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The blaze3d refactor (26.x) deleted the imperative {@code RenderSystem} state setters,
 * leaving nothing to redirect old {@code RenderSystem.enableBlend()} calls to.
 * {@link RemovedRenderStateNeutralize} drops the call (pops args + receiver, pushes a
 * default return) so the mod still loads.
 */
class RemovedRenderStateNeutralizeTest {

    private static final String RS = "com/mojang/blaze3d/systems/RenderSystem";

    /** render() calls only removed state setters (void, primitive args). */
    private static byte[] classOnlyNeutralizedCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC, "test/render/OnlyDead", null, "java/lang/Object", null);
        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN);
        c.visitMaxs(1, 1);
        c.visitEnd();

        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "render", "()V", null, null);
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, RS, "enableBlend", "()V", false);
        m.visitInsn(ICONST_1); m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKESTATIC, RS, "blendFunc", "(II)V", false);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKESTATIC, RS, "depthMask", "(Z)V", false);
        m.visitInsn(ICONST_1); m.visitInsn(ICONST_1); m.visitInsn(ICONST_1); m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKESTATIC, RS, "colorMask", "(ZZZZ)V", false);
        // Removed shader-binding setters (reference + primitive args must all pop cleanly).
        m.visitInsn(ACONST_NULL); // a Supplier<ShaderInstance>
        m.visitMethodInsn(INVOKESTATIC, RS, "setShader", "(Ljava/util/function/Supplier;)V", false);
        m.visitInsn(FCONST_1); m.visitInsn(FCONST_1); m.visitInsn(FCONST_1); m.visitInsn(FCONST_0);
        m.visitMethodInsn(INVOKESTATIC, RS, "setShaderColor", "(FFFF)V", false);
        m.visitInsn(ICONST_0);
        m.visitInsn(ACONST_NULL); // an Identifier
        m.visitMethodInsn(INVOKESTATIC, RS, "setShaderTexture",
                "(ILnet/minecraft/resources/Identifier;)V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(4, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode parse(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        return cn;
    }

    private static long renderSystemCalls(ClassNode cn) {
        return cn.methods.stream()
                .filter(mn -> mn.instructions != null)
                .flatMap(mn -> java.util.Arrays.stream(mn.instructions.toArray()))
                .filter(in -> in instanceof MethodInsnNode min && RS.equals(min.owner))
                .count();
    }

    @Test
    @DisplayName("removed RenderSystem state calls are neutralized; class loads + runs without RenderSystem present")
    void neutralizedClassLoadsAndRuns() throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            RemovedRenderStateNeutralize.register(t);
            byte[] out = t.transformClass(classOnlyNeutralizedCalls(), "test/render/OnlyDead");

            assertEquals(0, renderSystemCalls(parse(out)),
                    "all removed RenderSystem state calls must be neutralized away");

            // run render() with no RenderSystem on the classpath; a NoSuchMethodError
            // or VerifyError means a call survived or the pop/push unbalanced the stack.
            DefiningLoader dl = new DefiningLoader(getClass().getClassLoader());
            Class<?> defined = dl.define(out);
            Object inst = defined.getDeclaredConstructor().newInstance();
            defined.getMethod("render").invoke(inst);
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("non-registered RenderSystem methods are not neutralized")
    void liveMethodsArePreserved() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            RemovedRenderStateNeutralize.register(t);
            // getBackendDescription survives the refactor, so it's not in the neutralize set.
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            cw.visit(V17, ACC_PUBLIC, "test/render/Live", null, "java/lang/Object", null);
            MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "f", "()V", null, null);
            m.visitCode();
            m.visitMethodInsn(INVOKESTATIC, RS, "getBackendDescription", "()Ljava/lang/String;", false);
            m.visitInsn(POP);
            m.visitInsn(RETURN);
            m.visitMaxs(1, 0);
            m.visitEnd();
            cw.visitEnd();

            byte[] out = t.transformClass(cw.toByteArray(), "test/render/Live");
            boolean kept = java.util.Arrays.stream(parse(out).methods.get(0).instructions.toArray())
                    .anyMatch(in -> in instanceof MethodInsnNode min
                            && RS.equals(min.owner) && "getBackendDescription".equals(min.name));
            assertTrue(kept, "a surviving RenderSystem method must not be neutralized");
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    /** Loader with no MC on its classpath, so the transformed class verifies standalone. */
    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) { super(parent); }
        Class<?> define(byte[] b) { return defineClass("test.render.OnlyDead", b, 0, b.length); }
    }
}
