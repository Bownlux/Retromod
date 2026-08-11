/*
 * Retromod - per-mod synthetic-class embedding.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import com.retromod.util.ZipSecurity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Embeds Retromod's registered synthetic classes (ASM-generated polyfills for deleted MC or
 * loader classes) into the transformed mods that reference them, and rewrites those references
 * to the embedded copies.
 *
 * <p>Each copy goes under {@code com/retromod/embedded/<mod-key>/}, unique per mod. Embedding at
 * the original name would split-package with loader-owned modules on Forge/NeoForge. On Fabric,
 * Knot could resolve another mod's older copy of the same generated class instead.
 */
public final class SyntheticEmbedder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    /** Retromod-owned package root for embedded synthetics. */
    public static final String PREFIX = "com/retromod/embedded/";

    private SyntheticEmbedder() {}

    /** Result of embedding synthetics into an in-memory JAR. */
    public record ByteEmbeddingResult(byte[] jarBytes, int embeddedCount, boolean succeeded) {}

    /**
     * Register one of Retromod's compiled helper classes for per-mod embedding.
     *
     * <p>{@link VersionShim#getShimClasses()} is useful for reporting, but the embedder works from
     * the transformer's registered byte arrays. A redirect to a compiled helper therefore has to
     * register that helper here as well or an offline transform leaves a Retromod-only call owner
     * in the output jar.
     *
     * @return true when the class was already registered or its bytes were loaded successfully
     */
    public static boolean registerClassResource(
            RetromodTransformer transformer, String internalName, Class<?> resourceAnchor) {
        if (transformer == null || internalName == null || resourceAnchor == null) return false;
        if (transformer.getSyntheticClasses().containsKey(internalName)) return true;
        try (var in = resourceAnchor.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                LOGGER.warn("Could not find compatibility class resource {}.class", internalName);
                return false;
            }
            transformer.registerSyntheticClass(internalName, in.readAllBytes());
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not register compatibility class {}: {}", internalName, e.toString());
            return false;
        }
    }

    /**
     * Embed every registered synthetic that {@code modDir}'s classes reference and rewrite those
     * references to the embedded copies.
     *
     * @param modDir    extracted mod directory (classes at their package paths)
     * @param uniqueKey per-mod identifier (mod id or jar name) keeping the embedded package distinct
     * @return number of synthetics embedded (0 if none referenced / none registered / error)
     */
    public static int embed(Path modDir, String uniqueKey, RetromodTransformer transformer) {
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        if (synthetics == null || synthetics.isEmpty()) return 0;
        try {
            final List<Path> classFiles;
            try (var s = Files.walk(modDir)) {
                classFiles = s.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.toString().contains("META-INF"))
                        .toList();
            }

            // which registered synthetics does this mod reference?
            Set<String> referenced = new HashSet<>();
            for (Path cf : classFiles) {
                try {
                    referenced.addAll(referencedClasses(Files.readAllBytes(cf)));
                } catch (IOException ignored) {
                }
            }
            referenced.retainAll(synthetics.keySet());
            if (referenced.isEmpty()) return 0;
            expandTransitively(referenced, synthetics);

            String base = embeddedBase(uniqueKey);
            Map<String, String> rename = relocationMap(base, referenced);
            Remapper remapper = new SimpleRemapper(rename);

            Map<Path, byte[]> embeddedOutputs = new HashMap<>();
            for (String n : referenced) {
                byte[] renamed = remap(synthetics.get(n), remapper);
                Path target = ZipSecurity.safeResolve(modDir, rename.get(n) + ".class");
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("synthetic output collides with an existing mod class: "
                            + modDir.relativize(target));
                }
                embeddedOutputs.put(target, renamed);
            }
            Map<Path, byte[]> rewrittenClasses = new HashMap<>();
            for (Path cf : classFiles) {
                byte[] in = Files.readAllBytes(cf);
                byte[] out = remap(in, remapper);
                if (!Arrays.equals(in, out)) rewrittenClasses.put(cf, out);
            }
            for (Map.Entry<Path, byte[]> output : embeddedOutputs.entrySet()) {
                Files.createDirectories(output.getKey().getParent());
                Files.write(output.getKey(), output.getValue());
            }
            for (Map.Entry<Path, byte[]> output : rewrittenClasses.entrySet()) {
                Files.write(output.getKey(), output.getValue());
            }
            LOGGER.info("Embedded {} referenced synthetic class(es) into '{}' under {}",
                    referenced.size(), uniqueKey, base);
            return referenced.size();
        } catch (Exception e) {
            LOGGER.warn("Synthetic embedding skipped for '{}': {}", uniqueKey, e.toString());
            return 0;
        }
    }

    /** Whether extracted classes still point at any unrelocated registered synthetic. */
    static boolean hasRegisteredSyntheticReferences(
            Path modDir, RetromodTransformer transformer) throws IOException {
        Set<String> registered = transformer.getSyntheticClasses().keySet();
        if (registered.isEmpty()) return false;
        try (var files = Files.walk(modDir)) {
            for (Path classFile : files.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.toString().contains("META-INF"))
                    .toList()) {
                Set<String> references = referencedClasses(Files.readAllBytes(classFile));
                for (String internalName : registered) {
                    if (references.contains(internalName)) return true;
                }
            }
        }
        return false;
    }

    /**
     * A synthetic may reference OTHER registered synthetics (e.g. the Forge 26.2 LegacyEventBus
     * bridge constructs ReflectedConsumer, which no mod class names directly). Expand the
     * mod-referenced set to its transitive closure so an embedded copy never dangles on a
     * helper that was renamed away with it - or worse, never embedded at all.
     */
    private static void expandTransitively(Set<String> referenced, Map<String, byte[]> synthetics) {
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>(referenced);
        while (!work.isEmpty()) {
            byte[] bytes = synthetics.get(work.poll());
            if (bytes == null) continue;
            for (String dep : referencedClasses(bytes)) {
                if (synthetics.containsKey(dep) && referenced.add(dep)) work.add(dep);
            }
        }
    }

    /**
     * Jar-based variant for the offline CLI/AOT paths: reads {@code jarPath}, embeds the referenced
     * synthetics, rewrites references, and writes the jar back. Uses {@code Zip*Stream} so
     * {@code META-INF/MANIFEST.MF} and every other entry survive.
     */
    public static int embedIntoJar(Path jarPath, String uniqueKey, RetromodTransformer transformer) {
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        if (synthetics == null || synthetics.isEmpty()) return 0;
        try {
            java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
            long total = 0;
            try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(jarPath))) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (entries.size() >= MAX_ARCHIVE_ENTRIES) {
                        throw new IOException("jar has too many entries during synthetic embed: "
                                + jarPath.getFileName());
                    }
                    String entryName = validateEntryName(e.getName(), e.isDirectory());
                    // Bound per-entry (50MB) and aggregate (500MB) like the other extract paths:
                    // this reads an untrusted jar fully into memory, and the per-entry cap alone
                    // doesn't stop a many-entry decompression bomb. On overflow, bail: the outer
                    // catch returns 0 and the original jar is untouched, since writes go through
                    // the temp-then-move below.
                    byte[] data = e.isDirectory() ? new byte[0]
                            : ZipSecurity.safeReadAllBytes(zis);
                    total += data.length;
                    if (total > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                        throw new java.io.IOException("jar exceeds max total size during synthetic embed: "
                                + jarPath.getFileName());
                    }
                    // directory entries are retained: dropping them breaks package-resource
                    // lookups and classpath scanners in the rewritten jar
                    if (entries.putIfAbsent(entryName, data) != null) {
                        throw new IOException("jar contains duplicate entry during synthetic embed: "
                                + entryName);
                    }
                }
            }
            Set<String> referenced = new HashSet<>();
            for (var en : entries.entrySet()) {
                if (en.getKey().endsWith(".class")) referenced.addAll(referencedClasses(en.getValue()));
            }
            referenced.retainAll(synthetics.keySet());
            if (referenced.isEmpty()) return 0;
            expandTransitively(referenced, synthetics);

            String base = embeddedBase(uniqueKey);
            Map<String, String> rename = relocationMap(base, referenced);
            Remapper remapper = new SimpleRemapper(rename);

            java.util.LinkedHashMap<String, byte[]> out = new java.util.LinkedHashMap<>();
            for (var en : entries.entrySet()) {
                byte[] v = en.getValue();
                if (en.getKey().endsWith(".class")) v = remap(v, remapper);
                out.put(en.getKey(), v);
            }
            for (String n : referenced) {
                if (out.size() >= MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("synthetic output would exceed the jar entry limit");
                }
                String generatedName = validateEntryName(rename.get(n) + ".class", false);
                if (out.containsKey(generatedName)) {
                    throw new IOException("synthetic output collides with an existing jar entry: "
                            + generatedName);
                }
                out.put(generatedName, remap(synthetics.get(n), remapper));
            }

            // write to a sibling temp + move: rewriting in place with a truncating stream
            // destroys the jar if anything throws mid-write (review finding)
            Path targetJar = jarPath.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(targetJar)) {
                throw new IOException("refusing to replace symlinked mod jar: " + targetJar);
            }
            ZipSecurity.validateNotSymlink(targetJar);
            Path tmp = Files.createTempFile(targetJar.getParent(), "retromod-embed-", ".rmtmp");
            try {
                try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
                    for (var en : out.entrySet()) {
                        String entryName = validateEntryName(en.getKey(),
                                en.getKey().endsWith("/"));
                        zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                        zos.write(en.getValue());
                        zos.closeEntry();
                    }
                }
                try (var ignored = new java.util.zip.ZipFile(tmp.toFile())) {
                    // Opening the completed temporary file verifies its central directory before
                    // it replaces the original mod.
                }
                moveReplacingAtomically(tmp, targetJar);
            } finally {
                Files.deleteIfExists(tmp);
            }
            LOGGER.info("Embedded {} referenced synthetic class(es) into jar '{}' under {}",
                    referenced.size(), uniqueKey, base);
            return referenced.size();
        } catch (Exception e) {
            LOGGER.warn("Synthetic embedding (jar) skipped for '{}': {}", uniqueKey, e.toString());
            return 0;
        }
    }

    /**
     * Byte-array variant for nested JAR transforms. A failed result keeps {@code jarData} as its
     * payload so callers can restore a complete archive instead of returning transformed classes
     * with missing helper definitions.
     */
    public static ByteEmbeddingResult embedIntoJarBytes(byte[] jarData, String uniqueKey,
            RetromodTransformer transformer) {
        try {
            return embedIntoJarBytesChecked(jarData, uniqueKey, transformer,
                    ZipSecurity.DEFAULT_MAX_ENTRY_SIZE);
        } catch (Exception e) {
            LOGGER.warn("Synthetic embedding (nested JAR) skipped for '{}': {}",
                    uniqueKey, e.toString());
            return new ByteEmbeddingResult(jarData, 0, false);
        }
    }

    /**
     * Compatibility overload for callers that supply their own compressed-output limit.
     *
     * @return {@code jarData} itself when no registered synthetic is referenced
     */
    public static byte[] embedIntoJarBytes(byte[] jarData, String uniqueKey,
            RetromodTransformer transformer, long maxOutputBytes) throws IOException {
        return embedIntoJarBytesChecked(jarData, uniqueKey, transformer, maxOutputBytes)
                .jarBytes();
    }

    private static ByteEmbeddingResult embedIntoJarBytesChecked(byte[] jarData, String uniqueKey,
            RetromodTransformer transformer, long maxOutputBytes) throws IOException {
        if (jarData == null) throw new IOException("nested JAR bytes are null");
        if (transformer == null) throw new IOException("nested JAR transformer is null");
        if (maxOutputBytes <= 0) throw new IOException("nested JAR output limit must be positive");
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        if (synthetics == null || synthetics.isEmpty()) {
            return new ByteEmbeddingResult(jarData, 0, true);
        }

        java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
        long total = 0;
        try (var input = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(jarData))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entries.size() >= MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("nested JAR has too many entries during synthetic embed");
                }
                String name = validateEntryName(entry.getName(), entry.isDirectory());
                byte[] data = entry.isDirectory() ? new byte[0]
                        : ZipSecurity.safeReadAllBytes(input);
                total += data.length;
                if (total > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                    throw new IOException("nested JAR exceeds the synthetic embed size limit");
                }
                if (entries.putIfAbsent(name, data) != null) {
                    throw new IOException("nested JAR contains duplicate entry during synthetic embed: "
                            + name);
                }
            }
        }

        Set<String> referenced = new HashSet<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().endsWith(".class")) {
                referenced.addAll(referencedClasses(entry.getValue()));
            }
        }
        referenced.retainAll(synthetics.keySet());
        if (referenced.isEmpty()) return new ByteEmbeddingResult(jarData, 0, true);
        expandTransitively(referenced, synthetics);

        String base = embeddedBase(uniqueKey);
        Map<String, String> rename = relocationMap(base, referenced);
        Remapper remapper = new SimpleRemapper(rename);
        java.util.LinkedHashMap<String, byte[]> outputEntries = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            byte[] data = entry.getValue();
            if (entry.getKey().endsWith(".class")) data = remap(data, remapper);
            outputEntries.put(entry.getKey(), data);
        }
        for (String internalName : referenced) {
            if (outputEntries.size() >= MAX_ARCHIVE_ENTRIES) {
                throw new IOException("synthetic output would exceed the nested JAR entry limit");
            }
            String generatedName = validateEntryName(rename.get(internalName) + ".class", false);
            if (outputEntries.containsKey(generatedName)) {
                throw new IOException("synthetic output collides with an existing nested JAR entry: "
                        + generatedName);
            }
            outputEntries.put(generatedName, remap(synthetics.get(internalName), remapper));
        }

        BoundedMemoryOutput encoded = new BoundedMemoryOutput(maxOutputBytes, jarData.length);
        try (var output = new java.util.zip.ZipOutputStream(encoded)) {
            for (Map.Entry<String, byte[]> entry : outputEntries.entrySet()) {
                String name = validateEntryName(entry.getKey(), entry.getKey().endsWith("/"));
                output.putNextEntry(new java.util.zip.ZipEntry(name));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return new ByteEmbeddingResult(encoded.toByteArray(), referenced.size(), true);
    }

    /** Internal class names referenced by a class. */
    static Set<String> referencedClasses(byte[] classBytes) {
        Set<String> refs = new HashSet<>();
        try {
            new ClassReader(classBytes).accept(new ClassRemapper(
                    new ClassWriter(0),
                    new Remapper() {
                        @Override public String map(String internalName) {
                            refs.add(internalName);
                            return internalName;
                        }
                    }), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (Exception ignored) {
        }
        return refs;
    }

    private static byte[] remap(byte[] classBytes, Remapper remapper) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0); // pure rename, no frame/maxs recomputation
        cr.accept(new ClassRemapper(cw, remapper), 0);
        return cw.toByteArray();
    }

    private static Map<String, String> relocationMap(String base, Set<String> referenced)
            throws IOException {
        Map<String, String> rename = new HashMap<>();
        for (String internalName : referenced) {
            ZipSecurity.safeEntryName(internalName + ".class");
            rename.put(internalName, base + internalName);
        }
        return rename;
    }

    private static String validateEntryName(String entryName, boolean directory)
            throws IOException {
        String name = ZipSecurity.safeEntryName(entryName);
        if (name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("jar contains an unsafe entry name: " + entryName);
        }
        String withoutDirectorySlash = directory && name.endsWith("/")
                ? name.substring(0, name.length() - 1)
                : name;
        for (String part : withoutDirectorySlash.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) {
                throw new IOException("jar contains an unsafe entry name: " + entryName);
            }
        }
        return name;
    }

    private static void moveReplacingAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Stable per-mod package root used by embedded compatibility classes. */
    public static String embeddedBase(String uniqueKey) {
        return PREFIX + sanitize(uniqueKey) + "_" + shortDigest(uniqueKey) + "/";
    }

    /** A jar name / mod id reduced to the readable part of a package-legal segment. */
    private static String sanitize(String key) {
        if (key == null) return "mod";
        String s = key.replaceAll("\\.jar$", "").replaceAll("[^A-Za-z0-9_]", "_");
        if (s.length() > 96) s = s.substring(0, 96);
        return s.isEmpty() ? "mod" : s;
    }

    private static String shortDigest(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((rawKey == null ? "" : rawKey).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(12);
            for (int i = 0; i < 6; i++) value.append(String.format("%02x", digest[i]));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Bounds the compressed nested archive while it is rebuilt in memory. */
    private static final class BoundedMemoryOutput extends OutputStream {
        private static final int MAX_INITIAL_CAPACITY = 1024 * 1024;

        private final java.io.ByteArrayOutputStream output;
        private final long maxBytes;
        private long written;

        private BoundedMemoryOutput(long maxBytes, int expectedBytes) {
            if (maxBytes <= 0) throw new IllegalArgumentException("output limit must be positive");
            this.maxBytes = maxBytes;
            int initialCapacity = Math.max(32,
                    Math.min(Math.max(expectedBytes, 0), MAX_INITIAL_CAPACITY));
            this.output = new java.io.ByteArrayOutputStream(initialCapacity);
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length);
            output.write(bytes, offset, length);
        }

        private void reserve(long bytes) throws IOException {
            if (bytes > maxBytes - written) {
                throw new IOException("rewritten nested JAR exceeds " + maxBytes + " bytes");
            }
            written += bytes;
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
