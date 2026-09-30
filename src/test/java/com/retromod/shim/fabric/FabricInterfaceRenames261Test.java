/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Fabric API renamed six single-method interfaces by 26.1. #281 (Hold My Items on Fabric 26.3)
 * registered its HUD as a lambda implementing {@code HudElement.render}; the lambda was built for
 * the old name and threw {@code AbstractMethodError} on the first frame.
 */
class FabricInterfaceRenames261Test {

    private static final String HUD = "net/fabricmc/fabric/api/client/rendering/v1/hud/HudElement";
    private static final String VARIANT = "net/fabricmc/fabric/api/transfer/v1/item/ItemVariant";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a lambda implementing HudElement is built for extractRenderState")
    void hudLambdaUsesTheNewName() {
        transformer.registerInterfaceMethodRename(HUD, "render", "extractRenderState");
        ClassNode out = transform(cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hud", "()L" + HUD + ";",
                    null, null);
            mv.visitCode();
            Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                    "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);
            Type sam = Type.getMethodType("(Ljava/lang/Object;Ljava/lang/Object;)V");
            mv.visitInvokeDynamicInsn("render", "()L" + HUD + ";", metafactory, sam,
                    new Handle(H_INVOKESTATIC, "test/mod/Hud", "lambda$hud$0",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false), sam);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });

        InvokeDynamicInsnNode indy = first(out, InvokeDynamicInsnNode.class);
        assertEquals("extractRenderState", indy.name,
                "LambdaMetafactory binds this name against the 26.x interface");
    }

    @Test
    @DisplayName("a direct call to a renamed method uses the new name")
    void callSiteUsesTheNewName() {
        transformer.registerInterfaceMethodRename(VARIANT, "withComponentChanges", "withComponents");
        String desc = "(Lnet/minecraft/core/component/DataComponentPatch;)L" + VARIANT + ";";
        ClassNode out = transform(cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "patch",
                    "(L" + VARIANT + ";)Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEINTERFACE, VARIANT, "withComponentChanges", desc, true);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });

        assertEquals("withComponents", first(out, MethodInsnNode.class).name);
    }

    @Test
    @DisplayName("a lambda for an unrelated interface keeps its name")
    void otherInterfacesAreUntouched() {
        transformer.registerInterfaceMethodRename(HUD, "render", "extractRenderState");
        ClassNode out = transform(cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "task",
                    "()Ljava/lang/Runnable;", null, null);
            mv.visitCode();
            Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                    "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);
            Type sam = Type.getMethodType("()V");
            mv.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", metafactory, sam,
                    new Handle(H_INVOKESTATIC, "test/mod/Hud", "render", "()V", false), sam);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });

        assertEquals("run", first(out, InvokeDynamicInsnNode.class).name);
    }

    private ClassNode transform(java.util.function.Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Hud", null, "java/lang/Object", null);
        body.accept(cw);
        cw.visitEnd();
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Hud")).accept(out, 0);
        return out;
    }

    private static <T extends AbstractInsnNode> T first(ClassNode cn, Class<T> type) {
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (type.isInstance(insn)) return type.cast(insn);
            }
        }
        return fail("no " + type.getSimpleName() + " in the output");
    }
}
