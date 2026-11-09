/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyFeatureBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full AOT names classes with dots, and the passes that remember a legacy jar's classes still find them. */
class FullAotLegacyJarPassTest {

    private static final String PIECE = "fullaot/feature/MineshaftPiece";
    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
        resetCompilerSingleton();
    }

    @Test
    @DisplayName("a 1.21.1 jar's CONFIGURED_FEATURE read becomes 26.3's FEATURE in the full AOT cache")
    void configuredFeatureKeyIsRenamed(@TempDir Path dir) throws Exception {
        dir = dir.toRealPath();
        resetCompilerSingleton();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyFeatureBridge.register(transformer);

        Path input = dir.resolve("full-aot-feature.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            writeEntry(jar, "fabric.mod.json",
                    "{\"id\":\"featurefullaot\",\"version\":\"1.0\"}".getBytes(StandardCharsets.UTF_8));
            writeEntry(jar, PIECE + ".class", readsConfiguredFeature());
        }
        FullAotCompiler.getInstance(dir, "26.3").runFullCompilation(List.of(input)).get();

        Path cached = dir.resolve("retromod-cache/full-aot/featurefullaot")
                .resolve(FullAotCompiler.safeClassCacheFileName(PIECE));
        assertTrue(Files.isRegularFile(cached), "the renamed class must be written to the full AOT cache");
        assertEquals(List.of(REGISTRIES + ".FEATURE"), registryReads(Files.readAllBytes(cached)),
                "the dotted name full AOT passes must still reach the legacy feature pass");
    }

    /** {@code Registries.CONFIGURED_FEATURE}, as YUNG's mineshaft pieces read it for their decorations. */
    private static byte[] readsConfiguredFeature() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PIECE, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "key",
                "()Lnet/minecraft/resources/ResourceKey;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, REGISTRIES, "CONFIGURED_FEATURE", "Lnet/minecraft/resources/ResourceKey;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> registryReads(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> reads = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.owner.equals(REGISTRIES)) {
                    reads.add(field.owner + "." + field.name);
                }
            }
        }
        return reads;
    }

    private static void writeEntry(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static void resetCompilerSingleton() throws Exception {
        Field instance = FullAotCompiler.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }
}
