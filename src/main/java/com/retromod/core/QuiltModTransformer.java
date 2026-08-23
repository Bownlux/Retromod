/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.ArchivePublication;
import com.retromod.util.ZipSecurity;
import com.retromod.util.JarSignatureSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.*;

/**
 * Transforms Quilt mods for newer Minecraft versions. Quilt shares Fabric's bytecode and Mixin
 * system, so this reuses FabricModTransformer and only handles quilt.mod.json separately.
 */
public class QuiltModTransformer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Quilt");
    private static final int MAX_ENTRY_COUNT = 100_000;
    
    private final String targetMcVersion;
    private final FabricModTransformer fabricTransformer;
    
    public QuiltModTransformer(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
        this.fabricTransformer = new FabricModTransformer(targetMcVersion);
    }
    
    public static boolean isQuiltMod(Path jarPath) {
        if (jarPath == null || !Files.isRegularFile(jarPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getEntry("quilt.mod.json") != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    /** Runs the Fabric bytecode transform, then patches quilt.mod.json. */
    public Path transformMod(Path sourceJar, Path outputDir) throws IOException {
        validateArchiveInput(sourceJar);
        String originalName = sourceJar.getFileName().toString();

        LOGGER.info("Transforming Quilt mod: {}", originalName);

        if (com.retromod.util.OptOutCheck.isOptedOut(sourceJar)) {
            com.retromod.util.OptOutCheck.logSkipped(sourceJar);
            Path passthrough = outputDir.resolve(originalName);
            copyReplacingAtomically(sourceJar, passthrough);
            return passthrough;
        }

        String modMcVersion = extractMinecraftVersion(sourceJar);
        if (fabricTransformer.isNativeVersionMod(modMcVersion)) {
            LOGGER.info("  {} is already for {} - passing through", originalName, targetMcVersion);
            Path directCopy = outputDir.resolve(originalName);
            copyReplacingAtomically(sourceJar, directCopy);
            return directCopy;
        }

        Path stagingDirectory = createOutputStagingDirectory(outputDir);
        try {
            Path transformed = fabricTransformer.transformModWithAuthoritativeMinecraftVersion(
                sourceJar, stagingDirectory, modMcVersion);
            if (transformed == null || !Files.isRegularFile(
                    transformed, LinkOption.NOFOLLOW_LINKS)) {
                return transformed;
            }

            updateQuiltModJson(transformed);
            Path published = outputDir.toAbsolutePath().resolve(transformed.getFileName());
            moveReplacingAtomically(transformed, published);
            ModHealthChecker.relocateTransformedPath(transformed, published);
            return published;
        } finally {
            ModHealthChecker.forgetTransformedPathsUnder(stagingDirectory);
            deleteDirectory(stagingDirectory);
        }
    }
    
    protected void updateQuiltModJson(Path jarPath) throws IOException {
        Path tempDir = Files.createTempDirectory("retromod-quilt-");

        try {
            // A mod entry can misreport its size, so count bytes actually decompressed.
            long quiltTotalSize = 0;
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                validateArchiveEntries(jar);
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = canonicalEntryName(entry.getName());
                    Path outPath = ZipSecurity.safeResolve(tempDir, entryName);
                    if (entry.isDirectory()) {
                        Files.createDirectories(outPath);
                    } else {
                        Files.createDirectories(outPath.getParent());
                        long writtenBytes;
                        try (InputStream is = jar.getInputStream(entry)) {
                            long entryLimit = entryName.equals("quilt.mod.json")
                                ? QuiltMetadataCompat.MAX_METADATA_BYTES
                                : ZipSecurity.DEFAULT_MAX_ENTRY_SIZE;
                            writtenBytes = ZipSecurity.copyBounded(
                                is, outPath, entryLimit, entryName);
                        }
                        quiltTotalSize += writtenBytes;
                        if (quiltTotalSize > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                            throw new IOException("Quilt mod total extracted size exceeds limit ("
                                + ZipSecurity.DEFAULT_MAX_TOTAL_SIZE + " bytes) - possible zip bomb "
                                + "(decompressed " + quiltTotalSize + " bytes so far)");
                        }
                    }
                }
            }

            Path quiltJson = tempDir.resolve("quilt.mod.json");
            if (Files.isRegularFile(quiltJson, LinkOption.NOFOLLOW_LINKS)) {
                String content;
                try (InputStream input = Files.newInputStream(quiltJson)) {
                    content = new String(ZipSecurity.safeReadAllBytes(
                        input, QuiltMetadataCompat.MAX_METADATA_BYTES),
                        java.nio.charset.StandardCharsets.UTF_8);
                }
                content = updateVersionInQuiltJson(content);
                Files.writeString(quiltJson, content,
                    java.nio.charset.StandardCharsets.UTF_8);
            }

            repackJar(tempDir, jarPath);
        } finally {
            deleteDirectory(tempDir);
        }
    }
    
    String updateVersionInQuiltJson(String content) throws IOException {
        String updated = QuiltMetadataCompat.updateMinecraftVersion(content, targetMcVersion);
        if (!updated.equals(content)) {
            LOGGER.debug("Updated quilt.mod.json for Minecraft {}", targetMcVersion);
        }
        return updated;
    }
    
    private String extractMinecraftVersion(Path jarPath) throws IOException {
        validateArchiveInput(jarPath);
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            validateArchiveEntries(jar);
            var entry = jar.getEntry("quilt.mod.json");
            if (entry != null) {
                try (InputStream is = jar.getInputStream(entry)) {
                    return QuiltMetadataCompat.readMinecraftVersion(is);
                }
            }
        }
        return null;
    }

    private static void validateArchiveEntries(JarFile jar) throws IOException {
        if (jar.size() > MAX_ENTRY_COUNT) {
            throw new IOException("Quilt mod contains more than "
                + MAX_ENTRY_COUNT + " entries");
        }
        Set<String> entryNames = new HashSet<>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            String canonical = canonicalEntryName(entries.nextElement().getName());
            if (!entryNames.add(canonical)) {
                throw new IOException("Quilt mod contains a duplicate normalized entry: "
                    + canonical);
            }
        }
    }

    private static String canonicalEntryName(String entryName) throws IOException {
        ZipSecurity.safeEntryName(entryName);
        String normalizedSlashes = entryName.replace('\\', '/');
        StringBuilder canonical = new StringBuilder();
        for (String part : normalizedSlashes.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (canonical.length() > 0) canonical.append('/');
            canonical.append(part);
        }
        if (canonical.length() == 0) {
            throw new IOException("Quilt mod contains an empty normalized entry name");
        }
        return canonical.toString();
    }

    private static void validateArchiveInput(Path jarPath) throws IOException {
        if (jarPath == null || !Files.isRegularFile(jarPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Quilt mod input is not a regular file: " + jarPath);
        }
    }

    private void repackJar(Path sourceDir, Path targetJar) throws IOException {
        Path parent = targetJar.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("Quilt jar has no parent: " + targetJar);
        Path staged = Files.createTempFile(parent,
                "." + targetJar.getFileName() + ".", ".tmp");
        try {
            try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(staged))) {

                // ZIP directory entries keep package resource and classpath scans working.
                com.retromod.util.JarDirectoryEntries.writeAll(jos, sourceDir);

                try (var stream = Files.walk(sourceDir)) {
                    for (Path path : stream
                            .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .toList()) {
                        String entryName = sourceDir.relativize(path).toString().replace("\\", "/");
                        if (JarSignatureSanitizer.isSigningArtifact(entryName)) {
                            continue;
                        }
                        jos.putNextEntry(new JarEntry(ZipSecurity.safeEntryName(entryName)));
                        if (JarSignatureSanitizer.isManifest(entryName)) {
                            byte[] manifestBytes;
                            try (InputStream input = Files.newInputStream(path)) {
                                manifestBytes = ZipSecurity.safeReadAllBytes(
                                        input, QuiltMetadataCompat.MAX_METADATA_BYTES);
                            }
                            jos.write(JarSignatureSanitizer.sanitizeManifest(manifestBytes));
                        } else {
                            Files.copy(path, jos);
                        }
                        jos.closeEntry();
                    }
                }
            }
            try (JarFile ignored = new JarFile(staged.toFile())) {
                // Validate the completed central directory before replacement.
            }
            try {
                Files.move(staged, targetJar, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, targetJar, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void copyReplacingAtomically(Path source, Path target) throws IOException {
        ArchivePublication.copyReplacing(source, target);
    }

    private static Path createOutputStagingDirectory(Path outputDir) throws IOException {
        Path normalizedOutput = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOutput);
        Path parent = normalizedOutput.getParent();
        if (parent == null) {
            throw new IOException("Quilt mod output directory has no parent: " + outputDir);
        }
        return Files.createTempDirectory(parent, ".retromod-quilt-output-");
    }

    private static void moveReplacingAtomically(Path source, Path target) throws IOException {
        ArchivePublication.moveReplacing(source, target);
    }

    private void deleteDirectory(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> -a.compareTo(b))
                  .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        } catch (Exception e) {
            // ignore
        }
    }
}
