/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModComplexityAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsEagerLegacyForgeRegistryLifecycle() throws Exception {
        Path jar = tempDir.resolve("legacy-registry.jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output)) {
            jarOutput.putNextEntry(new JarEntry("example/RegistryHolder.class"));
            jarOutput.write(legacyRegistryHolder());
            jarOutput.closeEntry();
        }

        ModComplexityAnalyzer.ComplexityReport report =
                new ModComplexityAnalyzer().analyze(jar);

        assertEquals(25, report.score());
        assertTrue(report.riskFactors().contains(
                "Uses eager legacy Forge registry entries (needs DeferredRegister migration)"));
    }

    private static byte[] legacyRegistryHolder() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/RegistryHolder", null,
                "java/lang/Object", null);
        MethodVisitor register = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "register",
                "(Lnet/minecraftforge/event/RegistryEvent$Register;)V", null, null);
        register.visitCode();
        register.visitInsn(Opcodes.RETURN);
        register.visitMaxs(0, 1);
        register.visitEnd();
        MethodVisitor staticInit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V",
                null, null);
        staticInit.visitCode();
        staticInit.visitTypeInsn(Opcodes.NEW, "example/LegacyBlock");
        staticInit.visitInsn(Opcodes.DUP);
        staticInit.visitMethodInsn(Opcodes.INVOKESPECIAL, "example/LegacyBlock", "<init>",
                "()V", false);
        staticInit.visitInsn(Opcodes.POP);
        staticInit.visitInsn(Opcodes.RETURN);
        staticInit.visitMaxs(2, 0);
        staticInit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
