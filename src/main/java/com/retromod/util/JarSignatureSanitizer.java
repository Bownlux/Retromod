/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Removes signing metadata after a JAR entry has been rewritten. */
public final class JarSignatureSanitizer {

    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final long MAX_MANIFEST_BYTES = 2L * 1024 * 1024;

    private JarSignatureSanitizer() {}

    /** True for verifier artifacts directly below {@code META-INF/}. */
    public static boolean isSigningArtifact(String rawName) throws IOException {
        String name = ZipSecurity.canonicalEntryName(rawName);
        int slash = name.lastIndexOf('/');
        if (slash < 0 || !name.substring(0, slash).equalsIgnoreCase("META-INF")) {
            return false;
        }
        String leaf = name.substring(slash + 1).toUpperCase(Locale.ROOT);
        return leaf.startsWith("SIG-")
                || leaf.endsWith(".SF")
                || leaf.endsWith(".RSA")
                || leaf.endsWith(".DSA")
                || leaf.endsWith(".EC")
                || leaf.endsWith(".SIG")
                || leaf.endsWith(".P7S");
    }

    /** True for the standard manifest entry, with case and separator normalization. */
    public static boolean isManifest(String rawName) throws IOException {
        return ZipSecurity.canonicalEntryName(rawName)
                .equalsIgnoreCase(JarFile.MANIFEST_NAME);
    }

    /** Returns a copy without signature headers or per-entry digest attributes. */
    public static Manifest sanitizeManifest(Manifest source) {
        Manifest sanitized = new Manifest(source);
        stripSigningAttributes(sanitized.getMainAttributes());

        var sections = sanitized.getEntries();
        for (String sectionName : new ArrayList<>(sections.keySet())) {
            Attributes attributes = sections.get(sectionName);
            stripSigningAttributes(attributes);
            if (attributes.isEmpty()) {
                sections.remove(sectionName);
            }
        }

        if (sanitized.getMainAttributes().getValue(Attributes.Name.MANIFEST_VERSION) == null) {
            sanitized.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }
        return sanitized;
    }

    /** Rewrites bounded manifest bytes while preserving non-signing attributes. */
    public static byte[] sanitizeManifest(byte[] bytes) throws IOException {
        if (bytes == null) throw new IOException("JAR manifest bytes are missing");
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("JAR manifest exceeds " + MAX_MANIFEST_BYTES + " bytes");
        }
        Manifest manifest = new Manifest(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream output = new ByteArrayOutputStream(bytes.length);
        sanitizeManifest(manifest).write(output);
        return output.toByteArray();
    }

    /**
     * Rewrites a generated JAR in place. Call this only after another operation changed entry
     * bytes. A copy-only path must leave the original archive untouched.
     */
    public static void sanitizeJar(Path jarPath) throws IOException {
        sanitizeJar(jarPath, ZipSecurity.DEFAULT_MAX_ENTRY_SIZE,
                ZipSecurity.DEFAULT_MAX_TOTAL_SIZE, DEFAULT_MAX_ENTRIES);
    }

    /** Testable bounded variant for an already generated JAR. */
    public static void sanitizeJar(Path jarPath, long maxEntryBytes,
            long maxExpandedBytes, int maxEntries) throws IOException {
        validateLimits(maxEntryBytes, maxExpandedBytes, maxEntries);
        Path target = ZipSecurity.requireRegularFileNoFollow(jarPath, "generated JAR");
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Generated JAR has no parent: " + target);
        Path staged = Files.createTempFile(parent,
                "." + target.getFileName() + ".unsigned-", ".tmp");
        try {
            // The generated input can already contain changed bytes beside stale signatures.
            // Read it without verification, then verify every entry in the sanitized result.
            try (JarFile input = new JarFile(target.toFile(), false);
                 JarOutputStream output = new JarOutputStream(Files.newOutputStream(staged))) {
                Set<String> names = new HashSet<>();
                long expandedInput = 0;
                long expandedOutput = 0;
                int entries = 0;
                var enumeration = input.entries();
                while (enumeration.hasMoreElements()) {
                    JarEntry entry = enumeration.nextElement();
                    String name = ZipSecurity.safeEntryName(entry.getName());
                    String canonicalName = ZipSecurity.canonicalEntryName(name);
                    if (!names.add(canonicalName)) {
                        throw new IOException("Generated JAR contains a duplicate normalized entry: "
                                + name);
                    }
                    if (++entries > maxEntries) {
                        throw new IOException("Generated JAR contains more than " + maxEntries
                                + " entries");
                    }
                    if (isSigningArtifact(canonicalName)) continue;

                    String outputName = entry.isDirectory()
                            ? canonicalName + "/" : canonicalName;
                    JarEntry written = new JarEntry(outputName);
                    output.putNextEntry(written);
                    if (!entry.isDirectory()) {
                        byte[] data;
                        try (InputStream stream = input.getInputStream(entry)) {
                            long remaining = maxExpandedBytes - expandedInput;
                            if (remaining <= 0) {
                                throw new IOException("Generated JAR exceeds "
                                        + maxExpandedBytes + " expanded bytes at " + name);
                            }
                            data = ZipSecurity.safeReadAllBytes(
                                    stream, Math.min(maxEntryBytes, remaining));
                        }
                        expandedInput = reserve(
                                expandedInput, data.length, maxExpandedBytes, name);
                        if (isManifest(canonicalName)) {
                            data = sanitizeManifest(data);
                        }
                        if (data.length > maxEntryBytes) {
                            throw new IOException("Sanitized JAR entry exceeds "
                                    + maxEntryBytes + " bytes: " + name);
                        }
                        expandedOutput = reserve(
                                expandedOutput, data.length, maxExpandedBytes, name);
                        output.write(data);
                    }
                    output.closeEntry();
                }
            }
            verifyReadable(staged);
            moveReplacing(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** Sanitizes a changed nested JAR while enforcing its compressed output limit. */
    public static byte[] sanitizeJarBytes(byte[] jarBytes, long maxOutputBytes)
            throws IOException {
        if (jarBytes == null) throw new IOException("Nested JAR bytes are missing");
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("nested JAR output limit must be positive");
        }
        BoundedByteArrayOutputStream encoded = new BoundedByteArrayOutputStream(maxOutputBytes);
        Set<String> names = new HashSet<>();
        long expandedInput = 0;
        long expandedOutput = 0;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jarBytes));
             JarOutputStream output = new JarOutputStream(encoded)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = ZipSecurity.safeEntryName(entry.getName());
                String canonicalName = ZipSecurity.canonicalEntryName(name);
                if (!names.add(canonicalName)) {
                    throw new IOException("Nested JAR contains a duplicate normalized entry: "
                            + name);
                }
                if (++entries > DEFAULT_MAX_ENTRIES) {
                    throw new IOException("Nested JAR contains more than "
                            + DEFAULT_MAX_ENTRIES + " entries");
                }
                if (isSigningArtifact(canonicalName)) continue;

                String outputName = entry.isDirectory()
                        ? canonicalName + "/" : canonicalName;
                output.putNextEntry(new JarEntry(outputName));
                if (!entry.isDirectory()) {
                    long remaining = ZipSecurity.DEFAULT_MAX_TOTAL_SIZE - expandedInput;
                    if (remaining <= 0) {
                        throw new IOException("Nested JAR exceeds the expanded-byte limit at "
                                + name);
                    }
                    byte[] data = ZipSecurity.safeReadAllBytes(input,
                            Math.min(ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, remaining));
                    expandedInput = reserve(expandedInput, data.length,
                            ZipSecurity.DEFAULT_MAX_TOTAL_SIZE, name);
                    if (isManifest(canonicalName)) {
                        data = sanitizeManifest(data);
                    }
                    if (data.length > ZipSecurity.DEFAULT_MAX_ENTRY_SIZE) {
                        throw new IOException("Sanitized nested JAR entry exceeds "
                                + ZipSecurity.DEFAULT_MAX_ENTRY_SIZE + " bytes: " + name);
                    }
                    expandedOutput = reserve(expandedOutput, data.length,
                            ZipSecurity.DEFAULT_MAX_TOTAL_SIZE, name);
                    output.write(data);
                }
                output.closeEntry();
            }
        } catch (OutputLimitExceeded exceeded) {
            throw new IOException(exceeded.getMessage(), exceeded);
        }
        return encoded.toByteArray();
    }

    /** Removes signature entries and sanitizes a manifest in an in-memory archive map. */
    public static void sanitizeEntries(Map<String, byte[]> entries) throws IOException {
        for (String name : new ArrayList<>(entries.keySet())) {
            if (isSigningArtifact(name)) {
                entries.remove(name);
            } else if (isManifest(name)) {
                entries.put(name, sanitizeManifest(entries.get(name)));
            }
        }
    }

    private static void stripSigningAttributes(Attributes attributes) {
        for (Object key : new ArrayList<>(attributes.keySet())) {
            String name = key.toString().toUpperCase(Locale.ROOT);
            if (name.equals("SIGNATURE-VERSION")
                    || name.equals("MAGIC")
                    || name.equals("DIGEST-ALGORITHMS")
                    || name.endsWith("-DIGEST")
                    || name.contains("-DIGEST-")) {
                attributes.remove(key);
            }
        }
    }

    private static long reserve(long used, long bytes, long maximum, String entryName)
            throws IOException {
        if (bytes < 0 || bytes > maximum - used) {
            throw new IOException("JAR exceeds " + maximum
                    + " expanded bytes at " + entryName);
        }
        return used + bytes;
    }

    private static void validateLimits(long maxEntryBytes, long maxExpandedBytes,
            int maxEntries) {
        if (maxEntryBytes <= 0 || maxExpandedBytes <= 0 || maxEntries <= 0) {
            throw new IllegalArgumentException("JAR sanitization limits must be positive");
        }
    }

    private static void verifyReadable(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final long maximum;

        private BoundedByteArrayOutputStream(long maximum) {
            super((int) Math.min(maximum, 8192));
            this.maximum = maximum;
        }

        @Override
        public synchronized void write(int value) {
            if ((long) count + 1 > maximum) {
                throw new OutputLimitExceeded(maximum);
            }
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (length < 0 || (long) count + length > maximum) {
                throw new OutputLimitExceeded(maximum);
            }
            super.write(bytes, offset, length);
        }
    }

    private static final class OutputLimitExceeded extends RuntimeException {
        private OutputLimitExceeded(long maximum) {
            super("Rewritten nested JAR exceeds " + maximum + " bytes");
        }
    }
}
