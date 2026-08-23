/*
 * Retromod test suite. Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class HierarchyClassReadSecurityTest {

    @Test
    void fabricReaderResolvesNormalClassesWithinExtractionRoot(@TempDir Path directory)
            throws Exception {
        Path root = Files.createDirectories(directory.resolve("extracted"));
        byte[] parent = classWithSuper("example/Parent", "java/lang/Object");
        byte[] child = classWithSuper("example/Child", "example/Parent");
        writeClass(root, "example/Parent", parent);
        writeClass(root, "example/Child", child);

        assertArrayEquals(parent,
                FabricModTransformer.readHierarchyClassBytes(root, "example/Parent"));
        assertEquals("example/Parent", RetromodTransformer.commonSuperViaBytes(
                "example/Child", "example/Parent",
                name -> FabricModTransformer.readHierarchyClassBytes(root, name)));
    }

    @Test
    void fabricReaderRejectsTraversalAndSymlinkLeaves(@TempDir Path directory)
            throws Exception {
        Path root = Files.createDirectories(directory.resolve("extracted"));
        Path outside = Files.write(directory.resolve("outside.class"),
                classWithSuper("outside", "java/lang/Object"));

        assertNull(FabricModTransformer.readHierarchyClassBytes(root, "../outside"));

        Path linked = root.resolve("linked.class");
        Files.createSymbolicLink(linked, outside);
        assertNull(FabricModTransformer.readHierarchyClassBytes(root, "linked"));
    }

    @Test
    void fabricReaderRejectsOversizedClassBeforeAllocation(@TempDir Path directory)
            throws Exception {
        Path root = Files.createDirectories(directory.resolve("extracted"));
        Path oversized = root.resolve("oversized.class");
        try (var channel = Files.newByteChannel(oversized,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(RetromodTransformer.MAX_HIERARCHY_CLASS_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }

        assertNull(FabricModTransformer.readHierarchyClassBytes(root, "oversized"));
    }

    @Test
    void classpathFallbackResolvesNormalClassResource() {
        byte[] bytes = RetromodTransformer.readClassBytes(
                "com/retromod/core/RetromodTransformer", null);

        assertNotNull(bytes, "the normal classpath resource must remain readable");
        assertEquals("com/retromod/core/RetromodTransformer",
                new ClassReader(bytes).getClassName());
    }

    @Test
    void classpathFallbackRejectsResourcePastConfiguredLimit() {
        byte[] result = RetromodTransformer.readClassBytes(
                "example/Oversized", null,
                ignored -> new ByteArrayInputStream(new byte[65]), 64);

        assertNull(result);
    }

    private static void writeClass(Path root, String internalName, byte[] bytes)
            throws Exception {
        Path target = root.resolve(internalName + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] classWithSuper(String name, String superName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
