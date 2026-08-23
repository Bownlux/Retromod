/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
import com.retromod.testutil.SignedJarTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AotCompilerSecurityTest {

    @Test
    @DisplayName("archive budgets enforce total bytes and entry count atomically")
    void archiveBudgetEnforcesBothLimits() throws Exception {
        AotCompiler.ArchiveBudget budget = new AotCompiler.ArchiveBudget(10, 2);

        budget.reserve(6, "first.class");
        assertThrows(IOException.class, () -> budget.reserve(5, "too-large.class"));
        assertEquals(6, budget.usedBytes());
        assertEquals(1, budget.usedEntries());

        budget.reserve(4, "second.class");
        assertThrows(IOException.class, () -> budget.reserve(0, "third.class"));
        assertEquals(10, budget.usedBytes());
        assertEquals(2, budget.usedEntries());
    }

    @Test
    @DisplayName("AOT output limits cover every final entry after generated content and migration")
    void finalOutputBudgetMatchesPublishedArchive(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("input.jar");
        Path output = directory.resolve("output.jar");
        String potionPath = "data/example/tags/entity_type/potions.json";
        byte[] legacyPotion = "{\"values\":[\"minecraft:potion\"]}"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("fabric.mod.json", "{}".getBytes(StandardCharsets.UTF_8));
        entries.put(potionPath, legacyPotion);
        Files.write(input, jarBytes(entries));

        ShimRegistry registry = new ShimRegistry();
        registry.register(new VersionShim() {
            @Override public String getShimName() { return "budget fixture"; }
            @Override public String getSourceVersion() { return "1.20.1"; }
            @Override public String getTargetVersion() { return "26.2"; }
            @Override public String getModLoaderType() { return "fabric"; }
            @Override public void registerRedirects(RetromodTransformer transformer) {}
            @Override public String[] getShimClasses() {
                return new String[] {AotCompiler.class.getName()};
            }
        });
        AotCompiler compiler = new AotCompiler(registry, "26.2");
        ModVersionInfo mod = new ModVersionInfo(
                "test", "1.0", "1.20.1", "fabric", null,
                Set.of(), Set.of(), false);
        AotCompiler.ArchiveBudget budget =
                new AotCompiler.ArchiveBudget(Long.MAX_VALUE, Integer.MAX_VALUE);

        compiler.compileJar(input, output, mod, budget);

        long expandedBytes = 0;
        int entryCount = 0;
        try (JarFile jar = new JarFile(output.toFile())) {
            var jarEntries = jar.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry entry = jarEntries.nextElement();
                expandedBytes += entry.getSize();
                entryCount++;
            }
            assertTrue(jar.getJarEntry("META-INF/MANIFEST.MF") != null);
            assertTrue(jar.getJarEntry("META-INF/") != null);
            assertTrue(jar.getJarEntry("retromod_aot.properties") != null);
            assertTrue(jar.getJarEntry(
                    "retromod_embedded/com/retromod/aot/AotCompiler.class") != null);
            assertTrue(jar.getJarEntry(potionPath).getSize() > legacyPotion.length,
                    "the migrated resource must expand for this budget regression");
        }
        assertEquals(expandedBytes, budget.usedBytes());
        assertEquals(entryCount, budget.usedEntries());

        byte[] previous = "previous output".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);
        AotCompiler.ArchiveBudget shortByOneByte =
                new AotCompiler.ArchiveBudget(expandedBytes - 1, Integer.MAX_VALUE);
        IOException byteFailure = assertThrows(IOException.class,
                () -> compiler.compileJar(input, output, mod, shortByOneByte));
        assertTrue(byteFailure.getMessage().contains("retromod_aot.properties"),
                byteFailure.getMessage());
        assertArrayEquals(previous, Files.readAllBytes(output));

        AotCompiler.ArchiveBudget shortByOneEntry =
                new AotCompiler.ArchiveBudget(Long.MAX_VALUE, entryCount - 1);
        IOException entryFailure = assertThrows(IOException.class,
                () -> compiler.compileJar(input, output, mod, shortByOneEntry));
        assertTrue(entryFailure.getMessage().contains("retromod_aot.properties"),
                entryFailure.getMessage());
        assertArrayEquals(previous, Files.readAllBytes(output));
    }

    @Test
    @DisplayName("overlapping AOT hierarchy scopes stay isolated by transform thread")
    void overlappingHierarchyScopesAreIsolated() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        byte[] fallback = new byte[] {9};
        byte[] firstBytes = new byte[] {1};
        byte[] secondBytes = new byte[] {2};
        transformer.setJarClassBytesProvider(name -> fallback);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> {
                try (var ignored = transformer.pushJarClassBytesProvider(name -> firstBytes)) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out in first hierarchy scope");
                    }
                    assertArrayEquals(firstBytes,
                            RetromodTransformer.readCurrentJarClassForAdapter("fixture/Type"));
                }
                assertArrayEquals(fallback,
                        RetromodTransformer.readCurrentJarClassForAdapter("fixture/Type"));
                return null;
            });
            var second = workers.submit(() -> {
                try (var ignored = transformer.pushJarClassBytesProvider(name -> secondBytes)) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out in second hierarchy scope");
                    }
                    assertArrayEquals(secondBytes,
                            RetromodTransformer.readCurrentJarClassForAdapter("fixture/Type"));
                }
                assertArrayEquals(fallback,
                        RetromodTransformer.readCurrentJarClassForAdapter("fixture/Type"));
                return null;
            });

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertArrayEquals(fallback,
                    RetromodTransformer.readCurrentJarClassForAdapter("fixture/Type"));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            transformer.clearJarClassBytesProvider();
        }
    }

    @Test
    @DisplayName("sequential AOT outputs report only their own transformed class count")
    void sequentialCompileCountersAreIsolated(@TempDir Path directory) throws Exception {
        Path firstInput = directory.resolve("first-input.jar");
        Path firstOutput = directory.resolve("first-output.jar");
        Path secondInput = directory.resolve("second-input.jar");
        Path secondOutput = directory.resolve("second-output.jar");
        Files.write(firstInput, jarBytes(Map.of(
                "fixture/First.class", plainClass("fixture/First"),
                "fixture/Second.class", plainClass("fixture/Second"))));
        Files.write(secondInput, jarBytes(Map.of(
                "fixture/Third.class", plainClass("fixture/Third"))));
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "1.20.1");
        ModVersionInfo mod = new ModVersionInfo(
                "fixture", "1.0", "1.20.1", "fabric", null,
                Set.of("fixture/"), Set.of(), false);

        compiler.compileJar(firstInput, firstOutput, mod,
                new AotCompiler.ArchiveBudget(Long.MAX_VALUE, Integer.MAX_VALUE));
        compiler.compileJar(secondInput, secondOutput, mod,
                new AotCompiler.ArchiveBudget(Long.MAX_VALUE, Integer.MAX_VALUE));

        assertEquals("2", aotMetadata(firstOutput).getProperty("classes_transformed"));
        assertEquals("1", aotMetadata(secondOutput).getProperty("classes_transformed"));
        assertEquals("0", aotMetadata(secondOutput).getProperty("classes_jit_fallback"));
    }

    @Test
    @DisplayName("AOT detects Quilt as the Fabric bytecode family and patches Quilt metadata")
    void quiltInputUsesFabricShimsAndQuiltMetadata(@TempDir Path directory) throws Exception {
        Path realDirectory = directory.toRealPath();
        String metadata = """
                {"schema_version":1,"quilt_loader":{
                  "group":"fixture","id":"quilt_fixture","version":"1.0",
                  "depends":[
                    {"id":"quilt_loader","versions":">=0.20.0"},
                    {"id":"minecraft","versions":"~1.20.1"}
                  ]
                },"depends":{"minecraft":"must-stay"}}
                """;
        Path input = realDirectory.resolve("quilt-input.jar");
        Files.write(input, jarBytes(Map.of(
                "quilt.mod.json", metadata.getBytes(StandardCharsets.UTF_8))));

        ShimRegistry registry = new ShimRegistry();
        registry.register(new VersionShim() {
            @Override public String getShimName() { return "Quilt Fabric-family fixture"; }
            @Override public String getSourceVersion() { return "1.20.1"; }
            @Override public String getTargetVersion() { return "26.2"; }
            @Override public String getModLoaderType() { return "fabric"; }
            @Override public void registerRedirects(RetromodTransformer transformer) {}
        });
        AotCompiler compiler = AotCompiler.forOfflineInputs(
                registry, "26.2", realDirectory.resolve("cache"));

        Path output = compiler.compileModAot(input, true);

        assertNotEquals(input, output);
        try (JarFile jar = new JarFile(output.toFile());
             var stream = jar.getInputStream(jar.getJarEntry("quilt.mod.json"))) {
            byte[] updated = stream.readAllBytes();
            assertEquals("=26.2",
                    com.retromod.core.QuiltMetadataCompat.readMinecraftVersion(updated));
            String json = new String(updated, StandardCharsets.UTF_8);
            assertTrue(json.contains("\"minecraft\": \"must-stay\""), json);
        }
        assertEquals("quilt", aotMetadata(output).getProperty("mod_loader"));
    }

    @Test
    @DisplayName("AOT removes stale signing records from changed outer and nested archives")
    void signedOuterAndNestedArchivesAreSanitized(@TempDir Path directory) throws Exception {
        Path realDirectory = directory.toRealPath();
        byte[] oldFabricMetadata = """
                {"schemaVersion":1,"id":"fixture","version":"1.0",
                 "depends":{"minecraft":"1.20.1"}}
                """.getBytes(StandardCharsets.UTF_8);
        Path nested = SignedJarTestSupport.createSignedJar(realDirectory, "nested.jar",
                SignedJarTestSupport.entries(
                        "fabric.mod.json", oldFabricMetadata,
                        "assets/fixture/nested.txt", new byte[]{1, 2, 3}));
        Path input = SignedJarTestSupport.createSignedJar(realDirectory, "signed-input.jar",
                SignedJarTestSupport.entries(
                        "fabric.mod.json", oldFabricMetadata,
                        "META-INF/jars/nested.jar", Files.readAllBytes(nested)));
        AotCompiler compiler = AotCompiler.forOfflineInputs(
                new ShimRegistry(), "26.2", realDirectory.resolve("cache"));

        Path output = compiler.compileModAot(input, true);

        SignedJarTestSupport.verifyEveryEntry(output);
        assertFalse(SignedJarTestSupport.hasSigningMetadata(output));
        byte[] nestedOutput;
        try (JarFile jar = new JarFile(output.toFile(), true);
             var stream = jar.getInputStream(jar.getJarEntry("META-INF/jars/nested.jar"))) {
            nestedOutput = stream.readAllBytes();
        }
        Path extractedNested = realDirectory.resolve("nested-output.jar");
        Files.write(extractedNested, nestedOutput);
        SignedJarTestSupport.verifyEveryEntry(extractedNested);
        assertFalse(SignedJarTestSupport.hasSigningMetadata(extractedNested));
    }

    @Test
    @DisplayName("nested archive output refuses growth past its limit")
    void nestedArchiveOutputIsBounded() throws Exception {
        AotCompiler.BoundedArchiveOutput output =
                new AotCompiler.BoundedArchiveOutput(10, 1000);
        byte[] first = new byte[6];
        byte[] rejected = new byte[5];

        output.write(first);
        assertThrows(IOException.class, () -> output.write(rejected));
        assertEquals(6, output.size(), "a rejected write must not consume the budget");

        output.write(new byte[4]);
        assertEquals(10, output.size());
        assertEquals(10, output.toByteArray().length);
    }

    @Test
    @DisplayName("nested archive traversal shares one expanded-byte budget")
    void nestedArchiveTraversalUsesSharedBudget() throws Exception {
        byte[] nestedJar = jarBytes(Map.of("assets/test/data.bin", new byte[6]));
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2");
        Method transformNested = AotCompiler.class.getDeclaredMethod(
                "transformNestedJarAot", byte[].class, int.class,
                MixinCompatibilityTransformer.class, boolean.class,
                RetromodTransformer.NestedArchiveBudget.class);
        transformNested.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> transformNested.invoke(compiler, nestedJar, 1,
                        new MixinCompatibilityTransformer(RetromodTransformer.getInstance()),
                        false, new RetromodTransformer.NestedArchiveBudget(11, 10)));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    @DisplayName("nested refmap reads use the index and traversal budget")
    void nestedRefmapReadsUseSharedBudget() throws Exception {
        byte[] nestedJar = jarBytes(Map.of(
                "test-refmap.json", "{}".getBytes(StandardCharsets.UTF_8)));
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2");
        Method transformNested = AotCompiler.class.getDeclaredMethod(
                "transformNestedJarAot", byte[].class, int.class,
                MixinCompatibilityTransformer.class, boolean.class,
                RetromodTransformer.NestedArchiveBudget.class);
        transformNested.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> transformNested.invoke(compiler, nestedJar, 1,
                        new MixinCompatibilityTransformer(RetromodTransformer.getInstance()),
                        false, new RetromodTransformer.NestedArchiveBudget(3, 10)));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    @DisplayName("nested AOT siblings share one archive budget")
    void nestedAotSiblingsShareBudget() throws Exception {
        byte[] sibling = jarBytes(Map.of("assets/test/data.bin", new byte[6]));
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2");
        Method transformNested = AotCompiler.class.getDeclaredMethod(
                "transformNestedJarAot", byte[].class, int.class,
                MixinCompatibilityTransformer.class, boolean.class,
                RetromodTransformer.NestedArchiveBudget.class);
        transformNested.setAccessible(true);
        RetromodTransformer.NestedArchiveBudget budget =
                new RetromodTransformer.NestedArchiveBudget(12, 3);
        MixinCompatibilityTransformer mixins =
                new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        transformNested.invoke(compiler, sibling, 1, mixins, false, budget);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> transformNested.invoke(compiler, sibling, 1, mixins, false, budget));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    @DisplayName("nested AOT recursion keeps the parent archive budget")
    void nestedAotRecursionSharesBudget() throws Exception {
        byte[] child = jarBytes(Map.of("fabric.mod.json", new byte[4]));
        Map<String, byte[]> parentEntries = new java.util.LinkedHashMap<>();
        parentEntries.put("fabric.mod.json", new byte[4]);
        parentEntries.put("META-INF/jars/child.jar", child);
        byte[] parent = jarBytes(parentEntries);
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2");
        Method transformNested = AotCompiler.class.getDeclaredMethod(
                "transformNestedJarAot", byte[].class, int.class,
                MixinCompatibilityTransformer.class, boolean.class,
                RetromodTransformer.NestedArchiveBudget.class);
        transformNested.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> transformNested.invoke(compiler, parent, 1,
                        new MixinCompatibilityTransformer(RetromodTransformer.getInstance()),
                        false, new RetromodTransformer.NestedArchiveBudget(
                                Long.MAX_VALUE, 5)));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("child.jar"),
                failure.getCause().getMessage());
    }

    @Test
    @DisplayName("a failed AOT rewrite preserves the previous output")
    void failedRewritePreservesPreviousOutput(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("input.jar");
        Path output = directory.resolve("output.jar");
        byte[] previous = "previous output".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);
        writeJar(input, "retromod_aot.properties", "source metadata".getBytes(StandardCharsets.UTF_8));

        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "1.20.1");
        Method compileJar = AotCompiler.class.getDeclaredMethod(
                "compileJar", Path.class, Path.class, ModVersionInfo.class);
        compileJar.setAccessible(true);
        ModVersionInfo mod = new ModVersionInfo(
                "test", "1.0", "1.20.1", "fabric", null,
                Set.of("test/"), Set.of(), false);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> compileJar.invoke(compiler, input, output, mod));
        assertInstanceOf(IOException.class, failure.getCause());
        assertArrayEquals(previous, Files.readAllBytes(output));

        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count(), "failed staged outputs must be removed");
        }
    }

    @Test
    @DisplayName("an unsafe nested archive keeps the previous AOT output")
    void unsafeNestedArchiveKeepsPreviousOutput(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("nested-input.jar");
        Path output = directory.resolve("nested-output.jar");
        byte[] previous = "previous output".getBytes(StandardCharsets.UTF_8);
        byte[] unsafeChild = jarBytes(Map.of("../Bad.class", new byte[] {1}));
        Map<String, byte[]> outerEntries = new java.util.LinkedHashMap<>();
        outerEntries.put("fabric.mod.json", "{}".getBytes(StandardCharsets.UTF_8));
        outerEntries.put("META-INF/jars/child.jar", unsafeChild);
        Files.write(input, jarBytes(outerEntries));
        Files.write(output, previous);

        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "1.20.1");
        Method compileJar = AotCompiler.class.getDeclaredMethod(
                "compileJar", Path.class, Path.class, ModVersionInfo.class);
        compileJar.setAccessible(true);
        ModVersionInfo mod = new ModVersionInfo(
                "test", "1.0", "1.20.1", "fabric", null,
                Set.of(), Set.of(), false);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> compileJar.invoke(compiler, input, output, mod));
        assertInstanceOf(IOException.class, failure.getCause());
        assertArrayEquals(previous, Files.readAllBytes(output));

        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count(), "failed staged outputs must be removed");
        }
    }

    @Test
    @DisplayName("source hashes match SHA-256 without loading the file at once")
    void sourceHashMatchesSha256(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("source.bin");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AotCompiler.computeHash(file));
    }

    @Test
    @DisplayName("source hash read failures are reported")
    void sourceHashReadFailureIsReported(@TempDir Path directory) {
        Path missing = directory.resolve("missing.jar");

        assertThrows(IOException.class, () -> AotCompiler.computeHash(missing));
    }

    @Test
    @DisplayName("AOT manifest values cannot create additional headers")
    void aotManifestValuesCannotCreateAdditionalHeaders(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("manifest-input.jar");
        Path output = directory.resolve("manifest-output.jar");
        writeJar(input, "fabric.mod.json", "{}".getBytes(StandardCharsets.UTF_8));
        AotCompiler compiler = new AotCompiler(
                new ShimRegistry(), "26.2\r\nTarget-Injected: yes",
                directory.toRealPath().resolve("cache"));
        Method compileJar = AotCompiler.class.getDeclaredMethod(
                "compileJar", Path.class, Path.class, ModVersionInfo.class);
        compileJar.setAccessible(true);
        ModVersionInfo mod = new ModVersionInfo(
                "test", "1.0", "1.20.1\r\nSource-Injected: yes", "fabric", null,
                Set.of(), Set.of(), false);

        compileJar.invoke(compiler, input, output, mod);

        try (JarFile jar = new JarFile(output.toFile())) {
            var attributes = jar.getManifest().getMainAttributes();
            assertEquals("unknown",
                    attributes.getValue("Retromod-Source-Version"));
            assertEquals("26.2__Target-Injected: yes",
                    attributes.getValue("Retromod-Target-Version"));
            assertNull(attributes.getValue("Source-Injected"));
            assertNull(attributes.getValue("Target-Injected"));
        }
    }

    @Test
    @DisplayName("a symlinked AOT cache root disables compilation")
    void symlinkedAotCacheRootFailsClosed(@TempDir Path directory) throws Exception {
        Path realDirectory = directory.toRealPath();
        Path outside = Files.createDirectory(realDirectory.resolve("outside-cache"));
        Path sentinel = outside.resolve("sentinel.txt");
        Files.writeString(sentinel, "keep", StandardCharsets.UTF_8);
        Path cacheLink = realDirectory.resolve("cache-link");
        Files.createSymbolicLink(cacheLink, outside);
        AotCompiler compiler = new AotCompiler(
                new ShimRegistry(), "26.2", cacheLink);

        IOException failure = assertThrows(IOException.class,
                () -> compiler.compileModAot(realDirectory.resolve("mod.jar")));

        assertTrue(failure.getMessage().contains("could not be validated"),
                failure.getMessage());
        assertEquals("keep", Files.readString(sentinel, StandardCharsets.UTF_8));
        assertTrue(Files.isSymbolicLink(cacheLink));
    }

    @Test
    @DisplayName("AOT caches cannot cross target Minecraft versions")
    void cachedTargetVersionMustMatch(@TempDir Path directory) throws Exception {
        Path realDirectory = directory.toRealPath();
        Path source = realDirectory.resolve("source.jar");
        Files.writeString(source, "source bytes", StandardCharsets.UTF_8);
        String sourceHash = AotCompiler.computeHash(source);
        Path wrongTarget = realDirectory.resolve("wrong-target.jar");
        Path matchingTarget = realDirectory.resolve("matching-target.jar");
        Path emptyHash = realDirectory.resolve("empty-hash.jar");
        writeCacheJar(wrongTarget, "26.1", sourceHash);
        writeCacheJar(matchingTarget, "26.2", sourceHash);
        writeCacheJar(emptyHash, "26.2", "");
        AotCompiler compiler = new AotCompiler(
                new ShimRegistry(), "26.2", realDirectory.resolve("cache"));
        Method valid = AotCompiler.class.getDeclaredMethod(
                "isValidCache", Path.class, Path.class);
        valid.setAccessible(true);

        assertFalse((boolean) valid.invoke(compiler, wrongTarget, source));
        assertTrue((boolean) valid.invoke(compiler, matchingTarget, source));
        assertFalse((boolean) valid.invoke(compiler, emptyHash, source));
    }

    @Test
    @DisplayName("AOT caches stay within their registration and request context")
    void cachedTransformationContextMustMatch(@TempDir Path directory) throws Exception {
        Path realDirectory = directory.toRealPath();
        Path source = realDirectory.resolve("source.jar");
        Files.writeString(source, "source bytes", StandardCharsets.UTF_8);
        String sourceHash = AotCompiler.computeHash(source);
        Path automaticCache = realDirectory.resolve("automatic.jar");
        Path explicitCache = realDirectory.resolve("explicit.jar");
        writeCacheJar(automaticCache, "26.2", sourceHash, "runtime:automatic");
        writeCacheJar(explicitCache, "26.2", sourceHash, "runtime:explicit");

        AotCompiler runtimeCompiler = new AotCompiler(
                new ShimRegistry(), "26.2", realDirectory.resolve("runtime-cache"));
        AotCompiler offlineCompiler = AotCompiler.forOfflineInputs(
                new ShimRegistry(), "26.2", realDirectory.resolve("offline-cache"));
        Method automaticValid = AotCompiler.class.getDeclaredMethod(
                "isValidCache", Path.class, Path.class);
        automaticValid.setAccessible(true);
        Method contextualValid = AotCompiler.class.getDeclaredMethod(
                "isValidCache", Path.class, Path.class, boolean.class);
        contextualValid.setAccessible(true);

        assertTrue((boolean) automaticValid.invoke(runtimeCompiler, automaticCache, source));
        assertFalse((boolean) automaticValid.invoke(offlineCompiler, automaticCache, source));
        assertFalse((boolean) contextualValid.invoke(
                runtimeCompiler, automaticCache, source, true));
        assertTrue((boolean) contextualValid.invoke(
                runtimeCompiler, explicitCache, source, true));
        assertFalse((boolean) automaticValid.invoke(runtimeCompiler, explicitCache, source));
    }

    @Test
    @DisplayName("AOT cache validation bounds manifest expansion")
    void cachedManifestReadIsBounded(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("source.jar");
        Files.writeString(source, "source bytes", StandardCharsets.UTF_8);
        Path cached = directory.resolve("oversized-cache.jar");
        String manifest = "Manifest-Version: 1.0\r\n"
                + "Retromod-AOT-Version: " + AotCompiler.AOT_VERSION + "\r\n"
                + "Retromod-AOT-Context: runtime:automatic\r\n"
                + "Retromod-Target-Version: 26.2\r\n"
                + "Retromod-Source-Hash: " + AotCompiler.computeHash(source) + "\r\n"
                + "X-Fill: " + "a".repeat(2 * 1024 * 1024) + "\r\n\r\n";
        writeJar(cached, JarFile.MANIFEST_NAME,
                manifest.getBytes(StandardCharsets.UTF_8));
        AotCompiler compiler = new AotCompiler(
                new ShimRegistry(), "26.2", directory.resolve("cache"));
        Method valid = AotCompiler.class.getDeclaredMethod(
                "isValidCache", Path.class, Path.class);
        valid.setAccessible(true);

        assertFalse((boolean) valid.invoke(compiler, cached, source));
    }

    @Test
    @DisplayName("AOT compilation refuses a symbolic-link mod input")
    void symlinkedModInputIsRefusedBeforeReadsOrWrites(@TempDir Path directory)
            throws Exception {
        Path realDirectory = directory.toRealPath();
        Path source = realDirectory.resolve("source.jar");
        writeFabricMod(source);
        Path linkedInput = realDirectory.resolve("linked.jar");
        Files.createSymbolicLink(linkedInput, source);
        Path cache = realDirectory.resolve("cache");
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2", cache);

        IOException failure = assertThrows(IOException.class,
                () -> compiler.compileModAot(linkedInput));

        assertTrue(failure.getMessage().contains("non-symlink"), failure.getMessage());
        assertFalse(Files.exists(cache.resolve("linked-aot.jar"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(realDirectory.resolve("retromod-backups"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    @DisplayName("AOT compilation refuses a symbolic-link cache file")
    void symlinkedCacheFileIsRefusedBeforeBackup(@TempDir Path directory) throws Exception {
        Path realDirectory = directory.toRealPath();
        Path source = realDirectory.resolve("source.jar");
        writeFabricMod(source);
        Path cache = realDirectory.resolve("cache");
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2", cache);
        Path outside = Files.writeString(realDirectory.resolve("outside.jar"), "keep");
        Files.createSymbolicLink(cache.resolve("source-aot.jar"), outside);

        IOException failure = assertThrows(IOException.class,
                () -> compiler.compileModAot(source));

        assertTrue(failure.getMessage().contains("symbolic link"), failure.getMessage());
        assertEquals("keep", Files.readString(outside));
        assertFalse(Files.exists(realDirectory.resolve("retromod-backups"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    @DisplayName("AOT compilation refuses a symbolic-link backup directory")
    void symlinkedBackupDirectoryIsRefused(@TempDir Path directory) throws Exception {
        Path realDirectory = directory.toRealPath();
        Path mods = Files.createDirectory(realDirectory.resolve("mods"));
        Path source = mods.resolve("source.jar");
        writeFabricMod(source);
        Path outside = Files.createDirectory(realDirectory.resolve("outside-backups"));
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "keep");
        Files.createSymbolicLink(mods.resolve("retromod-backups"), outside);
        Path cache = realDirectory.resolve("cache");
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2", cache);

        IOException failure = assertThrows(IOException.class,
                () -> compiler.compileModAot(source));

        assertTrue(failure.getMessage().contains("backup directory"), failure.getMessage());
        assertEquals("keep", Files.readString(sentinel));
        assertFalse(Files.exists(cache.resolve("source-aot.jar"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    private static void writeJar(Path path, String name, byte[] data) throws Exception {
        Files.write(path, jarBytes(Map.of(name, data)));
    }

    private static void writeCacheJar(Path path, String targetVersion, String sourceHash)
            throws Exception {
        writeCacheJar(path, targetVersion, sourceHash, "runtime:automatic");
    }

    private static void writeCacheJar(Path path, String targetVersion, String sourceHash,
                                      String context) throws Exception {
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(
                java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(
                "Retromod-AOT-Version", AotCompiler.AOT_VERSION);
        manifest.getMainAttributes().putValue("Retromod-Target-Version", targetVersion);
        manifest.getMainAttributes().putValue("Retromod-Source-Hash", sourceHash);
        manifest.getMainAttributes().putValue("Retromod-AOT-Context", context);
        String selfHash = AotCacheStamp.currentSelfHash();
        if (!selfHash.isEmpty()) {
            manifest.getMainAttributes().putValue("Retromod-Self-Hash", selfHash);
        }
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(path), manifest)) {
            // The manifest is the cache validation surface for this regression.
        }
    }

    private static void writeFabricMod(Path path) throws Exception {
        String metadata = """
                {"schemaVersion":1,"id":"fixture","version":"1.0",
                 "depends":{"minecraft":"1.20.1"}}
                """;
        Files.write(path, jarBytes(Map.of(
                "fabric.mod.json", metadata.getBytes(StandardCharsets.UTF_8))));
    }

    private static byte[] jarBytes(Map<String, byte[]> entries) throws Exception {
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

    private static Properties aotMetadata(Path jarPath) throws Exception {
        Properties metadata = new Properties();
        try (JarFile jar = new JarFile(jarPath.toFile());
             var input = jar.getInputStream(jar.getJarEntry("retromod_aot.properties"))) {
            metadata.load(input);
        }
        return metadata;
    }

    private static byte[] plainClass(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName,
                null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
