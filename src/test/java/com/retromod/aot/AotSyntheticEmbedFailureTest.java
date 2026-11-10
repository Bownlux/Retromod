/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** An AOT jar whose stand-in could not be embedded is never cached or served. */
class AotSyntheticEmbedFailureTest {

    private static final String OLD_HELPER = "legacy/removed/FailHelper";
    private static final String HELPER = "com/retromod/generated/AotFailHelper";
    private static final String USES = "test/UsesHelper";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("A Fabric compile with the loader API runtime on fails instead of caching a broken jar")
    void fabricRuntimeOnCompileFails(@TempDir Path dir) throws Exception {
        ShimRegistry registry = new ShimRegistry();
        registry.register(new HelperShim("fabric", "1.20.1"));
        Path cache = dir.toRealPath().resolve("cache");
        Path input = collidingMod(dir, "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"failtest\","
                + "\"version\":\"1.0.0\",\"depends\":{\"minecraft\":\"1.20.1\"}}");
        try (AutoCloseable runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider())) {
            AotCompiler compiler = AotCompiler.forOfflineInputs(registry, "26.1", cache);

            assertFailsAndLeavesNoCache(compiler, input, cache);
        }
    }

    @Test
    @DisplayName("A picker compile of a Forge mod fails instead of caching a broken jar")
    void pickerCompileFails(@TempDir Path dir) throws Exception {
        transformer.registerSyntheticClass(HELPER, emptyClass(HELPER));
        transformer.registerClassRedirect(OLD_HELPER, HELPER);
        Path cache = dir.toRealPath().resolve("cache");
        Path input = collidingMod(dir, "META-INF/mods.toml", "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"
                + "license=\"MIT\"\n[[mods]]\nmodId=\"failtest\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.failtest]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20.1]\"\n");
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.3", cache);

        assertFailsAndLeavesNoCache(compiler, input, cache);
    }

    private static void assertFailsAndLeavesNoCache(AotCompiler compiler, Path input, Path cache)
            throws Exception {
        IOException failure = assertThrows(IOException.class, () -> compiler.compileModAot(input, true));
        assertTrue(failure.getMessage().contains(HELPER.replace('/', '.')), failure.getMessage());
        Path cached = cache.resolve(input.getFileName().toString().replace(".jar", "-aot.jar"));
        assertFalse(Files.exists(cached), "the cache must not keep a jar that names a missing class");
        assertThrows(IOException.class, () -> compiler.compileModAot(input, true),
                "a second compile must fail again, not serve a cached jar");
    }

    /** Registers the helper for one loader's mods, since offline compiles clear registrations first. */
    private record HelperShim(String loader, String source) implements VersionShim {
        @Override public String getShimName() { return "AOT embed failure fixture"; }
        @Override public String getSourceVersion() { return source; }
        @Override public String getTargetVersion() { return "26.1"; }
        @Override public String getModLoaderType() { return loader; }
        @Override public void registerRedirects(RetromodTransformer transformer) {
            transformer.registerSyntheticClass(HELPER, emptyClass(HELPER));
            transformer.registerClassRedirect(OLD_HELPER, HELPER);
        }
    }

    /** A mod that names the removed type and already holds a class where its copy would go. */
    private static Path collidingMod(Path dir, String metadataName, String metadata) throws Exception {
        Path jar = dir.resolve("fail-embed.jar");
        String occupied = SyntheticEmbedder.embeddedBase(jar.getFileName().toString()) + HELPER;
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, metadataName, metadata.getBytes(StandardCharsets.UTF_8));
            put(out, USES + ".class", classWithField(USES, OLD_HELPER));
            put(out, occupied + ".class", emptyClass(occupied));
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
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
}
