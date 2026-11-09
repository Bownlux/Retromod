/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.SyntheticEmbedder;
import com.retromod.loaderapi.CliHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** {@code batch} embeds the stand-in classes a translated mod calls on every target, as {@code transform} does. */
class CliBatchSyntheticEmbeddingTest {

    private static final String BUILDER = "net/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder";
    private static final String SUPPLIER = "net/minecraft/world/entity/ai/attributes/AttributeSupplier";
    private static final String TEMPT_RANGE = "com/retromod/generated/LegacyTemptRange";
    private static final String HURT_HELPER = "com/retromod/generated/Legacy1212IntermediaryMembers";
    private static final String FORGE_REGISTRY = "net/minecraftforge/registries/IForgeRegistry";
    private static final String MOB = "testmod/MobAttributes";

    @ParameterizedTest(name = "{0} with --aot={1}")
    @CsvSource({"forge,false", "forge,true", "neoforge,false", "neoforge,true"})
    @DisplayName("A Forge-family mob mod batched for 1.21.4 carries the tempt range helper it calls")
    void forgeFamilyMobModGetsTemptRangeHelper(String loader, boolean aot, @TempDir Path dir)
            throws Exception {
        // Forge 1.20.1 mods name build() by its SRG id; NeoForge 1.21.1 mods by its Mojang name.
        boolean forge = "forge".equals(loader);
        String jarName = loader + "-mob.jar";
        Path input = Files.createDirectories(dir.resolve("in")).resolve(jarName);
        writeJar(input, forge ? "META-INF/mods.toml" : "META-INF/neoforge.mods.toml",
                forge ? toml("forge", "[1.20.1,1.21)") : toml("neoforge", "[1.21.1,1.21.2)"),
                MOB, buildCaller(forge ? "m_22265_" : "build"));

        Path output = batch(dir, "1.21.4", jarName, aot);

        assertCallsEmbeddedTemptRange(output, jarName);
    }

    @ParameterizedTest(name = "{0} with --aot={1}")
    @CsvSource({"fabric,false", "fabric,true", "quilt,false"})
    @DisplayName("A Fabric or Quilt 1.21.1 mod batched for 1.21.4 carries the hurt helper it calls")
    void fabricModGetsIntermediaryHurtHelper(String loader, boolean aot, @TempDir Path dir)
            throws Exception {
        String jarName = loader + "-hurt.jar";
        Path input = Files.createDirectories(dir.resolve("in")).resolve(jarName);
        boolean quilt = "quilt".equals(loader);
        writeJar(input, quilt ? "quilt.mod.json" : "fabric.mod.json",
                quilt ? quiltJson() : fabricJson(), MOB, hurtCaller());

        Path output = batch(dir, "1.21.4", jarName, aot);

        String relocated = SyntheticEmbedder.embeddedBase(jarName) + HURT_HELPER;
        Map<String, byte[]> entries = entries(output);
        assertTrue(entries.containsKey(relocated + ".class"),
                "the intermediary hurt helper must be embedded under the mod's own package");
        assertTrue(calledOwners(entries.get(MOB + ".class")).contains(relocated),
                "the old hurt call must reach the embedded helper");
        assertNoSharedGeneratedNames(entries);
    }

    @Test
    @DisplayName("A Forge mob mod batched for the newest host still carries its tempt range helper")
    void newestHostStillEmbeds(@TempDir Path dir) throws Exception {
        String jarName = "neoforge-mob.jar";
        Path input = Files.createDirectories(dir.resolve("in")).resolve(jarName);
        writeJar(input, "META-INF/neoforge.mods.toml", toml("neoforge", "[1.21.1,1.21.2)"),
                MOB, buildCaller("build"));

        Path output = batch(dir, "26.3", jarName, false);

        assertCallsEmbeddedTemptRange(output, jarName);
    }

    @Test
    @DisplayName("A Forge mod batched for a Forge host before 26.1 keeps Forge's own registry type")
    void forgeTargetKeepsForgeRegistry(@TempDir Path dir) throws Exception {
        // Forge 1.21.4 still ships IForgeRegistry, so its NeoForge stand-in must not replace it.
        String jarName = "forge-registry.jar";
        Path input = Files.createDirectories(dir.resolve("in")).resolve(jarName);
        writeJar(input, "META-INF/mods.toml", toml("forge", "[1.20.1,1.21)"),
                MOB, registryHolder());

        Path output = batch(dir, "1.21.4", jarName, false);

        Map<String, byte[]> entries = entries(output);
        ClassNode mob = node(entries.get(MOB + ".class"));
        assertEquals("L" + FORGE_REGISTRY + ";", mob.fields.get(0).desc);
        assertTrue(entries.keySet().stream().noneMatch(name -> name.endsWith("/IForgeRegistry.class")),
                "no IForgeRegistry stand-in may be embedded for a Forge target");
    }

    /** Runs the batch over {@code dir/in} and returns the one output jar. */
    private static Path batch(Path dir, String target, String jarName, boolean aot) throws Exception {
        Path out = dir.resolve("out");
        if (aot) {
            CliHarness.batch(dir.resolve("in"), out, target, "--aot");
            return out.resolve(jarName.replace(".jar", "-aot.jar"));
        }
        CliHarness.batch(dir.resolve("in"), out, target);
        return out.resolve(jarName);
    }

    private static void assertCallsEmbeddedTemptRange(Path output, String jarName) throws Exception {
        assertTrue(Files.isRegularFile(output), "the batch must write " + output.getFileName());
        String relocated = SyntheticEmbedder.embeddedBase(jarName) + TEMPT_RANGE;
        Map<String, byte[]> entries = entries(output);
        assertTrue(entries.containsKey(relocated + ".class"),
                "LegacyTemptRange must be embedded under the mod's own package");
        assertTrue(calledOwners(entries.get(MOB + ".class")).contains(relocated),
                "build() must reach the embedded LegacyTemptRange");
        assertNoSharedGeneratedNames(entries);
    }

    /** No class outside the embedded package may name a stand-in nothing defines at runtime. */
    private static void assertNoSharedGeneratedNames(Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")
                    || entry.getKey().startsWith(SyntheticEmbedder.PREFIX)) continue;
            Set<String> names = referencedNames(entry.getValue());
            assertTrue(names.stream().noneMatch(name -> name.startsWith("com/retromod/generated/")),
                    entry.getKey() + " still names " + names);
        }
    }

    private static Set<String> referencedNames(byte[] bytes) {
        Set<String> names = new HashSet<>();
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override
            public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        }), 0);
        return names;
    }

    private static Set<String> calledOwners(byte[] bytes) {
        Set<String> owners = new HashSet<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) owners.add(call.owner);
            }
        }
        return owners;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Map<String, byte[]> entries(Path jar) throws Exception {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), file.getInputStream(entry).readAllBytes());
                }
            }
        }
        return entries;
    }

    /** A class whose static method builds a mob's attributes by {@code buildName}. */
    private static byte[] buildCaller(String buildName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOB, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(L" + BUILDER + ";)L" + SUPPLIER + ";", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, buildName, "()L" + SUPPLIER + ";", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class that damages an entity through the 1.21.1 intermediary {@code hurt}. */
    private static byte[] hurtCaller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOB, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "damage",
                "(Lnet/minecraft/class_1297;Lnet/minecraft/class_1282;)Z", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitLdcInsn(2.0f);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_1297", "method_5643",
                "(Lnet/minecraft/class_1282;F)Z", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class holding a Forge registry in a field. */
    private static byte[] registryHolder() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOB, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ITEMS", "L" + FORGE_REGISTRY + ";",
                null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String toml(String loaderId, String minecraftRange) {
        return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"testmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.testmod]]\nmodId=\"" + loaderId + "\"\nmandatory=true\n"
                + "versionRange=\"[1,)\"\n"
                + "[[dependencies.testmod]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"" + minecraftRange + "\"\n";
    }

    private static String fabricJson() {
        return "{\"schemaVersion\":1,\"id\":\"testmod\",\"version\":\"1.0.0\","
                + "\"depends\":{\"minecraft\":\"~1.21.1\"}}";
    }

    private static String quiltJson() {
        return "{\"schema_version\":1,\"quilt_loader\":{\"group\":\"com.example\",\"id\":\"testmod\","
                + "\"version\":\"1.0.0\",\"depends\":[{\"id\":\"minecraft\",\"versions\":\"~1.21.1\"}]}}";
    }

    private static void writeJar(Path jar, String metadataName, String metadata, String className,
            byte[] classBytes) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(metadataName));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(className + ".class"));
            out.write(classBytes);
            out.closeEntry();
        }
    }
}
