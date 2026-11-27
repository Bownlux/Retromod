/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.LegacyRandomSourceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The registry fields and randoms of a pre-1.19.3 Fabric mod, by the Mojang names it carries after the 26.x remap. */
class FabricOldEraMovesTest {

    private static final String MOD = "test/registry/Old";
    // The generated class the old math bridge sends calls to.
    private static final String MATH = "com/retromod/generated/LegacyMojangMath";
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("on 26.3 Registry.ITEM reads BuiltInRegistries.ITEM and the dimension key reads Registries.DIMENSION")
    void registryFieldsMove() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new Fabric_1_19_2_to_1_19_3().registerRedirects(transformer);
        List<String> fields = fields(transformer.transformClass(oldReads(), MOD));
        assertTrue(fields.contains("net/minecraft/core/registries/BuiltInRegistries.ITEM"), fields.toString());
        assertTrue(fields.contains("net/minecraft/core/registries/Registries.DIMENSION"), fields.toString());
    }

    @Test
    @DisplayName("before 26.1 the Fabric runtime is intermediary-named, so the Mojang-named moves stay off")
    void intermediaryHostsKeepTheirNames() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        new Fabric_1_19_2_to_1_19_3().registerRedirects(transformer);
        new Fabric_1_18_2_to_1_19().registerRedirects(transformer);
        List<String> fields = fields(transformer.transformClass(oldReads(), MOD));
        assertTrue(fields.contains("net/minecraft/core/Registry.ITEM"), fields.toString());
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyRandomSourceAdapter.VIEW));
    }

    @Test
    @DisplayName("on 26.3 a pre-1.19 Fabric mod's randoms are read through the Random view, as Forge mods' are")
    void randomViewShips() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new Fabric_1_18_2_to_1_19().registerRedirects(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyRandomSourceAdapter.VIEW));
    }

    @Test
    @DisplayName("on 26.3 a pre-1.19.3 Fabric mod's Vector3f, Quaternion and pose rotation reach JOML and the math helpers")
    void oldMathMovesToJoml() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new Fabric_1_19_2_to_1_19_3().registerRedirects(transformer);
        List<String> calls = calls(transformer.transformClass(oldMath(), MOD));
        assertTrue(calls.contains(MATH + ".v3RotationDegrees(Lorg/joml/Vector3f;F)Lorg/joml/Quaternionf;"),
                calls.toString());
        assertTrue(calls.contains("com/mojang/blaze3d/vertex/PoseStack.mulPose(Lorg/joml/Quaternionf;)V"), calls.toString());
        assertTrue(fields(transformer.transformClass(oldMath(), MOD)).contains(MATH + ".YP"));
        assertTrue(transformer.getSyntheticClasses().containsKey(MATH));
    }

    @Test
    @DisplayName("before 26.1 the old math classes keep their names, since the Fabric runtime there is intermediary-named")
    void oldMathStaysOffIntermediaryHosts() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        new Fabric_1_19_2_to_1_19_3().registerRedirects(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(MATH));
        assertTrue(calls(transformer.transformClass(oldMath(), MOD)).contains(
                "com/mojang/math/Vector3f.rotationDegrees(F)Lcom/mojang/math/Quaternion;"));
    }

    // Vector3f.YP.rotationDegrees(90f) handed to PoseStack.mulPose, as every 1.16 to 1.19.2 renderer rotates a model.
    private static byte[] oldMath() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "rotate", "(Lcom/mojang/blaze3d/vertex/PoseStack;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "com/mojang/math/Vector3f", "YP", "Lcom/mojang/math/Vector3f;");
        mv.visitLdcInsn(90.0F);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/mojang/math/Vector3f", "rotationDegrees", "(F)Lcom/mojang/math/Quaternion;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/mojang/blaze3d/vertex/PoseStack", "mulPose", "(Lcom/mojang/math/Quaternion;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        node.methods.forEach(method -> {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        });
        return calls;
    }

    private static byte[] oldReads() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/Registry", "ITEM", "Lnet/minecraft/core/DefaultedRegistry;");
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/Registry", "DIMENSION_REGISTRY", "Lnet/minecraft/resources/ResourceKey;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> fields(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> fields = new ArrayList<>();
        node.methods.forEach(method -> {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name);
            }
        });
        return fields;
    }
}
