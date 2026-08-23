/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FullAotJsonParsingBoundsTest {

    @AfterEach
    void resetCompilerSingleton() throws Exception {
        Field instance = FullAotCompiler.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void refmapRequiresExactUtf8(@TempDir Path gameDirectory) throws Exception {
        Path mod = gameDirectory.resolve("fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(mod))) {
            writeEntry(output, "fabric.mod.json",
                    ("{\"id\":\"fixture\",\"version\":\"1.0\","
                            + "\"depends\":{\"minecraft\":\"1.20.1\"}}")
                                    .getBytes(StandardCharsets.UTF_8));
            writeEntry(output, "fixture-refmap.json", invalidUtf8Refmap());
        }

        FullAotCompiler compiler = FullAotCompiler.getInstance(
                gameDirectory.toRealPath(), "26.2");
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> compiler.runFullCompilation(List.of(mod)).get());

        assertInstanceOf(IOException.class, failure.getCause());
    }

    private static void writeEntry(JarOutputStream output, String name, byte[] bytes)
            throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static byte[] invalidUtf8Refmap() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("{\"mappings\":{\"fixture\":{\"key\":\""
                .getBytes(StandardCharsets.UTF_8));
        output.write(0xc3);
        output.write(0x28);
        output.write("\"}}}".getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }
}
