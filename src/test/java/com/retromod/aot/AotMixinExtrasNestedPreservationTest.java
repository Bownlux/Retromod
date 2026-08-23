/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.retromod.core.RetromodTransformer;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class AotMixinExtrasNestedPreservationTest {

    @AfterEach
    void clearTransformer() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    @Test
    void aotKeepsMixinExtrasNestedJarByteForByte() throws Exception {
        byte[] original = providerJar();
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2");
        Method transform = AotCompiler.class.getDeclaredMethod(
                "transformNestedJarAot", byte[].class, int.class,
                MixinCompatibilityTransformer.class, boolean.class,
                RetromodTransformer.NestedArchiveBudget.class, String.class);
        transform.setAccessible(true);

        byte[] transformed = (byte[]) transform.invoke(
                compiler, original, 1,
                new MixinCompatibilityTransformer(RetromodTransformer.getInstance()), false,
                new RetromodTransformer.NestedArchiveBudget(16 * 1024 * 1024, 10_000),
                "outer.jar!/META-INF/jars/mixinextras.jar");

        assertSame(original, transformed,
                "AOT must return the exact provider archive before embedding or metadata edits");
    }

    private static byte[] providerJar() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            write(output, "fabric.mod.json",
                    "{\"schemaVersion\":1,\"id\":\"mixinextras\",\"version\":\"0.5.4\","
                            + "\"depends\":{\"minecraft\":\"1.20.1\"},"
                            + "\"mixins\":[\"mixinextras.mixins.json\"]}");
            write(output, "mixinextras.mixins.json",
                    "{\"required\":true,\"package\":\"com.llamalad7.mixinextras.mixin\","
                            + "\"mixins\":[]}");
            write(output, "com/llamalad7/mixinextras/MixinExtrasBootstrap.class",
                    classBytes("com/llamalad7/mixinextras/MixinExtrasBootstrap"));
            write(output, "com/llamalad7/mixinextras/service/MixinExtrasService.class",
                    classBytes("com/llamalad7/mixinextras/service/MixinExtrasService"));
            write(output, "com/llamalad7/mixinextras/service/MixinExtrasServiceImpl.class",
                    classBytes("com/llamalad7/mixinextras/service/MixinExtrasServiceImpl"));
        }
        return bytes.toByteArray();
    }

    private static void write(JarOutputStream output, String name, String value)
            throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static void write(JarOutputStream output, String name, byte[] value)
            throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(value);
        output.closeEntry();
    }

    private static byte[] classBytes(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName,
                null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
