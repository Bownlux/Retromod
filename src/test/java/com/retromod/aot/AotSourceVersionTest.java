/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * The offline AOT path (the CLI {@code aot} command and {@code batch --aot}) clears every
 * registration before each input. The mod's source version has to be set after that, or the
 * transform runs as if the version were unknown: a Forge 1.19.2 mod's drifted SRG ids take the
 * newer name, and a redirect kept for older mods rewrites a newer mod too.
 */
class AotSourceVersionTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void resetTransformer() {
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("a Forge 1.19.2 mod's m_7366_ keeps the name it had in 1.19.2")
    void srgIdsUseTheModsOwnVersion(@TempDir Path directory) throws Exception {
        AotCompiler compiler = AotCompiler.forOfflineInputs(
                new ShimRegistry(), "26.1", directory.toRealPath().resolve("cache"));
        Path input = directory.resolve("armor-mod.jar");
        writeForgeMod(input, "[1.19.2]", call("test/Owner", "m_7366_",
                "(Lnet/minecraft/world/entity/EquipmentSlot;)I"));

        assertEquals("getDurabilityForSlot", firstCallName(compiler.compileModAot(input)),
                "m_7366_ was getDurabilityForSlot until 1.19.2 and getDefenseForType from 1.19.4");
    }

    @Test
    @DisplayName("a redirect kept for mods before 1.21.2 leaves a 1.21.4 mod alone")
    void redirectForOlderModsSkipsANewerMod(@TempDir Path directory) throws Exception {
        ShimRegistry registry = new ShimRegistry();
        registry.register(new OlderModsOnlyShim());
        AotCompiler compiler = AotCompiler.forOfflineInputs(
                registry, "26.1", directory.toRealPath().resolve("cache"));
        Path input = directory.resolve("newer-mod.jar");
        writeForgeMod(input, "[1.21.4]", call("test/Api", "old", "()V"));

        assertEquals("old", firstCallName(compiler.compileModAot(input)),
                "the mod was built for the newer behavior, so its call stays as written");
    }

    /** Redirects {@code test/Api.old()} for mods built before 1.21.2 only. */
    private static final class OlderModsOnlyShim implements VersionShim {
        @Override
        public String getShimName() {
            return "Older mods only";
        }

        @Override
        public String getSourceVersion() {
            return "1.21.4";
        }

        @Override
        public String getTargetVersion() {
            return "26.1";
        }

        @Override
        public String getModLoaderType() {
            return "forge";
        }

        @Override
        public void registerRedirects(RetromodTransformer transformer) {
            transformer.registerMethodRedirect("test/Api", "old", "()V", "test/Api", "replacement", "()V");
            transformer.limitRedirectToModsBefore("test/Api", "old", "()V", "1.21.2");
        }
    }

    // --- the mod under test ---------------------------------------------------------------------

    private static void writeForgeMod(Path input, String minecraftRange, byte[] fixture) throws Exception {
        String metadata = """
                modLoader="javafml"
                loaderVersion="[1,)"
                license="MIT"
                [[mods]]
                modId="aot_source_version_test"
                version="1.0.0"
                [[dependencies.aot_source_version_test]]
                modId="minecraft"
                mandatory=true
                versionRange="%s"
                ordering="NONE"
                side="BOTH"
                """.formatted(minecraftRange);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry("test/Fixture.class"));
            jar.write(fixture);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/mods.toml"));
            jar.write(metadata.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    /** {@code test/Fixture.probe()}, making one static call and discarding its result. */
    private static byte[] call(String owner, String name, String desc) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Fixture", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe",
                "()V", null, null);
        method.visitCode();
        for (org.objectweb.asm.Type argument : org.objectweb.asm.Type.getArgumentTypes(desc)) {
            method.visitInsn(argument.getSort() == org.objectweb.asm.Type.OBJECT
                    ? Opcodes.ACONST_NULL : Opcodes.ICONST_0);
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false);
        if (!desc.endsWith(")V")) method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String firstCallName(Path jarPath) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile());
             InputStream input = jar.getInputStream(jar.getEntry("test/Fixture.class"))) {
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
