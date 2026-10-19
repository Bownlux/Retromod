/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Per-entry SHA-256 digests of a transformed jar, without the stamps that change on every run. */
public final class LoaderApiGolden {

    // The compile time and the self-hash change on every run, so two builds compare by content.
    private static final Set<String> VOLATILE_ATTRIBUTES = Set.of(
            "Retromod-Compiled-Time", "Retromod-Self-Hash");
    private static final List<String> VOLATILE_PROPERTIES = List.of(
            "compiled_time=", "retromod_self_hash=");

    private LoaderApiGolden() {}

    /** Writes {@code label\tentry\tsha256} lines for each {@code label=jar} argument. */
    public static void main(String[] args) throws IOException {
        // java -cp ... com.retromod.loaderapi.LoaderApiGolden <out.tsv> <label>=<jar>..., with jars
        // made by an older CLI build.
        List<String> lines = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String[] labelAndJar = args[i].split("=", 2);
            for (Map.Entry<String, String> entry : digests(Files.readAllBytes(Path.of(labelAndJar[1])))
                    .entrySet()) {
                lines.add(labelAndJar[0] + "\t" + entry.getKey() + "\t" + entry.getValue());
            }
        }
        Files.write(Path.of(args[0]), lines, StandardCharsets.UTF_8);
    }

    /** Entry name to the digest of its normalized content, sorted by name. */
    public static Map<String, String> digests(byte[] jar) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] content = in.readAllBytes();
                digests.put(entry.getName(), sha256(normalize(entry.getName(), content)));
            }
        }
        return digests;
    }

    /** Reads a golden file into label, then entry name to digest. */
    public static Map<String, Map<String, String>> read(InputStream golden) throws IOException {
        Map<String, Map<String, String>> labels = new TreeMap<>();
        for (String line : new String(golden.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            String[] cells = line.split("\t");
            labels.computeIfAbsent(cells[0], ignored -> new TreeMap<>()).put(cells[1], cells[2]);
        }
        return labels;
    }

    /** The CLI jar relocates these libraries; Retromod classes it embeds name them shaded. */
    private static final Map<String, String> SHADED = Map.of(
            "com/retromod/shaded/slf4j/", "org/slf4j/",
            "com/retromod/shaded/gson/", "com/google/gson/",
            "com/retromod/shaded/toml4j/", "com/moandjiezana/toml/");

    static byte[] normalize(String name, byte[] content) throws IOException {
        // A nested jar is rewritten with fresh timestamps, so compare its entries instead.
        if (name.endsWith(".jar")) {
            StringBuilder nested = new StringBuilder();
            digests(content).forEach((entry, digest) ->
                    nested.append(entry).append('=').append(digest).append('\n'));
            return nested.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (name.endsWith(".class")) {
            return unshade(content);
        }
        if (name.equals("META-INF/MANIFEST.MF")) {
            Manifest manifest = new Manifest(new ByteArrayInputStream(content));
            Attributes main = manifest.getMainAttributes();
            for (String volatileName : VOLATILE_ATTRIBUTES) main.remove(new Attributes.Name(volatileName));
            StringBuilder text = new StringBuilder();
            new TreeMap<>(main.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    entry -> entry.getKey().toString(), entry -> entry.getValue().toString())))
                    .forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (name.equals("retromod_aot.properties")) {
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
                if (VOLATILE_PROPERTIES.stream().anyMatch(line::startsWith)) continue;
                kept.writeBytes((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return kept.toByteArray();
        }
        return content;
    }

    private static byte[] unshade(byte[] classBytes) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(classBytes).accept(new org.objectweb.asm.commons.ClassRemapper(
                writer, new org.objectweb.asm.commons.Remapper() {
                    @Override
                    public String map(String internalName) {
                        for (Map.Entry<String, String> shaded : SHADED.entrySet()) {
                            if (internalName.startsWith(shaded.getKey())) {
                                return shaded.getValue() + internalName.substring(shaded.getKey().length());
                            }
                        }
                        return internalName;
                    }
                }), 0);
        return writer.toByteArray();
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
