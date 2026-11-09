/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A runtime AOT compile, as the in-game picker runs it, relocates the stand-ins its mod uses. */
class AotSyntheticEmbeddingTest {

    private static final String OLD_HELPER = "legacy/removed/TopHelper";
    private static final String HELPER = "com/retromod/generated/AotTopLevelHelper";
    private static final String UNUSED = "com/retromod/generated/AotUnusedHelper";
    private static final String USES = "test/UsesHelper";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"forge", "neoforge", "fabric"})
    @DisplayName("A picker compile embeds the helper its mod calls under the mod's own package")
    void runtimeCompileEmbedsReferencedHelpers(String loader, @TempDir Path dir) throws Exception {
        registerHelpers(transformer);
        Path input = modJar(dir, loader);
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.3",
                dir.toRealPath().resolve("cache"));

        Path output = compiler.compileModAot(input, true);

        String relocated = SyntheticEmbedder.embeddedBase(input.getFileName().toString()) + HELPER;
        Map<String, byte[]> entries = entries(output);
        assertEquals("L" + relocated + ";", node(entries.get(USES + ".class")).fields.get(0).desc,
                "the mod's field must name its own copy of the helper");
        assertEquals(relocated, new ClassReader(entries.get(relocated + ".class")).getClassName());
        assertTrue(entries.keySet().stream().noneMatch(name -> name.startsWith("retromod_embedded/")),
                "no loader resolves the old shared folder, so it is not written");
        assertTrue(entries.keySet().stream().noneMatch(name -> name.endsWith(UNUSED + ".class")),
                "a helper the mod never names stays out of its jar");

        // A cache hit serves the jar that was embedded in place.
        Path cached = compiler.compileModAot(input, true);
        assertEquals(output, cached);
        assertTrue(entries(cached).containsKey(relocated + ".class"));
    }

    @Test
    @DisplayName("An offline compile leaves the embedding to the CLI, which knows the batch's libraries")
    void offlineCompileLeavesEmbeddingToTheCli(@TempDir Path dir) throws Exception {
        // Offline compiles clear every registration first, so the helper comes from a shim.
        ShimRegistry registry = new ShimRegistry();
        registry.register(new HelperShim());
        Path input = modJar(dir, "forge");
        AotCompiler compiler = AotCompiler.forOfflineInputs(registry, "26.3",
                dir.toRealPath().resolve("cache"));

        Path output = compiler.compileModAot(input, true);

        Map<String, byte[]> entries = entries(output);
        assertEquals("L" + HELPER + ";", node(entries.get(USES + ".class")).fields.get(0).desc,
                "the CLI relocates the helper after this call");
        assertTrue(entries.keySet().stream().noneMatch(name -> name.startsWith(SyntheticEmbedder.PREFIX)
                        || name.startsWith("retromod_embedded/")),
                "nothing is embedded before the CLI's library-aware pass");
    }

    /** Registers the helper the fixture's old type moves to, and one nothing names. */
    private static void registerHelpers(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, emptyClass(HELPER));
        transformer.registerSyntheticClass(UNUSED, emptyClass(UNUSED));
        transformer.registerClassRedirect(OLD_HELPER, HELPER);
    }

    /** A Forge 1.20 shim, the milestone a 1.20.1 mod resolves to, that registers the fixture's helpers. */
    private static final class HelperShim implements VersionShim {
        @Override public String getShimName() { return "AOT embedding fixture"; }
        @Override public String getSourceVersion() { return "1.20"; }
        @Override public String getTargetVersion() { return "26.3"; }
        @Override public String getModLoaderType() { return "forge"; }
        @Override public void registerRedirects(RetromodTransformer transformer) {
            registerHelpers(transformer);
        }
    }

    /** A mod for {@code loader} whose class holds a field of the removed type. */
    private static Path modJar(Path dir, String loader) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String metadata = switch (loader) {
            case "forge" -> "META-INF/mods.toml";
            case "neoforge" -> "META-INF/neoforge.mods.toml";
            default -> "fabric.mod.json";
        };
        String text = switch (loader) {
            case "forge" -> toml("[1.20.1,1.21)");
            case "neoforge" -> toml("[1.21.1,1.22)");
            default -> "{\"schemaVersion\":1,\"id\":\"embedtest\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.20.1\"}}";
        };
        entries.put(metadata, text.getBytes(StandardCharsets.UTF_8));
        entries.put(USES + ".class", classWithField(USES, OLD_HELPER));
        Path jar = dir.resolve(loader + "-embed-test.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String toml(String minecraftRange) {
        return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"embedtest\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.embedtest]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"" + minecraftRange + "\"\n";
    }

    private static byte[] classWithField(String name, String fieldType) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "helper", "L" + fieldType + ";", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Map<String, byte[]> entries(Path jar) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), file.getInputStream(entry).readAllBytes());
                }
            }
        }
        return entries;
    }
}
