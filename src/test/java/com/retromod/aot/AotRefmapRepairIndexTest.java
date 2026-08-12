/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
import com.retromod.testsupport.RefmapRepairFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AotRefmapRepairIndexTest {

    @TempDir
    Path tempDir;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private Field resolverField;
    private Object savedResolver;
    private String savedTarget;

    @BeforeEach
    void installExactTargetIndex() throws Exception {
        resolverField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        resolverField.setAccessible(true);
        savedResolver = resolverField.get(transformer);
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(RefmapRepairFixture.writeTargetJar(tempDir.resolve("target.jar")));
        assertTrue(resolver.isIndexed());
        resolverField.set(transformer, resolver);
    }

    @AfterEach
    void restoreTransformer() throws Exception {
        resolverField.set(transformer, savedResolver);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
        resetFullAotCompiler();
    }

    @Test
    @DisplayName("AOT pre-scans each nested archive and keeps its refmap plan isolated")
    void nestedAotUsesOnlyTheOwningArchiveRefmap() throws Exception {
        byte[] withRefmap = transformNested(RefmapRepairFixture.archive(true), "with-refmap.jar");
        byte[] withoutRefmap = transformNested(
                RefmapRepairFixture.archive(false), "without-refmap.jar");

        assertEquals(RefmapRepairFixture.NEW_HANDLER,
                RefmapRepairFixture.handlerDescriptor(withRefmap));
        assertTrue(RefmapRepairFixture.refmap(withRefmap)
                        .contains(RefmapRepairFixture.NEW_TARGET_SELECTOR),
                "the AOT class and emitted refmap must agree on the new descriptor");
        assertEquals(RefmapRepairFixture.OLD_HANDLER,
                RefmapRepairFixture.handlerDescriptor(withoutRefmap),
                "the second archive must not inherit the first archive's repair facts");
    }

    @Test
    @DisplayName("Full AOT collects the outer archive's refmap before starting worker threads")
    void fullAotWorkersReceiveTheOuterRefmapPlan() throws Exception {
        resetFullAotCompiler();
        Path mod = RefmapRepairFixture.writeFabricMod(
                tempDir.resolve("full-aot-refmap.jar"), "fullaotrefmap", true);

        FullAotCompiler compiler = FullAotCompiler.getInstance(tempDir, "26.1");
        compiler.runFullCompilation(List.of(mod)).get();

        Path cached = tempDir.resolve("retromod-cache/full-aot/fullaotrefmap")
                .resolve(FullAotCompiler.safeClassCacheFileName(RefmapRepairFixture.MIXIN));
        assertTrue(Files.isRegularFile(cached),
                "the refmap-linked class repair must be written to the completed cache");
        assertEquals(RefmapRepairFixture.NEW_HANDLER,
                handlerDescriptor(Files.readAllBytes(cached)));
    }

    @Test
    @DisplayName("Full AOT does not scan a refmap-named nested jar as JSON")
    void fullAotSkipsRefmapNamedNestedJarDuringRefmapPlanning() throws Exception {
        byte[] nested = jarOf(Map.of("assets/fixture/data.bin", new byte[] {1, 2, 3}));
        Path outer = tempDir.resolve("full-aot-nested.jar");
        Files.write(outer, jarOf(Map.of("META-INF/jars/refmap-library.jar", nested)));
        FullAotCompiler compiler = FullAotCompiler.getInstance(tempDir, "26.1");
        FullAotCompiler.ExpandedByteBudget budget =
                new FullAotCompiler.ExpandedByteBudget(1);
        Method collect = FullAotCompiler.class.getDeclaredMethod(
                "collectRefmapRepairs", JarFile.class, MixinCompatibilityTransformer.class,
                boolean.class, boolean.class, FullAotCompiler.ExpandedByteBudget.class);
        collect.setAccessible(true);

        try (JarFile jar = new JarFile(outer.toFile())) {
            collect.invoke(compiler, jar, new MixinCompatibilityTransformer(transformer),
                    false, true, budget);
        }

        assertEquals(0, budget.usedBytes(),
                "a nested archive must not consume the JSON refmap scan budget");
    }

    private byte[] transformNested(byte[] archive, String key) throws Exception {
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.1");
        Method method = AotCompiler.class.getDeclaredMethod(
                "transformNestedJarAot", byte[].class, int.class,
                MixinCompatibilityTransformer.class, boolean.class,
                AotCompiler.ArchiveBudget.class, String.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(compiler, archive, 1,
                new MixinCompatibilityTransformer(transformer), false,
                new AotCompiler.ArchiveBudget(16 * 1024 * 1024, 10_000), key);
    }

    private static String handlerDescriptor(byte[] classBytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);
        return node.methods.stream()
                .filter(method -> "handler".equals(method.name))
                .findFirst().orElseThrow().desc;
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

    private static void resetFullAotCompiler() throws Exception {
        Field instance = FullAotCompiler.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }
}
