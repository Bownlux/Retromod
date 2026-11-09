/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The fuzzy resolver never picks a member the calling class cannot access, which fails with IllegalAccessError. */
class FuzzyAccessTest {

    private static final String PACK = "net/minecraft/server/packs/metadata/pack/PackMetadataSection";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";
    private static final String HELPER = "net/minecraft/world/level/Helper";
    private static final String MOD = "mod/Reader";
    private static final String BAMBOO = "net/minecraft/world/level/block/BambooSaplingBlock";
    private static final String SHAPE = "Lnet/minecraft/world/phys/shapes/VoxelShape;";

    @TempDir
    Path dir;

    @Test
    @DisplayName("PackMetadataSection.CODEC is not matched to the private FALLBACK_CODEC for a mod's read")
    void privateFieldIsNotGuessed() throws Exception {
        FuzzyMethodResolver resolver = indexed();
        assertEquals("FALLBACK_CODEC", resolver.resolveField(PACK, "CODEC", CODEC, true).name(),
                "without a caller nothing is checked, as before");
        assertNull(resolver.resolveField(PACK, "CODEC", CODEC, true, List.of(MOD), (caller, ancestor) -> false),
                "a mod's class cannot read a private field of PackMetadataSection");
        assertNotNull(resolver.resolveField(PACK, "CODEC", CODEC, true, List.of(PACK), (caller, ancestor) -> false),
                "the class itself can");
    }

    @Test
    @DisplayName("A private, package-private or protected method is only chosen for a caller that can reach it")
    void methodAccessFollowsTheCaller() throws Exception {
        FuzzyMethodResolver resolver = indexed();
        assertNull(resolver.resolveMethod(HELPER, "tick", "()V", List.of(MOD), (caller, ancestor) -> true), "tickPrivately is private");
        assertNull(resolver.resolveMethod(HELPER, "spawn", "()V", List.of(MOD), (caller, ancestor) -> true), "spawnInPackage is package-private");
        assertEquals("spawnInPackage", resolver.resolveMethod(HELPER, "spawn", "()V", List.of("net/minecraft/world/level/Other"),
                (caller, ancestor) -> false).name(), "a class in the same package reaches it");
        assertNull(resolver.resolveMethod(HELPER, "update", "()V", List.of(MOD), (caller, ancestor) -> false),
                "updateProtected from a class proven not to extend Helper");
        // Answers are kept per calling class, so the subclass is a class of its own.
        assertEquals("updateProtected", resolver.resolveMethod(HELPER, "update", "()V", List.of("mod/SubHelper"),
                (caller, ancestor) -> true).name(), "from a subclass, or one whose parents cannot be read");
    }

    @Test
    @DisplayName("A mixin on BambooSaplingBlock alone still reads the removed SAPLING_SHAPE as the private SHAPE; a plain class or a second target does not")
    void mixinsReachTheirTargetsPrivateMembers() throws Exception {
        FuzzyMethodResolver resolver = indexed();
        assertEquals("SHAPE", resolver.resolveField(BAMBOO, "SAPLING_SHAPE", SHAPE, true,
                List.of(BAMBOO), (caller, ancestor) -> false).name(), "the mixin's code runs inside BambooSaplingBlock");
        assertNull(resolver.resolveField(BAMBOO, "SAPLING_SHAPE", SHAPE, true, List.of(MOD), (caller, ancestor) -> false));
        assertNull(resolver.resolveField(BAMBOO, "SAPLING_SHAPE", SHAPE, true,
                List.of(BAMBOO, "net/minecraft/world/level/block/CactusBlock"), (caller, ancestor) -> false),
                "a mixin on two blocks has a copy in CactusBlock too, which cannot read BambooSaplingBlock's private field");
    }

    @Test
    @DisplayName("The transformer maps an intermediary mixin target on 26.x, so the mixin's read is guessed and a plain class's is not")
    void transformerPassesMixinTargets() throws Exception {
        java.lang.reflect.Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.initFuzzyResolver(hostJar());
        // Any field redirect, so the class pass visits method bodies at all.
        transformer.registerFieldRedirect("probe/Old", "value", "probe/Old", "renamed");
        // As a Fabric jar ships it: the target in intermediary, which 26.x maps to BambooSaplingBlock.
        String saved = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        byte[] mixin = shapeReader("mod/BambooMixin", "net/minecraft/class_2202");
        byte[] plain = shapeReader(MOD, null);
        java.util.Map<String, byte[]> jar = java.util.Map.of("mod/BambooMixin", mixin, MOD, plain);
        try (var scope = transformer.pushJarClassBytesProvider(jar::get)) {
            assertEquals("SHAPE", readField(transformer.transformClass(mixin, "mod/BambooMixin")));
            assertEquals("SAPLING_SHAPE", readField(transformer.transformClass(plain, MOD)));
        } finally {
            RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    // A class whose read() returns BambooSaplingBlock.SAPLING_SHAPE, a mixin on target when one is named.
    private static byte[] shapeReader(String name, String target) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        if (target != null) {
            AnnotationVisitor annotation = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
            AnnotationVisitor targets = annotation.visitArray("value");
            targets.visit(null, Type.getObjectType(target));
            targets.visitEnd();
            annotation.visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()" + SHAPE, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, BAMBOO, "SAPLING_SHAPE", SHAPE);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String readField(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) return field.name;
            }
        }
        return null;
    }

    private FuzzyMethodResolver indexed() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        return resolver;
    }

    private Path hostJar() throws Exception {
        Path jar = dir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            ClassWriter pack = new ClassWriter(0);
            pack.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PACK, null, "java/lang/Object", null);
            pack.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "FALLBACK_CODEC", CODEC,
                    "Lcom/mojang/serialization/Codec<L" + PACK + ";>;", null).visitEnd();
            pack.visitEnd();
            write(out, PACK, pack.toByteArray());
            ClassWriter helper = new ClassWriter(0);
            helper.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HELPER, null, "java/lang/Object", null);
            method(helper, Opcodes.ACC_PRIVATE, "tickPrivately");
            method(helper, 0, "spawnInPackage");
            method(helper, Opcodes.ACC_PROTECTED, "updateProtected");
            helper.visitEnd();
            write(out, HELPER, helper.toByteArray());
            ClassWriter bamboo = new ClassWriter(0);
            bamboo.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, BAMBOO, null, "java/lang/Object", null);
            bamboo.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "SHAPE", SHAPE, null, null).visitEnd();
            bamboo.visitEnd();
            write(out, BAMBOO, bamboo.toByteArray());
        }
        return jar;
    }

    private static void method(ClassWriter cw, int access, String name) {
        MethodVisitor mv = cw.visitMethod(access, name, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
    }

    private static void write(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name + ".class"));
        out.write(bytes);
        out.closeEntry();
    }
}
