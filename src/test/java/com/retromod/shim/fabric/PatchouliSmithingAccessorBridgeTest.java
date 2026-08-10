/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for Patchouli 1.20.1 smithing pages on Fabric 1.21.11. */
class PatchouliSmithingAccessorBridgeTest {

    private static final String ACCESSOR =
            "vazkii/patchouli/mixin/AccessorSmithingTransformRecipe";
    private static final String INGREDIENT = "net/minecraft/class_1856";

    @AfterEach
    void clear() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void oldIngredientCallUnwrapsTheOptionalAccessor() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);

        ClassNode output = new ClassNode();
        new ClassReader(transformer.transformClass(oldPatchouliCall(), "test/PatchouliPage"))
                .accept(output, 0);
        var instructions = Arrays.asList(output.methods.get(0).instructions.toArray());

        assertTrue(instructions.stream().anyMatch(i -> i instanceof MethodInsnNode call
                && ACCESSOR.equals(call.owner)
                && "retromod$getTemplateOptional".equals(call.name)
                && "()Ljava/util/Optional;".equals(call.desc)));
        assertTrue(instructions.stream().anyMatch(i -> i instanceof MethodInsnNode call
                && "java/util/Optional".equals(call.owner)
                && "orElseThrow".equals(call.name)
                && "()Ljava/lang/Object;".equals(call.desc)));
        assertTrue(instructions.stream().anyMatch(i -> i instanceof TypeInsnNode cast
                && cast.getOpcode() == Opcodes.CHECKCAST && INGREDIENT.equals(cast.desc)),
                "the Object returned by Optional.orElseThrow must be restored to Ingredient");
    }

    @Test
    void oldSoundEventIdCallUsesTheRecordAccessor() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);

        ClassNode output = new ClassNode();
        new ClassReader(transformer.transformClass(oldSoundEventIdCall(), "test/PatchouliSounds"))
                .accept(output, 0);
        var instructions = Arrays.asList(output.methods.get(0).instructions.toArray());

        assertTrue(instructions.stream().anyMatch(i -> i instanceof MethodInsnNode call
                && "net/minecraft/class_3414".equals(call.owner)
                && "comp_3319".equals(call.name)
                && "()Lnet/minecraft/class_2960;".equals(call.desc)));
    }

    private static byte[] oldPatchouliCall() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/PatchouliPage", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "template", "(L" + ACCESSOR + ";)L" + INGREDIENT + ";", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, ACCESSOR, "getTemplate",
                "()L" + INGREDIENT + ";", true);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] oldSoundEventIdCall() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/PatchouliSounds", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "id", "(Lnet/minecraft/class_3414;)Lnet/minecraft/class_2960;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/class_3414", "method_14833",
                "()Lnet/minecraft/class_2960;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
