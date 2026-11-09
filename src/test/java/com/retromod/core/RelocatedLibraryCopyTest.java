/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static com.retromod.testutil.RelocatedLibraryFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** A Retromod class copied into a mod names the game's Gson and SLF4J, on every path that copies one. */
class RelocatedLibraryCopyTest {

    private static final String HELPER = "com/retromod/fixture/RelocatedLogs";
    private static final String OLD_API = "oldapi/Logs";
    private static final String HOST = "26.3";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void clean() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        // The second clear removes what a reset hook that never fired registers on the first.
        transformer.clearRedirectsForTesting();
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A synthetic registered from relocated bytes is kept with the game's names")
    void registeredSyntheticNamesTheGamesLibraries() {
        transformer.registerSyntheticClass(HELPER, helper(HELPER, SHADED_SLF4J, SHADED_GSON));

        assertRestored(transformer.getSyntheticClasses().get(HELPER));
    }

    @Test
    @DisplayName("registerClassResource copies a relocated class resource with the game's names")
    void classResourceIsCopiedWithTheGamesNames() {
        registerHelper(transformer);

        assertRestored(transformer.getSyntheticClasses().get(HELPER));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"extracted folder", "jar file", "nested jar bytes"})
    @DisplayName("Each embed entry point writes the copy with the game's names")
    void everyEmbedEntryPointWritesTheGamesNames(String entryPoint, @TempDir Path dir) throws Exception {
        registerHelper(transformer);
        Map<String, byte[]> mod = Map.of("mod/Caller.class", caller("mod/Caller", HELPER));
        String embedded = SyntheticEmbedder.embeddedBase("relocmod.jar") + HELPER + ".class";

        Map<String, byte[]> output = switch (entryPoint) {
            // The Fabric and Forge runtime paths, outer mod and bundled library alike.
            case "extracted folder" -> {
                for (Map.Entry<String, byte[]> entry : mod.entrySet()) {
                    Path file = dir.resolve(entry.getKey());
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue());
                }
                assertEquals(1, SyntheticEmbedder.embed(dir, "relocmod.jar", transformer));
                yield Map.of(embedded, Files.readAllBytes(dir.resolve(embedded)));
            }
            // The CLI transform and batch commands and AOT, for the outer mod.
            case "jar file" -> {
                Path jar = dir.resolve("relocmod.jar");
                Files.write(jar, jar(mod));
                assertEquals(1, SyntheticEmbedder.embedIntoJarOrDiscard(jar, "relocmod.jar", transformer,
                        SyntheticLibraries.NONE));
                yield entries(Files.readAllBytes(jar));
            }
            // The CLI and AOT, for a bundled library.
            default -> {
                SyntheticEmbedder.ByteEmbeddingResult result =
                        SyntheticEmbedder.embedIntoJarBytes(jar(mod), "relocmod.jar", transformer);
                assertTrue(result.succeeded());
                yield entries(result.jarBytes());
            }
        };

        assertRestored(output.get(embedded));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Fabric runtime", "Forge runtime", "CLI transform", "CLI batch", "AOT"})
    @DisplayName("Each translation path leaves no relocated name in the mod or its bundled library")
    void everyPathCopiesTheGamesNames(String path, @TempDir Path dir) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = HOST;
        boolean fabric = path.startsWith("Fabric");
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(!fabric);
        Path input = Files.createDirectory(dir.resolve("in")).resolve("relocmod.jar");
        Files.write(input, fabric ? fabricMod() : forgeMod());
        Path out = Files.createDirectory(dir.resolve("out"));

        Path output = switch (path) {
            case "Fabric runtime" -> {
                registerHelper(transformer);
                yield new FabricModTransformer(HOST).transformMod(input, out);
            }
            case "Forge runtime" -> {
                registerHelper(transformer);
                yield new ForgeModTransformer(HOST).transformMod(input, out);
            }
            case "CLI transform" -> {
                // The CLI clears every registration before its own, as it does per input.
                transformer.onRegistrationReset(() -> registerHelper(transformer));
                CliHarness.transform(input, out.resolve("relocmod.jar"), HOST);
                yield out.resolve("relocmod.jar");
            }
            case "CLI batch" -> {
                transformer.onRegistrationReset(() -> registerHelper(transformer));
                CliHarness.batch(input.getParent(), out, HOST);
                yield onlyJar(out);
            }
            default -> {
                transformer.onRegistrationReset(() -> registerHelper(transformer));
                CliHarness.aot(input, out.resolve("relocmod.jar"), HOST);
                yield out.resolve("relocmod.jar");
            }
        };

        assertNotNull(output, path + " wrote no jar");
        Map<String, byte[]> outer = entries(Files.readAllBytes(output));
        String nestedPath = fabric ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
        Map<String, byte[]> nested = entries(outer.get(nestedPath));
        for (Map<String, byte[]> jar : List.of(outer, nested)) {
            List<String> copies = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : jar.entrySet()) {
                if (!entry.getKey().endsWith(".class")) continue;
                assertFalse(namesShadedLibrary(entry.getValue()), path + ": " + entry.getKey()
                        + " names Retromod's relocated libraries");
                if (entry.getKey().startsWith(SyntheticEmbedder.PREFIX)
                        && entry.getKey().endsWith("/" + HELPER + ".class")) {
                    copies.add(entry.getKey());
                    assertRestored(entry.getValue());
                }
            }
            assertEquals(1, copies.size(), path + " must embed the helper once per jar: " + jar.keySet());
        }
    }

    /** Registers the helper the way a shim does, read as a class resource, and points the old API at it. */
    private static void registerHelper(RetromodTransformer t) {
        Class<?> anchor = anchorServing(HELPER, helper(HELPER, SHADED_SLF4J, SHADED_GSON));
        assertTrue(SyntheticEmbedder.registerClassResource(t, HELPER, anchor));
        t.registerClassRedirect(OLD_API, HELPER);
    }

    /** The copy names the game's SLF4J and Gson and is otherwise the helper as written. */
    private static void assertRestored(byte[] copy) {
        assertNotNull(copy, "the helper was not copied");
        assertFalse(namesShadedLibrary(copy), "the copy still names Retromod's relocated libraries");
        ClassNode node = new ClassNode();
        new ClassReader(copy).accept(node, 0);
        FieldNode log = node.fields.stream().filter(f -> f.name.equals("LOG")).findFirst().orElseThrow();
        assertEquals("L" + HOST_SLF4J + "Logger;", log.desc);
        assertTrue(node.methods.stream().anyMatch(m -> m.desc.equals("(L" + HOST_GSON + "JsonObject;)Ljava/lang/Object;")),
                "parse must take the game's JsonObject");
    }

    /** {@code class name { static void run() { owner.warn("hello"); } }}. */
    private static byte[] caller(String name, String owner) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor run = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        run.visitCode();
        run.visitLdcInsn("hello");
        run.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "warn", "(Ljava/lang/String;)V", false);
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(1, 0);
        run.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Forge 1.20.1 mod calling the old API, with a bundled library mod that calls it too. */
    private static byte[] forgeMod() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("reloclib"));
        lib.put("lib/Caller.class", caller("lib/Caller", OLD_API));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("relocmod"));
        mod.put("mod/Caller.class", caller("mod/Caller", OLD_API));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
    }

    /** The same mod and library for Fabric 1.20.1. */
    private static byte[] fabricMod() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("fabric.mod.json", fabricJson("reloclib", ""));
        lib.put("lib/Caller.class", caller("lib/Caller", OLD_API));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("fabric.mod.json", fabricJson("relocmod", ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]"));
        mod.put("mod/Caller.class", caller("mod/Caller", OLD_API));
        mod.put("META-INF/jars/lib.jar", jar(lib));
        return jar(mod);
    }

    private static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fabricJson(String modId, String extra) {
        return ("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\"}" + extra + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> entries(byte[] jarBytes) throws Exception {
        assertNotNull(jarBytes, "the bundled library is missing from the output");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarInputStream in = new JarInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
                if (!entry.isDirectory()) entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static Path onlyJar(Path dir) throws Exception {
        try (var jars = Files.list(dir)) {
            List<Path> all = jars.filter(p -> p.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }
}
