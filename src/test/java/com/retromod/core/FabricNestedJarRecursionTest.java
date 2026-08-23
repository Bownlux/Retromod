/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mapping.IntermediaryToMojangMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that Fabric's extracted-tree runtime path reaches archives inside nested mods. */
class FabricNestedJarRecursionTest {

    private static final String OLD_TYPE = "test/recursive/OldType";
    private static final String NEW_TYPE = "test/recursive/NewType";
    private static final String SYNTHETIC = "com/retromod/generated/recursive/Helper";
    private static final String DEEP_CLASS = "deep/Deep";

    @Test
    void secondLevelClassRefmapAndAccessWidenerAreTransformed(@TempDir Path dir)
            throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        try {
            transformer.registerClassRedirect(OLD_TYPE, NEW_TYPE);
            transformer.registerSyntheticClass(SYNTHETIC, emptyClass(SYNTHETIC));
            assertTrue(IntermediaryToMojangMapper.getInstance().isLoaded(),
                    "the bundled intermediary mapping table is required by this fixture");

            byte[] deepJar = jarOf(entries(
                    "fabric.mod.json", fabricMetadata("deep_fixture", null),
                    DEEP_CLASS + ".class", deepClass(),
                    "deep-refmap.json", refmap(),
                    "deep.accesswidener", accessWidener()));
            byte[] libraryJar = jarOf(entries(
                    "fabric.mod.json", fabricMetadata(
                            "library_fixture", "META-INF/jars/deep-refmap-lib.jar"),
                    "META-INF/jars/deep-refmap-lib.jar", deepJar));

            Path source = dir.resolve("outer.jar");
            Files.write(source, jarOf(entries(
                    "fabric.mod.json", fabricMetadata(
                            "outer_fixture", "META-INF/jars/refmap-library.jar"),
                    "META-INF/jars/refmap-library.jar", libraryJar)));
            Path outputDir = Files.createDirectories(dir.resolve("game/output"));

            Path output = new FabricModTransformer("26.2").transformMod(source, outputDir);

            byte[] libraryOut = readEntry(output, "META-INF/jars/refmap-library.jar");
            assertNotNull(libraryOut, "the first-level nested mod must remain present");
            byte[] deepOut = readEntry(libraryOut, "META-INF/jars/deep-refmap-lib.jar");
            assertNotNull(deepOut, "the second-level nested mod must remain present");

            ClassNode deepClass = readClass(readEntry(deepOut, DEEP_CLASS + ".class"));
            assertEquals("L" + NEW_TYPE + ";", deepClass.fields.get(0).desc,
                    "the class remap must reach the second-level archive");

            String deepKey = "outer.jar!/META-INF/jars/refmap-library.jar"
                    + "!/META-INF/jars/deep-refmap-lib.jar";
            String relocatedSynthetic = SyntheticEmbedder.embeddedBase(deepKey) + SYNTHETIC;
            assertEquals("L" + relocatedSynthetic + ";", deepClass.fields.get(1).desc,
                    "the helper name must include the complete nested archive chain");
            assertNotNull(readEntry(deepOut, relocatedSynthetic + ".class"),
                    "the stable second-level helper copy must be included");

            String refmap = new String(
                    readEntry(deepOut, "deep-refmap.json"), StandardCharsets.UTF_8);
            assertTrue(refmap.contains("net/minecraft/client/Minecraft"),
                    "the second-level refmap must gain official names");
            assertFalse(refmap.contains("net/minecraft/class_310"),
                    "no stale intermediary owner may remain in the emitted refmap");
            assertTrue(refmap.contains(";tick()V"),
                    "the second-level refmap method must gain its official name");
            assertFalse(refmap.contains("method_1574"),
                    "no stale intermediary method may remain in the emitted refmap");

            String accessWidener = new String(
                    readEntry(deepOut, "deep.accesswidener"), StandardCharsets.UTF_8);
            assertTrue(accessWidener.startsWith("accessWidener v2 official"),
                    "the second-level access widener must use the host namespace");
            assertTrue(accessWidener.contains("net/minecraft/client/Minecraft"));

        } finally {
            transformer.clearRedirectsForTesting();
            transformer.clearJarClassBytesProvider();
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
        }
    }

    @Test
    void recursionStopsAfterFourNestedArchives(@TempDir Path dir) throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        try {
            transformer.registerClassRedirect(OLD_TYPE, NEW_TYPE);

            byte[] child = jarOf(entries(
                    "fabric.mod.json", fabricMetadata("level_5", null),
                    "level5/Marker.class", classWithRedirect("level5/Marker")));
            for (int level = 4; level >= 1; level--) {
                String nestedPath = "META-INF/jars/level" + (level + 1) + ".jar";
                child = jarOf(entries(
                        "fabric.mod.json", fabricMetadata("level_" + level, nestedPath),
                        "level" + level + "/Marker.class",
                        classWithRedirect("level" + level + "/Marker"),
                        nestedPath, child));
            }

            Path source = dir.resolve("depth-limit.jar");
            Files.write(source, jarOf(entries(
                    "fabric.mod.json", fabricMetadata(
                            "depth_limit", "META-INF/jars/level1.jar"),
                    "META-INF/jars/level1.jar", child)));
            Path output = new FabricModTransformer("26.2").transformMod(
                    source, Files.createDirectories(dir.resolve("depth-game/output")));

            byte[] current = readEntry(output, "META-INF/jars/level1.jar");
            for (int level = 1; level <= 4; level++) {
                assertEquals("L" + NEW_TYPE + ";",
                        readClass(readEntry(current,
                                "level" + level + "/Marker.class")).fields.get(0).desc,
                        "nested level " + level + " must be transformed");
                current = readEntry(current,
                        "META-INF/jars/level" + (level + 1) + ".jar");
                assertNotNull(current, "nested level " + (level + 1) + " must remain present");
            }
            assertEquals("L" + OLD_TYPE + ";",
                    readClass(readEntry(current, "level5/Marker.class")).fields.get(0).desc,
                    "the fifth nested archive must remain outside the traversal limit");
        } finally {
            transformer.clearRedirectsForTesting();
            transformer.clearJarClassBytesProvider();
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
        }
    }

    @Test
    void packagingFallbackRecursesWithTheSameStableArchiveChain(@TempDir Path dir)
            throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            transformer.registerClassRedirect(OLD_TYPE, NEW_TYPE);
            transformer.registerSyntheticClass(SYNTHETIC, emptyClass(SYNTHETIC));

            byte[] deepJar = jarOf(entries(
                    "fabric.mod.json", fabricMetadata("fallback_deep", null),
                    DEEP_CLASS + ".class", deepClass(),
                    "deep.mixins.json", "{\"required\":true,\"mixins\":[]}"));
            byte[] libraryJar = jarOf(entries(
                    "META-INF/jars/deep.jar", deepJar));
            Path extracted = dir.resolve("fallback-extracted");
            Path nested = extracted.resolve("META-INF/jars/library.jar");
            Files.createDirectories(nested.getParent());
            Files.write(nested, libraryJar);

            Path original = dir.resolve("fallback-outer.jar");
            writeManifestJar(original);
            Path output = dir.resolve("fallback-output.jar");
            invokeRepackage(new FabricModTransformer("1.21.1"),
                    extracted, output, original);

            byte[] libraryOut = readEntry(output, "META-INF/jars/library.jar");
            byte[] deepOut = readEntry(libraryOut, "META-INF/jars/deep.jar");
            ClassNode deepClass = readClass(readEntry(deepOut, DEEP_CLASS + ".class"));
            assertEquals("L" + NEW_TYPE + ";", deepClass.fields.get(0).desc,
                    "the packaging fallback must recurse into the second-level class");

            String deepKey = "fallback-outer.jar!/META-INF/jars/library.jar"
                    + "!/META-INF/jars/deep.jar";
            String relocatedSynthetic = SyntheticEmbedder.embeddedBase(deepKey) + SYNTHETIC;
            assertEquals("L" + relocatedSynthetic + ";", deepClass.fields.get(1).desc);
            assertNotNull(readEntry(deepOut, relocatedSynthetic + ".class"));

            String mixinConfig = new String(
                    readEntry(deepOut, "deep.mixins.json"), StandardCharsets.UTF_8);
            assertTrue(mixinConfig.contains("\"required\": false"),
                    "second-level mixin config resources must be made non-fatal");
            assertTrue(mixinConfig.contains("\"defaultRequire\": 0"));
            String metadata = new String(
                    readEntry(deepOut, "fabric.mod.json"), StandardCharsets.UTF_8);
            assertEquals("1.21.1", com.google.gson.JsonParser.parseString(metadata)
                            .getAsJsonObject().getAsJsonObject("depends")
                            .get("minecraft").getAsString(),
                    "the fallback must update each nested mod's host constraint");
        } finally {
            transformer.clearRedirectsForTesting();
            transformer.clearJarClassBytesProvider();
        }
    }

    @Test
    void officialRecursionFailsClosedAtTraversalLimits(@TempDir Path dir) throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        try {
            transformer.registerClassRedirect(OLD_TYPE, NEW_TYPE);
            byte[] child = jarOf(entries(
                    "fabric.mod.json", fabricMetadata("budget_child", null),
                    DEEP_CLASS + ".class", classWithRedirect(DEEP_CLASS)));
            String parentMetadata = fabricMetadata(
                    "budget_parent", "META-INF/jars/deep.jar");
            Path parent = dir.resolve("budget-parent.jar");
            byte[] originalParent = jarOf(entries(
                    "fabric.mod.json", parentMetadata,
                    "META-INF/jars/deep.jar", child));
            Files.write(parent, originalParent);

            long parentExpansion = parentMetadata.getBytes(StandardCharsets.UTF_8).length
                    + child.length;
            RetromodTransformer.NestedArchiveBudget budget =
                    new RetromodTransformer.NestedArchiveBudget(parentExpansion, 100);
            Method method = FabricModTransformer.class.getDeclaredMethod(
                    "remapNestedJar", Path.class,
                    IntermediaryToMojangMapper.class, int.class, String.class,
                    RetromodTransformer.NestedArchiveBudget.class);
            method.setAccessible(true);

            InvocationTargetException failure = assertThrows(
                    InvocationTargetException.class,
                    () -> method.invoke(
                            new FabricModTransformer("26.2"), parent,
                            IntermediaryToMojangMapper.getInstance(), 1,
                            "budget-parent.jar", budget));

            assertInstanceOf(java.io.IOException.class, failure.getCause());
            assertArrayEquals(originalParent, Files.readAllBytes(parent),
                    "a rejected nested traversal must not publish partial changes");
            assertEquals(parentExpansion, budget.usedBytes(),
                    "nested traversal must stop at the configured limit");
            assertEquals(5, budget.usedEntries(),
                    "the traversal state must remain consistent after rejection");
        } finally {
            transformer.clearRedirectsForTesting();
            transformer.clearJarClassBytesProvider();
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
        }
    }

    @Test
    void nestedPackagingBoundsManifestExpansion(@TempDir Path dir) throws Exception {
        String manifest = "Manifest-Version: 1.0\r\nX-Fill: "
                + "a".repeat(2 * 1024 * 1024) + "\r\n\r\n";
        Path nested = dir.resolve("large-manifest.jar");
        byte[] original = jarOf(entries(
                JarFile.MANIFEST_NAME, manifest,
                "fabric.mod.json", fabricMetadata("large_manifest", null)));
        Files.write(nested, original);
        Method method = FabricModTransformer.class.getDeclaredMethod(
                "processNestedJiJJar", Path.class, String.class, int.class,
                RetromodTransformer.NestedArchiveBudget.class);
        method.setAccessible(true);

        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> method.invoke(new FabricModTransformer("26.2"), nested,
                        "outer.jar!/META-INF/jars/large-manifest.jar", 1,
                        RetromodTransformer.NestedArchiveBudget.defaults()));

        assertInstanceOf(java.io.IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("ZIP entry exceeds"),
                failure.getCause().getMessage());
        assertArrayEquals(original, Files.readAllBytes(nested),
                "a rejected nested manifest must leave the source archive unchanged");
    }

    private static LinkedHashMap<String, byte[]> entries(Object... values) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            Object value = values[i + 1];
            byte[] bytes = value instanceof byte[] data
                    ? data : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            entries.put(String.valueOf(values[i]), bytes);
        }
        return entries;
    }

    private static byte[] jarOf(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] readEntry(Path jar, String name) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = archive.getJarEntry(name);
            if (entry == null) return null;
            return archive.getInputStream(entry).readAllBytes();
        }
    }

    private static byte[] readEntry(byte[] jar, String name) throws Exception {
        try (JarInputStream input = new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                if (name.equals(entry.getName())) return input.readAllBytes();
            }
        }
        return null;
    }

    private static void invokeRepackage(FabricModTransformer transformer, Path source,
            Path output, Path original) throws Exception {
        Method method = FabricModTransformer.class.getDeclaredMethod(
                "repackageJar", Path.class, Path.class, Path.class);
        method.setAccessible(true);
        try {
            method.invoke(transformer, source, output, original);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static void writeManifestJar(Path jar) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream ignored = new JarOutputStream(
                Files.newOutputStream(jar), manifest)) {
            // The packaging path only needs the source manifest for this fixture.
        }
    }

    private static ClassNode readClass(byte[] bytes) {
        assertNotNull(bytes, "the expected class entry must remain present");
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] deepClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                DEEP_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "redirected", "L" + OLD_TYPE + ";",
                null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "helper", "L" + SYNTHETIC + ";",
                null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithRedirect(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "redirected", "L" + OLD_TYPE + ";",
                null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String fabricMetadata(String id, String nestedJar) {
        String jars = nestedJar == null
                ? "" : ",\"jars\":[{\"file\":\"" + nestedJar + "\"}]";
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\","
                + "\"version\":\"1.0.0\",\"depends\":{\"fabricloader\":\"*\","
                + "\"minecraft\":\"1.20.1\"}" + jars + "}";
    }

    private static String refmap() {
        return "{\"mappings\":{\"deep.DeepMixin\":{\"onTick\":"
                + "\"Lnet/minecraft/class_310;method_1574()V\"}},"
                + "\"data\":{\"intermediary\":{\"deep.DeepMixin\":{\"onTick\":"
                + "\"Lnet/minecraft/class_310;method_1574()V\"}}}}";
    }

    private static String accessWidener() {
        return "accessWidener v2 intermediary\n"
                + "accessible class net/minecraft/class_310\n";
    }
}
