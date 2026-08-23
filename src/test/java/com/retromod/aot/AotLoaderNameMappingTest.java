/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.fabric.embedded.LegacyBlockRandomTickBridge;
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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AotLoaderNameMappingTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void resetTransformer() {
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
    }

    @Test
    void standaloneAotEmitsForge1_20_1TargetSrg(@TempDir Path directory) throws Exception {
        String inputName = "aot-loader-mapping-public-input.jar";
        Path cachedOutput = Path.of("config/retromod/aot-cache",
                "aot-loader-mapping-public-input-aot.jar");
        Files.deleteIfExists(cachedOutput);

        try {
            ShimRegistry registry = new ShimRegistry();
            registry.register(new ForgeNoopShim());
            AotCompiler compiler = new AotCompiler(registry, "1.20.1");
            Path input = directory.resolve(inputName);
            writeForgeMod(input, oldForgeCall());

            Path output = compiler.compileModAot(input);

            assertEquals(cachedOutput.toAbsolutePath().normalize(),
                    output.toAbsolutePath().normalize(),
                    "the public AOT path must emit its cached transform, not return the input jar");
            assertEquals("m_8055_", transformedCallName(output));
        } finally {
            Files.deleteIfExists(cachedOutput);
        }
    }

    @Test
    void batchAotReconfiguresMappingsBetweenFabricAndForge(@TempDir Path directory)
            throws Exception {
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.1");
        ModVersionInfo fabric = info("fabric");
        ModVersionInfo forge = info("forge");

        compiler.configureLoaderMappingsFor(fabric);
        Path fabricOutput = compile(compiler, fabric, directory.resolve("fabric-input.jar"),
                directory.resolve("fabric-output.jar"), intermediaryNamedCall());
        assertEquals("tick", transformedCallName(fabricOutput));

        compiler.configureLoaderMappingsFor(forge);
        Path forgeOutput = compile(compiler, forge, directory.resolve("next-forge-input.jar"),
                directory.resolve("next-forge-output.jar"), intermediaryNamedCall());
        assertEquals("method_5773", transformedCallName(forgeOutput),
                "the next Forge AOT jar must not inherit Fabric intermediary mappings");
    }

    @Test
    void offlineAotDropsFabricOnlyStateBeforeTheNextForgeInput(@TempDir Path directory)
            throws Exception {
        ShimRegistry registry = new ShimRegistry();
        registry.register(new Fabric_1_21_11_to_26_1());
        AotCompiler compiler = AotCompiler.forOfflineInputs(
                registry, "26.1", directory.toRealPath().resolve("cache"));
        Path fabricInput = directory.resolve("fabric.jar");
        Path forgeInput = directory.resolve("forge.jar");
        writeFabricMod(fabricInput, intermediaryNamedCall());
        writeModernForgeMod(forgeInput, intermediaryNamedCall());
        RetromodTransformer.FieldKey randomTick = new RetromodTransformer.FieldKey(
                "net/minecraft/world/level/block/Block", "randomTicks");

        compiler.compileModAot(fabricInput);
        assertTrue(transformer.getFieldRedirects().containsKey(randomTick));
        assertTrue(transformer.getSyntheticClasses().containsKey(
                LegacyBlockRandomTickBridge.INTERNAL_NAME));

        compiler.compileModAot(forgeInput);
        assertFalse(transformer.getFieldRedirects().containsKey(randomTick));
        assertFalse(transformer.getSyntheticClasses().containsKey(
                LegacyBlockRandomTickBridge.INTERNAL_NAME));
    }

    private static ModVersionInfo info(String loader) {
        return new ModVersionInfo("testmod", "1.0", "1.16", loader, null,
                Set.of("test/"), Set.of(), false);
    }

    private static Path compile(AotCompiler compiler, ModVersionInfo info,
            Path input, Path output, byte[] classBytes) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry("test/Fixture.class"));
            jar.write(classBytes);
            jar.closeEntry();
        }
        Method compileJar = AotCompiler.class.getDeclaredMethod(
                "compileJar", Path.class, Path.class, ModVersionInfo.class);
        compileJar.setAccessible(true);
        compileJar.invoke(compiler, input, output, info);
        return output;
    }

    private static void writeForgeMod(Path input, byte[] classBytes) throws Exception {
        String metadata = """
                modLoader="javafml"
                loaderVersion="[36,)"
                license="MIT"
                [[mods]]
                modId="aot_mapping_test"
                version="1.0.0"
                displayName="AOT Mapping Test"
                [[dependencies.aot_mapping_test]]
                modId="forge"
                mandatory=true
                versionRange="[36,)"
                ordering="NONE"
                side="BOTH"
                [[dependencies.aot_mapping_test]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.16.5]"
                ordering="NONE"
                side="BOTH"
                """;
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry("test/Fixture.class"));
            jar.write(classBytes);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/mods.toml"));
            jar.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static void writeFabricMod(Path input, byte[] classBytes) throws Exception {
        String metadata = """
                {
                  "schemaVersion": 1,
                  "id": "aot_fabric_scope_test",
                  "version": "1.0.0",
                  "depends": {
                    "fabricloader": ">=0.16.0",
                    "minecraft": "1.21.11"
                  }
                }
                """;
        writeMod(input, "fabric.mod.json", metadata, classBytes);
    }

    private static void writeModernForgeMod(Path input, byte[] classBytes) throws Exception {
        String metadata = """
                modLoader="javafml"
                loaderVersion="[1,)"
                license="MIT"
                [[mods]]
                modId="aot_forge_scope_test"
                version="1.0.0"
                [[dependencies.aot_forge_scope_test]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.21.11]"
                ordering="NONE"
                side="BOTH"
                """;
        writeMod(input, "META-INF/mods.toml", metadata, classBytes);
    }

    private static void writeMod(Path input, String metadataPath, String metadata,
                                 byte[] classBytes) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry("test/Fixture.class"));
            jar.write(classBytes);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(metadataPath));
            jar.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static String transformedCallName(Path jarPath) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            try (InputStream input = jar.getInputStream(jar.getEntry("test/Fixture.class"))) {
                ClassNode node = new ClassNode();
                new ClassReader(input.readAllBytes()).accept(node, 0);
                return node.methods.stream()
                        .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                        .filter(MethodInsnNode.class::isInstance)
                        .map(MethodInsnNode.class::cast)
                        .findFirst().orElseThrow().name;
            }
        }
    }

    private static byte[] oldForgeCall() {
        ClassWriter writer = classWriter();
        MethodVisitor method = method(writer);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "net/minecraft/world/level/BlockGetter", "func_180495_p",
                "(Lnet/minecraft/core/BlockPos;)"
                        + "Lnet/minecraft/world/level/block/state/BlockState;", true);
        method.visitInsn(Opcodes.POP);
        finish(method, writer);
        return writer.toByteArray();
    }

    private static byte[] intermediaryNamedCall() {
        ClassWriter writer = classWriter();
        MethodVisitor method = method(writer);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "test/Helper", "method_5773", "()V", false);
        finish(method, writer);
        return writer.toByteArray();
    }

    private static ClassWriter classWriter() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Fixture", null,
                "java/lang/Object", null);
        return writer;
    }

    private static MethodVisitor method(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", "(Lnet/minecraft/world/level/BlockGetter;"
                        + "Lnet/minecraft/core/BlockPos;)V", null, null);
        method.visitCode();
        return method;
    }

    private static void finish(MethodVisitor method, ClassWriter writer) {
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
    }

    private static final class ForgeNoopShim implements VersionShim {
        @Override
        public String getShimName() {
            return "Forge AOT mapping test";
        }

        @Override
        public String getSourceVersion() {
            return "1.16.5";
        }

        @Override
        public String getTargetVersion() {
            return "1.20";
        }

        @Override
        public String getModLoaderType() {
            return "forge";
        }

        @Override
        public void registerRedirects(RetromodTransformer transformer) {
            // The regression covers per-loader namespace setup before the shim chain is applied.
        }
    }
}
