/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.embedder.ModVersionInfo;
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

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The CLI's transform runs the passes that remember a legacy jar's classes, as the runtime does. */
class CliLegacyJarPassTest {

    private static final String PIECE = "cli/feature/MineshaftPiece";
    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("a 1.21.1 jar's CONFIGURED_FEATURE read becomes 26.3's FEATURE in the CLI output too")
    void configuredFeatureKeyIsRenamed(@TempDir Path dir) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyFeatureBridge.register(transformer);

        Path input = dir.resolve("input.jar");
        Path output = dir.resolve("output.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry(PIECE + ".class"));
            jar.write(readsConfiguredFeature());
            jar.closeEntry();
        }
        ModVersionInfo info = new ModVersionInfo("featurecli", "1.0", "1.21.1", "neoforge",
                null, Set.of("cli/feature/"), Set.of(), false);
        Method transformJar = RetromodCli.class.getDeclaredMethod("transformJar",
                Path.class, Path.class, RetromodTransformer.class, ModVersionInfo.class);
        transformJar.setAccessible(true);
        transformJar.invoke(null, input, output, transformer, info);

        // The CLI names the class by its entry, .class suffix included, and the pass still finds it.
        assertEquals(List.of(REGISTRIES + ".FEATURE"), registryReads(readEntry(output, PIECE + ".class")));
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

    private static byte[] readEntry(Path jarPath, String name) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile());
             InputStream input = jar.getInputStream(jar.getEntry(name))) {
            return input.readAllBytes();
        }
    }
}
