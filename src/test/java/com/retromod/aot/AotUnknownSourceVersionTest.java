/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AotUnknownSourceVersionTest {

    private static final String ENTRY = "fixture/Entry";
    private static final String OLD_TARGET = "fixture/OldTarget";
    private static final String NEW_TARGET = "fixture/NewTarget";

    private Path cachedOutput;

    @AfterEach
    void resetTransformerAndCache() throws Exception {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
        if (cachedOutput != null) Files.deleteIfExists(cachedOutput);
    }

    @Test
    void explicitAotTransformsMissingSourceVersionWithOnlyApplicableLoaderShims(
            @TempDir Path directory) throws Exception {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Path input = writeFabricModWithoutMinecraftDependency(directory);
        cachedOutput = Path.of("config/retromod/aot-cache")
                .resolve(input.getFileName().toString().replace(".jar", "-aot.jar"));
        Files.deleteIfExists(cachedOutput);

        ShimRegistry registry = new ShimRegistry();
        registry.register(classMoveShim("fabric", "26.1", NEW_TARGET));
        registry.register(classMoveShim("forge", "26.1", "fixture/WrongLoaderTarget"));
        registry.register(classMoveShim("fabric", "26.2", "fixture/FutureTarget"));
        AotCompiler compiler = new AotCompiler(registry, "26.1.2");

        assertEquals(input, compiler.compileModAot(input),
                "an automatic AOT scan must leave an unknown-source mod alone");

        Path output = compiler.compileModAot(input, true);

        assertNotEquals(input, output,
                "an explicitly requested AOT transform must produce a prepared jar");
        assertEquals(NEW_TARGET, constructedType(output),
                "the fallback must apply same-loader shims through the host only");
        try (ZipFile jar = new ZipFile(output.toFile())) {
            Manifest manifest = new Manifest(jar.getInputStream(
                    jar.getEntry("META-INF/MANIFEST.MF")));
            assertEquals("unknown", manifest.getMainAttributes()
                    .getValue("Retromod-Source-Version"));
        }
    }

    private static VersionShim classMoveShim(
            String loader, String targetVersion, String destination) {
        return new VersionShim() {
            @Override
            public String getShimName() {
                return loader + " fixture through " + targetVersion;
            }

            @Override
            public String getSourceVersion() {
                return "1.20.1";
            }

            @Override
            public String getTargetVersion() {
                return targetVersion;
            }

            @Override
            public String getModLoaderType() {
                return loader;
            }

            @Override
            public void registerRedirects(RetromodTransformer transformer) {
                transformer.registerClassRedirect(OLD_TARGET, destination);
            }
        };
    }

    private static Path writeFabricModWithoutMinecraftDependency(Path directory) throws Exception {
        Path jarPath = directory.resolve(
                "unknown-source-aot-" + System.nanoTime() + ".jar");
        String metadata = """
                {
                  "schemaVersion": 1,
                  "id": "unknown_source_aot",
                  "version": "1.0.0",
                  "depends": {"fabricloader": ">=0.14.0"}
                }
                """;
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write(metadata.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(ENTRY + ".class"));
            jar.write(entryClass());
            jar.closeEntry();
        }
        return jarPath;
    }

    private static byte[] entryClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ENTRY, null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "makeTarget", "()L" + OLD_TARGET + ";", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, OLD_TARGET);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                OLD_TARGET, "<init>", "()V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String constructedType(Path jarPath) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile());
             InputStream input = jar.getInputStream(jar.getEntry(ENTRY + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(input.readAllBytes()).accept(node, 0);
            return node.methods.stream()
                    .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                    .filter(TypeInsnNode.class::isInstance)
                    .map(TypeInsnNode.class::cast)
                    .filter(instruction -> instruction.getOpcode() == Opcodes.NEW)
                    .findFirst().orElseThrow().desc;
        }
    }
}
