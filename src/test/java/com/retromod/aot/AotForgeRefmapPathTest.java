/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AotForgeRefmapPathTest {

    private static final String OLD_OWNER = "net/minecraft/block/PortalSize";
    private static final String NEW_OWNER = "net/minecraft/world/level/portal/PortalShape";
    private static final String REFMAP = "{\"mappings\":{\"MixinPortal\":{\"func_242974_d()I\":"
            + "\"L" + OLD_OWNER + ";func_242974_d()I\"}}}";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void registerRedirects() {
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_OWNER, NEW_OWNER);
        transformer.registerMethodRedirect(
                OLD_OWNER, "func_242974_d", "()I",
                NEW_OWNER, "m_77745_", "()I");
    }

    @AfterEach
    void resetTransformer() {
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("AOT rewrites Forge refmaps for a pre-26 target")
    void outerForgeRefmapIsTransformed(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("input.jar");
        Path output = directory.resolve("output.jar");
        Files.write(input, jarOf(Map.of(
                "portal-refmap.json", REFMAP.getBytes(StandardCharsets.UTF_8))));

        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "1.20.1");
        ModVersionInfo mod = new ModVersionInfo(
                "portal", "1.0", "1.20.1", "forge", null,
                Set.of("test/"), Set.of(), false);
        Method compileJar = AotCompiler.class.getDeclaredMethod(
                "compileJar", Path.class, Path.class, ModVersionInfo.class);
        compileJar.setAccessible(true);
        compileJar.invoke(compiler, input, output, mod);

        assertTargetSelector(readEntry(output, "portal-refmap.json"));
    }

    @Test
    @DisplayName("AOT rewrites Forge refmaps inside bundled jars for a pre-26 target")
    void nestedForgeRefmapIsTransformed() throws Exception {
        byte[] nested = jarOf(Map.of(
                "portal-refmap.json", REFMAP.getBytes(StandardCharsets.UTF_8)));
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "1.20.1");
        Method transformNested = AotCompiler.class.getDeclaredMethod(
                "transformNestedJarAot", byte[].class, int.class,
                MixinCompatibilityTransformer.class, boolean.class);
        transformNested.setAccessible(true);

        byte[] output = (byte[]) transformNested.invoke(
                compiler, nested, 1, new MixinCompatibilityTransformer(transformer), true);
        assertTargetSelector(new String(
                readEntry(output, "portal-refmap.json"), StandardCharsets.UTF_8));
    }

    private static void assertTargetSelector(String refmap) {
        assertTrue(refmap.contains("L" + NEW_OWNER + ";m_77745_()I"),
                "the refmap value must use the target owner and member");
        assertTrue(refmap.contains("\"m_77745_()I\""),
                "the descriptor-shaped refmap key must follow the member redirect");
        assertFalse(refmap.contains("func_242974_d"),
                "the source SRG selector must not survive the AOT resource pass");
    }

    private static byte[] jarOf(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String readEntry(Path jarPath, String name) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(name);
            if (entry == null) throw new AssertionError("missing " + name);
            try (InputStream input = jar.getInputStream(entry)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static byte[] readEntry(byte[] jarBytes, String name) throws Exception {
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (name.equals(entry.getName())) return jar.readAllBytes();
            }
        }
        throw new AssertionError("missing " + name);
    }
}
