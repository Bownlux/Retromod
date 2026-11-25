/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A Fabric jar that names no Minecraft version is judged by its classes, as Essential's container needs. */
class UnversionedJarEvidenceTest {

    // Essential's container declares only a loader floor and a nested loader jar.
    private static final String NO_MINECRAFT = """
            {"schemaVersion": 1, "id": "%s", "version": "1.0.0", "environment": "client",
             "depends": {"fabricloader": ">=0.18.4"}}""";

    @TempDir
    Path dir;

    @Test
    void aJava25JarWithMojangNamesAndNestedJarsIsCurrentOn26x() throws IOException {
        Path jar = essentialLike(dir.resolve("Container_fabric_26-3.jar"));

        assertTrue(UnversionedJarEvidence.isBuiltForHost(jar, "26.3"), "the file name names the host");
        assertFalse(UnversionedJarEvidence.isBuiltForHost(jar, "1.21.11"),
                "an intermediary host cannot tell current from old by names, so it keeps translating");
    }

    @Test
    void aClassOrDescriptorPastItsReadLimitEndsTheScanWithoutLoadingIt() throws IOException {
        // 40 MiB of class bytes, past the 32 MiB class limit, packed into a small file.
        Path bigClass = dir.resolve("big-class.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bigClass))) {
            out.putNextEntry(new ZipEntry("fabric.mod.json"));
            out.write(String.format(NO_MINECRAFT, "big").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("a/A.class"));
            byte[] block = new byte[1 << 20];
            for (int i = 0; i < 40; i++) out.write(block);
            out.closeEntry();
        }
        assertFalse(UnversionedJarEvidence.isBuiltForHost(bigClass, "26.1"), "it gets the normal translation");

        // A descriptor of 3 MiB, past the 2 MiB a loader descriptor may take.
        Path bigDescriptor = dir.resolve("big-descriptor.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bigDescriptor))) {
            out.putNextEntry(new ZipEntry("fabric.mod.json"));
            out.write(String.format(NO_MINECRAFT, "big").getBytes(StandardCharsets.UTF_8));
            out.write(" ".repeat(3 << 20).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertFalse(UnversionedJarEvidence.isBuiltForHost(bigDescriptor, "26.1"));
    }

    @Test
    void pastTheFirstUnobfuscatedVersionTheJarMustAlsoPointAtTheHost() throws IOException {
        // Mojang names are the same on 26.1, 26.2 and 26.3, so they cannot date the mod on their own.
        Path unnamed = essentialLike(dir.resolve("container.jar"));
        assertTrue(UnversionedJarEvidence.isBuiltForHost(unnamed, "26.1"), "on 26.1 the names are enough");
        assertTrue(UnversionedJarEvidence.isBuiltForHost(unnamed, "26.1.2"));
        assertFalse(UnversionedJarEvidence.isBuiltForHost(unnamed, "26.3"),
                "a 26.1 or 26.2 mod without a hint still gets the shims up to 26.3");
        assertFalse(UnversionedJarEvidence.isBuiltForHost(essentialLike(dir.resolve("container-26.2.jar")), "26.3"),
                "a file name naming another version is no hint");

        // A nested mod's own minecraft constraint can name the host instead of the file name.
        byte[] inner = jarBytes(Map.of("fabric.mod.json",
                "{\"schemaVersion\": 1, \"id\": \"inner\", \"depends\": {\"minecraft\": \"~26.3\"}}",
                "gg/mod/Main.class", mojangClass("gg/mod/Main", Opcodes.V25)));
        Path nestedHint = jar(dir.resolve("hinted.jar"), Map.of("fabric.mod.json", json("hinted"), "mod.jar", inner));
        assertTrue(UnversionedJarEvidence.isBuiltForHost(nestedHint, "26.3"));
    }

    @Test
    void onlyAFabricOrQuiltJarIsJudged() throws IOException {
        Path forge = jar(dir.resolve("forge_26-3.jar"), Map.of("META-INF/mods.toml", "modLoader=\"javafml\"",
                "mod/Main.class", mojangClass("mod/Main", Opcodes.V25)));

        assertFalse(UnversionedJarEvidence.isBuiltForHost(forge, "26.3"),
                "a Forge jar staged on Fabric keeps the normal path, not a copy");
    }

    @Test
    void aMinecraftRangeWithoutANumberDatesNothing() {
        assertTrue(UnversionedJarEvidence.datesNothing(java.util.List.of()));
        assertTrue(UnversionedJarEvidence.datesNothing(java.util.List.of("*")));
        assertFalse(UnversionedJarEvidence.datesNothing(java.util.List.of(">=1.20")));
    }

    @Test
    void anIntermediaryNameAnywhereMakesTheJarOld() throws IOException {
        assertFalse(UnversionedJarEvidence.isBuiltForHost(oldMod(dir.resolve("old-26.3.jar")), "26.3"));

        // A container whose only Minecraft-naming class, two jars deep, is intermediary.
        byte[] deep = jarBytes(Map.of("mod/Deep.class",
                classNaming("mod/Deep", Opcodes.V17, "Lnet/minecraft/class_1792;")));
        byte[] inner = jarBytes(Map.of("mod/Inner.class", mojangClass("mod/Inner", Opcodes.V25), "deep.jar", deep));
        Path nested = jar(dir.resolve("nested-26.3.jar"), Map.of("fabric.mod.json", json("nested"), "inner.jar", inner));
        assertFalse(UnversionedJarEvidence.isBuiltForHost(nested, "26.3"));
    }

    @Test
    void aFewIntermediaryStringsInALargeMojangNamedJarStayCurrent() throws IOException {
        // Essential's compat patches name other mods' intermediary targets in 3 of its 649 Minecraft-naming classes.
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", json("big"));
        for (int i = 0; i < 40; i++) entries.put("mod/C" + i + ".class", mojangClass("mod/C" + i, Opcodes.V25));
        entries.put("mod/Compat.class", classNaming("mod/Compat", Opcodes.V25, "Lnet/minecraft/class_304;"));

        assertTrue(UnversionedJarEvidence.isBuiltForHost(jar(dir.resolve("big-26.3.jar"), entries), "26.3"));
    }

    @Test
    void aJarWithNoMinecraftNamesNeedsAJava25ClassFile() throws IOException {
        Path older = jar(dir.resolve("lib17-26.3.jar"), Map.of("fabric.mod.json", json("lib17"),
                "lib/A.class", classNaming("lib/A", Opcodes.V17, "Ljava/lang/String;")));
        Path current = jar(dir.resolve("lib25-26.3.jar"), Map.of("fabric.mod.json", json("lib25"),
                "lib/A.class", classNaming("lib/A", Opcodes.V25, "Ljava/lang/String;")));
        // A Java 25 copy under META-INF/versions/ does not make a Java 17 library current.
        Path multiRelease = jar(dir.resolve("lib17mr-26.3.jar"), Map.of("fabric.mod.json", json("lib17mr"),
                "lib/A.class", classNaming("lib/A", Opcodes.V17, "Ljava/lang/String;"),
                "META-INF/versions/25/lib/A.class", classNaming("lib/A", Opcodes.V25, "Ljava/lang/String;")));
        assertFalse(UnversionedJarEvidence.isBuiltForHost(multiRelease, "26.3"));

        assertFalse(UnversionedJarEvidence.isBuiltForHost(older, "26.3"), "no evidence either way keeps translating");
        assertTrue(UnversionedJarEvidence.isBuiltForHost(current, "26.3"));
    }

    @Test
    void theCliBatchCopiesTheCurrentJarAndStillTranslatesTheOldOne() throws Exception {
        Path in = Files.createDirectories(dir.resolve("in"));
        Path out = dir.resolve("out");
        Path current = essentialLike(in.resolve("container_fabric_26-3.jar"));
        Path old = oldMod(in.resolve("old-26.3.jar"));
        byte[] oldBytes = Files.readAllBytes(old);

        CliHarness.batch(in, out, "26.3");

        assertArrayEquals(Files.readAllBytes(current), Files.readAllBytes(out.resolve("container_fabric_26-3.jar")),
                "the current container and its nested jars stay byte for byte");
        try (ZipFile translated = new ZipFile(out.resolve("old-26.3.jar").toFile())) {
            String constants = new String(translated.getInputStream(translated.getEntry("old/OldItems.class"))
                    .readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("net/minecraft/class_1792"), "the old jar's intermediary name is remapped");
            assertTrue(constants.contains("net/minecraft/world/item/Item"));
        }
        assertFalse(java.util.Arrays.equals(oldBytes, Files.readAllBytes(out.resolve("old-26.3.jar"))));
    }

    @Test
    void theCliTransformLeavesTheCurrentJarAlone() throws Exception {
        Path current = essentialLike(dir.resolve("container_fabric_26-3.jar"));
        Path out = dir.resolve("container-out.jar");

        CliHarness.transform(current, out, "26.3");

        assertFalse(Files.exists(out), "nothing to write for a jar already built for the target");
    }

    /** A container like Essential's: no Minecraft version, a nested loader, and a nested Mojang-named mod. */
    private static Path essentialLike(Path path) throws IOException {
        byte[] mod = jarBytes(Map.of("fabric.mod.json", json("inner").getBytes(StandardCharsets.UTF_8),
                "gg/mod/Main.class", mojangClass("gg/mod/Main", Opcodes.V25)));
        byte[] loader = jarBytes(Map.of("fabric.mod.json", json("loader").getBytes(StandardCharsets.UTF_8),
                "gg/loader/Stage0.class", classNaming("gg/loader/Stage0", Opcodes.V1_8, "Ljava/lang/String;"),
                "mod.jar", mod));
        return jar(path, Map.of("fabric.mod.json", json("container"), "loader.jar", loader));
    }

    /** A 1.20-era mod with no Minecraft constraint whose class names Item by intermediary. */
    private static Path oldMod(Path path) throws IOException {
        return jar(path, Map.of("fabric.mod.json", json("old"),
                "old/OldItems.class", classNaming("old/OldItems", Opcodes.V17, "Lnet/minecraft/class_1792;")));
    }

    private static String json(String id) {
        return NO_MINECRAFT.formatted(id);
    }

    private static byte[] mojangClass(String name, int version) {
        return classNaming(name, version, "Lnet/minecraft/world/item/Item;");
    }

    /** A class with one static field of type {@code fieldDesc}. */
    private static byte[] classNaming(String name, int version, String fieldDesc) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(version, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ITEM", fieldDesc, null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path jar(Path path, Map<String, ?> entries) throws IOException {
        Files.write(path, jarBytes(entries));
        return path;
    }

    private static byte[] jarBytes(Map<String, ?> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, ?> entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                Object value = entry.getValue();
                zip.write(value instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : (byte[]) value);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
