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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Mythic Fantasies, a 1.19.2 Forge mod, on 26.3: its rock feature extended {@code Feature}, which
 * 26.3 made an interface, so the class failed to load and took the mod's registration with it.
 */
class LegacyFeatureBridgeTest {

    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";

    @Test
    @DisplayName("a jar with a class extending the old Feature, in either namespace, is an old-model jar")
    void detectsOldModel() {
        assertTrue(LegacyFeatureBridge.usesOldModel(subclass("mod/RockFeature", LegacyFeatureBridge.FEATURE)));
        assertTrue(LegacyFeatureBridge.usesOldModel(subclass("mod/RockFeature", "net/minecraft/class_3031")));
        assertFalse(LegacyFeatureBridge.usesOldModel(subclass("mod/Plain", "java/lang/Object")));
    }

    @Test
    @DisplayName("in an old jar, FEATURE becomes FEATURE_TYPE and CONFIGURED_FEATURE becomes FEATURE, once each")
    void registryFieldsMoveInOldJars() {
        byte[] registration = readsRegistries("mod/ModFeatures", "FEATURE", "CONFIGURED_FEATURE");
        LegacyFeatureBridge.markOldModelJar(
                List.of(subclass("mod/RockFeature", LegacyFeatureBridge.FEATURE), registration));

        assertEquals(List.of("FEATURE_TYPE", "FEATURE"),
                fieldsRead(LegacyFeatureBridge.apply(registration, "mod/ModFeatures")));
    }

    @Test
    @DisplayName("a 26.3 jar reading FEATURE, with no old feature class, keeps it")
    void newJarsAreUntouched() {
        byte[] registration = readsRegistries("other/NewFeatures", "FEATURE");
        LegacyFeatureBridge.markOldModelJar(List.of(subclass("other/Plain", "java/lang/Object"), registration));

        assertSame(registration, LegacyFeatureBridge.apply(registration, "other/NewFeatures"));
    }

    @Test
    @DisplayName("the generated feature type, configured feature, context, and configurations verify")
    void generatedClassesVerify() throws Exception {
        for (byte[] generated : List.of(LegacyFeatureBridge.generateLegacyFeature(),
                LegacyFeatureBridge.generateConfigured(), LegacyFeatureBridge.generateContext(),
                LegacyFeatureBridge.generateNone(), LegacyFeatureBridge.generateConfigurationInterface())) {
            ClassNode node = new ClassNode();
            new ClassReader(generated).accept(node, 0);
            for (MethodNode method : node.methods) {
                if ((method.access & ACC_ABSTRACT) == 0) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            }
        }
        ClassNode feature = new ClassNode();
        new ClassReader(LegacyFeatureBridge.generateLegacyFeature()).accept(feature, 0);
        assertEquals("com/mojang/serialization/MapCodec", feature.superName,
                "the old feature must be a MapCodec so its registration lands in FEATURE_TYPE");
        assertEquals(List.of(LegacyFeatureBridge.FEATURE), feature.interfaces);
    }

    private static byte[] subclass(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] readsRegistries(String name, String... fields) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "keys", "()V", null, null);
        mv.visitCode();
        for (String field : fields) {
            mv.visitFieldInsn(GETSTATIC, REGISTRIES, field, "Lnet/minecraft/resources/ResourceKey;");
            mv.visitInsn(POP);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> fieldsRead(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<String> names = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) names.add(field.name);
            }
        }
        return names;
    }
}
