/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.api.common.MixinExtrasApiShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AotKnownSourceApiShimTest {

    private static final String CALLER = "fixture/KnownSourceAotCaller";
    private Path cachedOutput;

    @AfterEach
    void cleanUp() throws Exception {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
        if (cachedOutput != null) {
            Files.deleteIfExists(cachedOutput);
        }
    }

    @Test
    void knownSourceAotAppliesCommonMixinExtrasShim(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("known-source-api-" + System.nanoTime() + ".jar");
        writeFabricMod(input, "1.21.4");
        cachedOutput = Path.of("config/retromod/aot-cache")
                .resolve(input.getFileName().toString().replace(".jar", "-aot.jar"));
        Files.deleteIfExists(cachedOutput);

        ShimRegistry registry = new ShimRegistry();
        registry.register(versionEdge());
        registry.register(new MixinExtrasApiShim());

        Path output = new AotCompiler(registry, "26.1").compileModAot(input);
        MethodInsnNode call = onlyCall(readClass(output));

        assertEquals("com/retromod/shim/api/common/embedded/MixinExtrasShim", call.owner);
        assertEquals("noopInit", call.name);
    }

    @Test
    void explicitSameMinecraftVersionAotStillAppliesApiRepairs(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("same-version-api-" + System.nanoTime() + ".jar");
        writeFabricMod(input, "26.1");
        cachedOutput = Path.of("config/retromod/aot-cache")
                .resolve(input.getFileName().toString().replace(".jar", "-aot.jar"));
        Files.deleteIfExists(cachedOutput);

        ShimRegistry registry = new ShimRegistry();
        registry.register(new MixinExtrasApiShim());

        Path output = new AotCompiler(registry, "26.1").compileModAot(input, true);
        MethodInsnNode call = onlyCall(readClass(output));

        assertEquals("com/retromod/shim/api/common/embedded/MixinExtrasShim", call.owner);
        assertEquals("noopInit", call.name);
    }

    @Test
    void apiRepairCanRunWithoutAMinecraftGraphPath(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("api-only-path-" + System.nanoTime() + ".jar");
        writeFabricMod(input, "1.19.4");
        cachedOutput = Path.of("config/retromod/aot-cache")
                .resolve(input.getFileName().toString().replace(".jar", "-aot.jar"));
        Files.deleteIfExists(cachedOutput);

        ShimRegistry registry = new ShimRegistry();
        registry.register(new MixinExtrasApiShim());

        Path output = new AotCompiler(registry, "1.20.1").compileModAot(input);
        MethodInsnNode call = onlyCall(readClass(output));

        assertEquals("com/retromod/shim/api/common/embedded/MixinExtrasShim", call.owner);
        assertEquals("noopInit", call.name);
    }

    private static VersionShim versionEdge() {
        return new VersionShim() {
            public String getShimName() { return "fixture 1.21.4 to 26.1"; }
            public String getSourceVersion() { return "1.21.4"; }
            public String getTargetVersion() { return "26.1"; }
            public String getModLoaderType() { return "fabric"; }
            public void registerRedirects(RetromodTransformer transformer) {}
        };
    }

    private static void writeFabricMod(Path path, String minecraftVersion) throws Exception {
        String metadata = """
                {
                  "schemaVersion": 1,
                  "id": "known_source_api",
                  "version": "1.0.0",
                  "depends": {
                    "fabricloader": ">=0.16.0",
                    "minecraft": "%s"
                  }
                }
                """.formatted(minecraftVersion);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write(metadata.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(CALLER + ".class"));
            jar.write(caller());
            jar.closeEntry();
        }
    }

    private static byte[] caller() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/llamalad7/mixinextras/MixinExtrasBootstrap", "init", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] readClass(Path jarPath) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(CALLER + ".class");
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static MethodInsnNode onlyCall(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream()
                .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .findFirst().orElseThrow();
    }
}
