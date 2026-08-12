/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodVersion;
import com.retromod.core.RetromodTransformer;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.testsupport.RefmapRepairFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRefmapRepairIndexTest {

    @TempDir
    Path tempDir;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private Field resolverField;
    private Object savedResolver;

    @BeforeEach
    void installExactTargetIndex() throws Exception {
        resolverField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        resolverField.setAccessible(true);
        savedResolver = resolverField.get(transformer);
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(RefmapRepairFixture.writeTargetJar(tempDir.resolve("target.jar")));
        assertTrue(resolver.isIndexed());
        resolverField.set(transformer, resolver);
    }

    @AfterEach
    void restoreTransformer() throws Exception {
        resolverField.set(transformer, savedResolver);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("CLI pre-scans a nested refmap before repairing an earlier class")
    void nestedRefmapRetypesHandlerBeforeStreamingClasses() throws Exception {
        byte[] transformed = RetromodCli.transformNestedJar(
                RefmapRepairFixture.archive(true), 1);

        assertEquals(RefmapRepairFixture.NEW_HANDLER,
                RefmapRepairFixture.handlerDescriptor(transformed));
        assertTrue(RefmapRepairFixture.refmap(transformed)
                        .contains(RefmapRepairFixture.NEW_TARGET_SELECTOR),
                "the class and emitted refmap must use the same proven target descriptor");
    }

    @Test
    @DisplayName("CLI does not leak one nested archive's refmap plan into the next archive")
    void nestedRefmapPlanIsArchiveScoped() throws Exception {
        byte[] withRefmap = RetromodCli.transformNestedJar(
                RefmapRepairFixture.archive(true), 1);
        byte[] withoutRefmap = RetromodCli.transformNestedJar(
                RefmapRepairFixture.archive(false), 1);

        assertEquals(RefmapRepairFixture.NEW_HANDLER,
                RefmapRepairFixture.handlerDescriptor(withRefmap));
        assertEquals(RefmapRepairFixture.OLD_HANDLER,
                RefmapRepairFixture.handlerDescriptor(withoutRefmap),
                "an archive without the relationship must not inherit another archive's repair");
    }

    @Test
    @DisplayName("CLI routes a mixin-named outer refmap through the refmap rewrite")
    void outerMixinNamedRefmapIsNotTreatedAsAConfig() throws Exception {
        Path input = tempDir.resolve("input.jar");
        Path output = tempDir.resolve("output.jar");
        String refmapName = "mixins.fixture.refmap.json";
        writeFabricMod(input, refmapName);

        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        Field cliTarget = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        cliTarget.setAccessible(true);
        String savedCliTarget = (String) cliTarget.get(null);
        try {
            cliTarget.set(null, "26.1");
            RetromodVersion.TARGET_MC_VERSION = "26.1";
            ModVersionInfo info = new ModVersionInfo(
                    "fixture", "1.0.0", "1.20.1", "fabric",
                    null, Set.of("test/refmap/"), Set.of(), false);
            Method transformJar = RetromodCli.class.getDeclaredMethod(
                    "transformJar", Path.class, Path.class,
                    RetromodTransformer.class, ModVersionInfo.class);
            transformJar.setAccessible(true);
            transformJar.invoke(null, input, output, transformer, info);
        } finally {
            cliTarget.set(null, savedCliTarget);
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
        }

        String transformed = readEntry(output, refmapName);
        assertTrue(transformed.contains(RefmapRepairFixture.NEW_TARGET_SELECTOR),
                "the outer refmap must receive the prepared selector rewrite");
        assertFalse(transformed.contains("\"required\""),
                "a refmap must not receive mixin config fields");
    }

    private static void writeFabricMod(Path output, String refmapName) throws Exception {
        byte[] fixture = RefmapRepairFixture.archive(true);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output));
             ZipInputStream input = new ZipInputStream(
                     new java.io.ByteArrayInputStream(fixture))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write("{\"schemaVersion\":1,\"id\":\"fixture\",\"version\":\"1.0.0\"}"
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();

            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = RefmapRepairFixture.REFMAP.equals(entry.getName())
                        ? refmapName : entry.getName();
                jar.putNextEntry(new JarEntry(name));
                jar.write(input.readAllBytes());
                jar.closeEntry();
            }
        }
    }

    private static String readEntry(Path jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(name);
            if (entry == null) throw new AssertionError("missing " + name);
            try (var input = zip.getInputStream(entry)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
