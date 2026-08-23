/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.mapping.IntermediaryToMojangMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class NestedArchivePolicyTest {

    @Test
    void recognizesOnlyTheExactMixinExtrasFabricId(@TempDir Path dir) throws Exception {
        byte[] mixinExtras = providerJar("mixinextras");
        Path path = dir.resolve("mixinextras.jar");
        Files.write(path, mixinExtras);

        assertTrue(NestedArchivePolicy.shouldPreserve(mixinExtras));
        assertTrue(NestedArchivePolicy.shouldPreserve(path));
        assertFalse(NestedArchivePolicy.shouldPreserve(providerJar("mixinextras-addon")));
        assertFalse(NestedArchivePolicy.shouldPreserve(providerJar("mixin-extras")));
        assertFalse(NestedArchivePolicy.shouldPreserve("not a jar".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fabricIdAndConfigWithoutProviderClassesAreNotPreserved() throws Exception {
        assertFalse(NestedArchivePolicy.shouldPreserve(providerJarWithoutMarkers()));
        assertFalse(NestedArchivePolicy.shouldPreserve(providerJarWithFakeMarkers()));
        assertFalse(NestedArchivePolicy.shouldPreserve(providerJarWithDeepConfig()));
    }

    @Test
    void recognizesTheMetadataFreeNeoForgeProviderShape() throws Exception {
        byte[] provider = providerJarWithNeoForgeLibraryShape();

        assertTrue(NestedArchivePolicy.shouldPreserve(provider));
        assertFalse(NestedArchivePolicy.shouldPreserve(providerJarWithNeoForgeLibraryShape(
                "example.NotMixinExtras")));
        assertFalse(NestedArchivePolicy.shouldPreserve(neoForgeLookalikeWithoutService()));
    }

    @Test
    void fabricNestedPathsKeepProviderBytesUntouched(@TempDir Path dir) throws Exception {
        byte[] original = providerJar("mixinextras");
        FabricModTransformer fabric = new FabricModTransformer("26.2");

        Path officialPath = dir.resolve("official.jar");
        Files.write(officialPath, original);
        Method remap = FabricModTransformer.class.getDeclaredMethod(
                "remapNestedJar", Path.class, IntermediaryToMojangMapper.class,
                int.class, String.class, RetromodTransformer.NestedArchiveBudget.class);
        remap.setAccessible(true);
        boolean officialChanged = (boolean) remap.invoke(
                fabric, officialPath, IntermediaryToMojangMapper.getInstance(), 1,
                "outer.jar!/META-INF/jars/mixinextras.jar",
                RetromodTransformer.NestedArchiveBudget.defaults());

        assertFalse(officialChanged);
        assertArrayEquals(original, Files.readAllBytes(officialPath));

        Path fallbackPath = dir.resolve("fallback.jar");
        Files.write(fallbackPath, original);
        Method fallback = FabricModTransformer.class.getDeclaredMethod(
                "processNestedJiJJar", Path.class, String.class, int.class,
                RetromodTransformer.NestedArchiveBudget.class);
        fallback.setAccessible(true);
        boolean fallbackChanged = (boolean) fallback.invoke(
                fabric, fallbackPath, "outer.jar!/META-INF/jars/mixinextras.jar", 1,
                RetromodTransformer.NestedArchiveBudget.defaults());

        assertFalse(fallbackChanged);
        assertArrayEquals(original, Files.readAllBytes(fallbackPath));
    }

    @Test
    void fabricApiFilenameCleanupKeepsExactProviderId(@TempDir Path dir) throws Exception {
        Path jars = Files.createDirectories(dir.resolve("META-INF/jars"));
        Path provider = jars.resolve("fabric-provider.jar");
        Path ordinaryApi = jars.resolve("fabric-api-old.jar");
        Files.write(provider, providerJar("mixinextras"));
        Files.write(ordinaryApi, providerJar("ordinary-api"));
        FabricModTransformer fabric = new FabricModTransformer("26.2");

        Method stripFiles = FabricModTransformer.class.getDeclaredMethod(
                "stripBundledFabricApiJars", Path.class);
        stripFiles.setAccessible(true);
        stripFiles.invoke(fabric, dir);

        assertTrue(Files.exists(provider));
        assertFalse(Files.exists(ordinaryApi));

        String metadata = "{\"jars\":["
                + "{\"file\":\"META-INF/jars/fabric-provider.jar\"},"
                + "{\"file\":\"META-INF/jars/fabric-api-old.jar\"}]}";
        Method stripReferences = FabricModTransformer.class.getDeclaredMethod(
                "stripFabricApiJarReferences", String.class, Path.class);
        stripReferences.setAccessible(true);
        String rewritten = (String) stripReferences.invoke(fabric, metadata, dir);

        assertTrue(rewritten.contains("fabric-provider.jar"));
        assertFalse(rewritten.contains("fabric-api-old.jar"));
    }

    @Test
    void forgeNestedPathKeepsProviderBytesUntouched(@TempDir Path dir) throws Exception {
        byte[] original = providerJarWithNeoForgeLibraryShape();
        Path provider = dir.resolve("mixinextras.jar");
        Files.write(provider, original);
        ForgeModTransformer forge = new ForgeModTransformer("26.2");
        Method patch = ForgeModTransformer.class.getDeclaredMethod(
                "patchSingleJijJar", Path.class, int.class);
        patch.setAccessible(true);

        boolean changed = (boolean) patch.invoke(forge, provider, 1);

        assertFalse(changed);
        assertArrayEquals(original, Files.readAllBytes(provider));
    }

    @Test
    void forgeNestedSiblingsShareOneExtractionBudget(@TempDir Path dir) throws Exception {
        Path jarjar = Files.createDirectories(dir.resolve("META-INF/jarjar"));
        Files.write(jarjar.resolve("first.jar"), ordinaryJar(6));
        Files.write(jarjar.resolve("second.jar"), ordinaryJar(6));
        ForgeModTransformer forge = new ForgeModTransformer("26.2");
        Method patch = ForgeModTransformer.class.getDeclaredMethod(
                "patchJarInJarMetadata", Path.class, int.class,
                RetromodTransformer.NestedArchiveBudget.class, String.class);
        patch.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> patch.invoke(forge, dir, 1,
                        new RetromodTransformer.NestedArchiveBudget(6, 2), "outer.jar"));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    void forgeNestedDepthKeepsTheParentExtractionBudget(@TempDir Path dir) throws Exception {
        byte[] child = ordinaryJar(4);
        LinkedHashMap<String, byte[]> parentEntries = new LinkedHashMap<>();
        parentEntries.put("META-INF/jarjar/child.jar", child);
        Path parent = dir.resolve("parent.jar");
        Files.write(parent, jar(parentEntries));
        ForgeModTransformer forge = new ForgeModTransformer("26.2");
        Method patch = ForgeModTransformer.class.getDeclaredMethod(
                "patchSingleJijJar", Path.class, int.class,
                RetromodTransformer.NestedArchiveBudget.class, String.class);
        patch.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> patch.invoke(forge, parent, 1,
                        new RetromodTransformer.NestedArchiveBudget(Long.MAX_VALUE, 2),
                        "outer.jar!/META-INF/jarjar/parent.jar"));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("child.jar"),
                failure.getCause().getMessage());
    }

    @Test
    void unsafeForgeChildAbortsTheOuterTransform(@TempDir Path dir) throws Exception {
        byte[] unsafeChild = jar(Map.of("../Bad.class", new byte[] {1}));
        LinkedHashMap<String, byte[]> outerEntries = new LinkedHashMap<>();
        outerEntries.put("META-INF/mods.toml",
                ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"
                        + "[[mods]]\nmodId=\"outer\"\nversion=\"1.0\"\n"
                        + "[[dependencies.outer]]\nmodId=\"minecraft\"\n"
                        + "versionRange=\"[1.20.1,1.20.2)\"\n")
                        .getBytes(StandardCharsets.UTF_8));
        outerEntries.put("META-INF/jarjar/unsafe.jar", unsafeChild);
        Path source = dir.resolve("outer.jar");
        Path output = Files.createDirectories(dir.resolve("output"));
        Files.write(source, jar(outerEntries));

        Path result = new ForgeModTransformer("26.2").transformMod(source, output);

        assertNull(result);
        assertFalse(Files.exists(output.resolve("outer-retromod.jar")));
    }

    @Test
    void unsafeFabricNestedArchiveAbortsBothRuntimePaths(@TempDir Path dir) throws Exception {
        byte[] unsafeChild = jar(Map.of("../Bad.class", new byte[] {1}));
        Path remapChild = dir.resolve("remap.jar");
        Path fallbackChild = dir.resolve("fallback.jar");
        Files.write(remapChild, unsafeChild);
        Files.write(fallbackChild, unsafeChild);
        FabricModTransformer fabric = new FabricModTransformer("26.2");

        Method remap = FabricModTransformer.class.getDeclaredMethod(
                "remapNestedJar", Path.class, IntermediaryToMojangMapper.class,
                int.class, String.class, RetromodTransformer.NestedArchiveBudget.class);
        remap.setAccessible(true);
        InvocationTargetException remapFailure = assertThrows(InvocationTargetException.class,
                () -> remap.invoke(fabric, remapChild,
                        IntermediaryToMojangMapper.getInstance(), 1,
                        "outer.jar!/META-INF/jars/remap.jar",
                        RetromodTransformer.NestedArchiveBudget.defaults()));
        assertInstanceOf(IOException.class, remapFailure.getCause());

        Method fallback = FabricModTransformer.class.getDeclaredMethod(
                "processNestedJiJJar", Path.class, String.class, int.class,
                RetromodTransformer.NestedArchiveBudget.class);
        fallback.setAccessible(true);
        InvocationTargetException fallbackFailure = assertThrows(
                InvocationTargetException.class,
                () -> fallback.invoke(fabric, fallbackChild,
                        "outer.jar!/META-INF/jars/fallback.jar", 1,
                        RetromodTransformer.NestedArchiveBudget.defaults()));
        assertInstanceOf(IOException.class, fallbackFailure.getCause());
    }

    @Test
    void preservedFabricSiblingsShareOneArchiveBudget(@TempDir Path dir) throws Exception {
        Path first = dir.resolve("first.jar");
        Path second = dir.resolve("second.jar");
        Files.write(first, providerJar("mixinextras"));
        Files.write(second, providerJar("mixinextras"));
        FabricModTransformer fabric = new FabricModTransformer("26.2");
        Method process = FabricModTransformer.class.getDeclaredMethod(
                "processNestedJiJJar", Path.class, String.class, int.class,
                RetromodTransformer.NestedArchiveBudget.class);
        process.setAccessible(true);
        RetromodTransformer.NestedArchiveBudget budget =
                new RetromodTransformer.NestedArchiveBudget(Long.MAX_VALUE, 8);

        assertFalse((boolean) process.invoke(fabric, first,
                "outer.jar!/META-INF/jars/first.jar", 1, budget));
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> process.invoke(fabric, second,
                        "outer.jar!/META-INF/jars/second.jar", 1, budget));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    static byte[] providerJar(String id) throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id
                + "\",\"version\":\"0.5.4\",\"depends\":{\"minecraft\":\"1.20.1\"},"
                + "\"mixins\":[\"mixinextras.mixins.json\"]}")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("mixinextras.mixins.json",
                "{\"required\":true,\"package\":\"com.llamalad7.mixinextras.mixin\","
                        .concat("\"mixins\":[]}").getBytes(StandardCharsets.UTF_8));
        addFabricProviderClasses(entries);
        entries.put("provider.marker", "unchanged".getBytes(StandardCharsets.UTF_8));
        return jar(entries);
    }

    private static byte[] providerJarWithoutMarkers() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json",
                ("{\"schemaVersion\":1,\"id\":\"mixinextras\",\"version\":\"0.5.4\","
                        + "\"mixins\":[\"mixinextras.mixins.json\"]}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("mixinextras.mixins.json",
                ("{\"required\":true,\"package\":\"com.llamalad7.mixinextras.mixin\","
                        + "\"mixins\":[]}").getBytes(StandardCharsets.UTF_8));
        return jar(entries);
    }

    private static byte[] providerJarWithFakeMarkers() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json",
                ("{\"schemaVersion\":1,\"id\":\"mixinextras\",\"version\":\"0.5.4\","
                        + "\"mixins\":[\"mixinextras.mixins.json\"]}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("mixinextras.mixins.json",
                ("{\"package\":\"com.llamalad7.mixinextras.mixin\",\"mixins\":[]}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("com/llamalad7/mixinextras/MixinExtrasBootstrap.class", new byte[] {1});
        entries.put("com/llamalad7/mixinextras/service/MixinExtrasService.class", new byte[] {2});
        entries.put("com/llamalad7/mixinextras/service/MixinExtrasServiceImpl.class", new byte[] {3});
        return jar(entries);
    }

    private static byte[] providerJarWithDeepConfig() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json",
                ("{\"schemaVersion\":1,\"id\":\"mixinextras\",\"version\":\"0.5.4\","
                        + "\"mixins\":[\"mixinextras.mixins.json\"]}")
                        .getBytes(StandardCharsets.UTF_8));
        String config = "{\"package\":\"com.llamalad7.mixinextras.mixin\",\"deep\":"
                + "[".repeat(300) + "0" + "]".repeat(300) + "}";
        entries.put("mixinextras.mixins.json", config.getBytes(StandardCharsets.UTF_8));
        addFabricProviderClasses(entries);
        return jar(entries);
    }

    private static byte[] providerJarWithNeoForgeLibraryShape() throws Exception {
        return providerJarWithNeoForgeLibraryShape(
                "com.llamalad7.mixinextras.platform.neoforge.MixinExtrasConfigPlugin");
    }

    private static byte[] providerJarWithNeoForgeLibraryShape(String plugin) throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF",
                ("Manifest-Version: 1.0\r\n"
                        + "MixinConfigs: mixinextras.init.mixins.json\r\n"
                        + "FMLModType: GAMELIBRARY\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("mixinextras.init.mixins.json",
                ("{\"minVersion\":\"0.8\",\"plugin\":\"" + plugin + "\","
                        + "\"package\":\"com.llamalad7.mixinextras.platform.neoforge.mixins\"}")
                        .getBytes(StandardCharsets.UTF_8));
        addFabricProviderClasses(entries);
        addClass(entries,
                "com/llamalad7/mixinextras/platform/neoforge/MixinExtrasConfigPlugin");
        entries.put("provider.marker", "unchanged".getBytes(StandardCharsets.UTF_8));
        return jar(entries);
    }

    private static byte[] neoForgeLookalikeWithoutService() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF",
                ("Manifest-Version: 1.0\r\n"
                        + "MixinConfigs: mixinextras.init.mixins.json\r\n"
                        + "FMLModType: GAMELIBRARY\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("mixinextras.init.mixins.json",
                ("{\"plugin\":\"com.llamalad7.mixinextras.platform.neoforge."
                        + "MixinExtrasConfigPlugin\",\"package\":\"com.llamalad7.mixinextras."
                        + "platform.neoforge.mixins\"}").getBytes(StandardCharsets.UTF_8));
        addClass(entries, "com/llamalad7/mixinextras/MixinExtrasBootstrap");
        addClass(entries,
                "com/llamalad7/mixinextras/platform/neoforge/MixinExtrasConfigPlugin");
        addClass(entries, "com/llamalad7/mixinextras/service/MixinExtrasService");
        return jar(entries);
    }

    private static byte[] ordinaryJar(int bytes) throws Exception {
        return jar(Map.of("assets/test/data.bin", new byte[bytes]));
    }

    private static void addFabricProviderClasses(Map<String, byte[]> entries) {
        addClass(entries, "com/llamalad7/mixinextras/MixinExtrasBootstrap");
        addClass(entries, "com/llamalad7/mixinextras/service/MixinExtrasService");
        addClass(entries, "com/llamalad7/mixinextras/service/MixinExtrasServiceImpl");
    }

    private static void addClass(Map<String, byte[]> entries, String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName,
                null, "java/lang/Object", null);
        writer.visitEnd();
        entries.put(internalName + ".class", writer.toByteArray());
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
