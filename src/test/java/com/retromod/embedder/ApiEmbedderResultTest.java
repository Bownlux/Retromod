/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.embedder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** ApiEmbedder reports what an embed run wrote instead of leaving the caller to guess. */
class ApiEmbedderResultTest {

    private static final ModVersionInfo FORGE_1_18 = new ModVersionInfo("example", "1.0", "1.18.2",
            "forge", "40", Set.of("com/example"), Set.of(), false);

    @TempDir
    Path dir;

    @Test
    void reportsNothingToEmbedAndWritesNoJar() throws Exception {
        Path source = dir.resolve("plain.jar");
        writeJar(source, Map.of("com/example/Plain.class", classCalling(null)));

        ApiEmbedder.EmbedResult result = new ApiEmbedder().embedRemovedApis(source, FORGE_1_18);

        assertNull(result.outputJar(), "a mod that calls no removed API gets no output jar");
        assertEquals(0, result.removedApis());
        assertFalse(Files.exists(dir.resolve("plain-retromod.jar")));
    }

    @Test
    void reportsZeroClassesWhenNoArchiveHoldsThem() throws Exception {
        assumeFalse(Files.exists(Path.of("config/retromod/api-archive/forge-1.20.4-49.0.0.jar")),
                "a local Forge archive would supply the class this test expects to be missing");
        Path source = dir.resolve("legacy.jar");
        writeJar(source, Map.of("com/example/Legacy.class",
                classCalling("net/minecraftforge/event/world/WorldEvent$Load")));

        ApiEmbedder.EmbedResult result = new ApiEmbedder().embedRemovedApis(source, FORGE_1_18);

        assertEquals(1, result.removedApis(), "WorldEvent.Load.getWorld is a listed removed API");
        assertEquals(0, result.embeddedClasses(), "no archive is present to supply its classes");
        assertEquals(dir.resolve("legacy-retromod.jar"), result.outputJar());
        assertTrue(Files.isRegularFile(result.outputJar()));
    }

    @Test
    void throwsInsteadOfSwallowingAnUnreadableJar() throws Exception {
        Path source = dir.resolve("unsafe.jar");
        writeJar(source, Map.of("../outside.txt", new byte[]{1}));

        assertThrows(IOException.class, () -> new ApiEmbedder().embedRemovedApis(source, FORGE_1_18),
                "an entry that escapes the jar must stop the run, not log and report success");
        assertFalse(Files.exists(dir.resolve("unsafe-retromod.jar")));
    }

    /** A class whose one method calls {@code owner.getWorld()}, or does nothing when owner is null. */
    private static byte[] classCalling(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Caller", null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        if (owner != null) {
            MethodVisitor call = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onLoad",
                    "(L" + owner + ";)V", null, null);
            call.visitCode();
            call.visitVarInsn(Opcodes.ALOAD, 0);
            call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "getWorld",
                    "()Lnet/minecraft/world/level/LevelAccessor;", false);
            call.visitInsn(Opcodes.POP);
            call.visitInsn(Opcodes.RETURN);
            call.visitMaxs(0, 0);
            call.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }
}
