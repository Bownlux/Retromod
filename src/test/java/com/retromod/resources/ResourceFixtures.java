/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Small mod jars and trees built in code, so no test ships another mod's or Minecraft's files. */
final class ResourceFixtures {

    private ResourceFixtures() {
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Entries in the order given, which a jar keeps. */
    static Map<String, byte[]> entries(String... namesAndContents) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            entries.put(namesAndContents[i], bytes(namesAndContents[i + 1]));
        }
        return entries;
    }

    static byte[] jar(Map<String, byte[]> entries) throws IOException {
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

    static Path writeJar(Path file, Map<String, byte[]> entries) throws IOException {
        Files.write(file, jar(entries));
        return file;
    }

    /** Every file of a jar on disk, by name. */
    static Map<String, byte[]> readJar(Path file) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (JarFile jar = new JarFile(file.toFile())) {
            var all = jar.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                if (entry.isDirectory()) continue;
                try (var input = jar.getInputStream(entry)) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return entries;
    }

    static Map<String, byte[]> readJar(byte[] jar, Path scratch) throws IOException {
        return readJar(Files.write(Files.createTempFile(scratch, "jar-", ".jar"), jar));
    }

    /** The files of a tree on disk, by forward-slash name. */
    static Map<String, byte[]> readDirectory(Path root) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        DirectoryResourceTree tree = DirectoryResourceTree.open(root);
        for (String name : tree.names()) entries.put(name, tree.read(name));
        return entries;
    }

    static void writeDirectory(Path root, Map<String, byte[]> entries) throws IOException {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
    }

    /** Name to SHA-256 of each entry under {@code assets/} and {@code data/}, for comparing paths. */
    static Map<String, String> gameResourceDigests(Map<String, byte[]> entries) {
        Map<String, String> digests = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("assets/") || name.startsWith("data/")) {
                digests.put(name, sha256(entry.getValue()));
            }
        }
        return digests;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A 1.12 Forge mod class: {@code @Mod(modid = ..., acceptedMinecraftVersions = ...)}. */
    static byte[] legacyModClass(String internalName, String modId, String acceptedVersions) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationVisitor mod = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        mod.visit("modid", modId);
        if (acceptedVersions != null) mod.visit("acceptedMinecraftVersions", acceptedVersions);
        mod.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class whose only method reads {@code owner.field}, so its constant pool names the owner. */
    static byte[] classReading(String internalName, String owner, String field, String descriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read",
                "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, field, descriptor);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    static String fabricMetadata(String modId, String minecraft) {
        return "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"" + minecraft + "\"}}";
    }

    static String forgeToml(String modId, String minecraftRange) {
        return "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"" + modId + "\"\nversion=\"1.0\"\n"
                + "[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"" + minecraftRange + "\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
    }
}
