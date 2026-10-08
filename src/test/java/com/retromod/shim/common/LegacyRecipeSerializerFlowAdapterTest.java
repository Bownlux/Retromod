/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A 1.21.x mod builds its serializer objects and hands them on as {@code RecipeSerializer}s. Once
 * the interface is dropped on 26.1, every hand-off has to become a real serializer, or the class
 * that registers the mod's recipes fails verification.
 */
class LegacyRecipeSerializerFlowAdapterTest {

    private static final String RS = LegacyRecipeSerializerShim.RECIPE_SERIALIZER;
    private static final String L_RS = "L" + RS + ";";
    private static final String MOD_SERIALIZER = "test/mod/MillingSerializer";

    @Test
    @DisplayName("a supplier lambda that returns a new mod serializer returns an adapted one")
    void returnedSerializerIsAdapted() {
        MethodNode method = transformed("()" + L_RS, mv -> {
            newModSerializer(mv);
            mv.visitInsn(Opcodes.ARETURN);
        });

        assertAdaptedBefore(method, Opcodes.ARETURN);
    }

    @Test
    @DisplayName("a mod serializer passed as the last argument of a helper is adapted")
    void lastArgumentIsAdapted() {
        MethodNode method = transformed("()V", mv -> {
            mv.visitLdcInsn("milling");
            newModSerializer(mv);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "test/mod/Recipes", "registerSerializer",
                    "(Ljava/lang/String;" + L_RS + ")Ljava/util/function/Supplier;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
        });

        assertAdaptedBefore(method, Opcodes.INVOKESTATIC);
    }

    @Test
    @DisplayName("a value the verifier already types as a RecipeSerializer is left alone")
    void castValueIsUntouched() {
        byte[] in = modClass("()" + L_RS, mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, RS);
            mv.visitInsn(Opcodes.ARETURN);
        });

        assertSame(in, LegacyRecipeSerializerFlowAdapter.apply(in));
    }

    @Test
    @DisplayName("a RecipeSerializer parameter handed on unchanged is left alone")
    void serializerParameterIsUntouched() {
        byte[] in = modClass("(" + L_RS + ")" + L_RS, mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.ARETURN);
        });

        assertSame(in, LegacyRecipeSerializerFlowAdapter.apply(in));
    }

    @Test
    @DisplayName("a value that another branch can replace before the hand-off is still adapted")
    void branchJoinIsAdapted() {
        // return flag ? new MillingSerializer() : Recipes.SERIALIZER;
        MethodNode method = transformed("(Z)" + L_RS, mv -> {
            Label field = new Label();
            Label done = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitJumpInsn(Opcodes.IFEQ, field);
            newModSerializer(mv);
            mv.visitJumpInsn(Opcodes.GOTO, done);
            mv.visitLabel(field);
            mv.visitFieldInsn(Opcodes.GETSTATIC, "test/mod/Recipes", "SERIALIZER", L_RS);
            mv.visitLabel(done);
            mv.visitInsn(Opcodes.ARETURN);
        });

        assertAdaptedBefore(method, Opcodes.ARETURN);
    }

    private static void assertAdaptedBefore(MethodNode method, int handOffOpcode) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() != handOffOpcode) continue;
            if (insn instanceof MethodInsnNode call && call.name.equals("adapt")) continue;
            AbstractInsnNode cast = insn.getPrevious();
            assertEquals(Opcodes.CHECKCAST, cast.getOpcode(), "the adapted value must be cast back");
            assertEquals(RS, ((TypeInsnNode) cast).desc);
            MethodInsnNode adapt = (MethodInsnNode) cast.getPrevious();
            assertEquals(LegacyRecipeSerializerShim.BRIDGE, adapt.owner);
            assertEquals("adapt", adapt.name);
            return;
        }
        fail("the hand-off instruction must still be there");
    }

    private static void newModSerializer(MethodVisitor mv) {
        mv.visitTypeInsn(Opcodes.NEW, MOD_SERIALIZER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MOD_SERIALIZER, "<init>", "()V", false);
    }

    private static MethodNode transformed(String desc, Consumer<MethodVisitor> body) {
        byte[] out = LegacyRecipeSerializerFlowAdapter.apply(modClass(desc, body));
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals("make")).findFirst().orElseThrow();
    }

    private static byte[] modClass(String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/mod/Recipes", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", desc,
                null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
