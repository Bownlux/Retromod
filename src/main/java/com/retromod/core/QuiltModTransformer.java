/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import java.util.regex.*;

/**
 * Transforms Quilt mods for newer Minecraft versions. Quilt shares Fabric's bytecode and Mixin
 * system, so this reuses FabricModTransformer and only handles quilt.mod.json separately.
 */
public class QuiltModTransformer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Quilt");
    
    private final String targetMcVersion;
    private final FabricModTransformer fabricTransformer;
    
    public QuiltModTransformer(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
        this.fabricTransformer = new FabricModTransformer(targetMcVersion);
    }
    
    public static boolean isQuiltMod(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getEntry("quilt.mod.json") != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    /** Runs the Fabric bytecode transform, then patches quilt.mod.json. */
    public Path transformMod(Path sourceJar, Path outputDir) throws IOException {
        String originalName = sourceJar.getFileName().toString();

        LOGGER.info("Transforming Quilt mod: {}", originalName);

        String modMcVersion = extractMinecraftVersion(sourceJar);
        if (isNativeVersion(modMcVersion)) {
            LOGGER.info("  {} is already for {} - passing through", originalName, targetMcVersion);
            Path directCopy = outputDir.resolve(originalName);
            Files.copy(sourceJar, directCopy, StandardCopyOption.REPLACE_EXISTING);
            return directCopy;
        }

        Path transformed = fabricTransformer.transformMod(sourceJar, outputDir);

        if (transformed != null && Files.exists(transformed)) {
            updateQuiltModJson(transformed);
        }

        return transformed;
    }
    
    private void updateQuiltModJson(Path jarPath) throws IOException {
        Path tempDir = Files.createTempDirectory("retromod-quilt-");

        try {
                // Bounded extraction: a mod JAR is user content, and an entry can lie about its
                // declared size, so count actual decompressed bytes to catch a zip bomb.
                long quiltTotalSize = 0;
                try (JarFile jar = new JarFile(jarPath.toFile())) {
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        Path outPath = ZipSecurity.safeResolve(tempDir, entry.getName());
                        if (entry.isDirectory()) {
                            Files.createDirectories(outPath);
                        } else {
                            Files.createDirectories(outPath.getParent());
                            long writtenBytes;
                            try (InputStream is = jar.getInputStream(entry)) {
                                writtenBytes = ZipSecurity.copyBounded(
                                    is, outPath, ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, entry.getName());
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
                if (Files.exists(quiltJson)) {
                    String content = Files.readString(quiltJson);
                    content = updateVersionInQuiltJson(content);
                    Files.writeString(quiltJson, content);
                }

                repackJar(tempDir, jarPath);
        } finally {
            deleteDirectory(tempDir);
        }
    }
    
    private String updateVersionInQuiltJson(String content) {
        // Matches "minecraft": "1.20.1" or "minecraft": ">=1.20.1".
        Pattern p = Pattern.compile("(\"minecraft\"\\s*:\\s*\")([^\"]+)(\")");
        Matcher m = p.matcher(content);
        
        if (m.find()) {
            String oldVersion = m.group(2);
            String newVersion = ">=" + targetMcVersion;
            content = m.replaceFirst("$1" + newVersion + "$3");
            LOGGER.debug("Updated quilt.mod.json: {} → {}", oldVersion, newVersion);
        }
        
        return content;
    }
    
    private String extractMinecraftVersion(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getEntry("quilt.mod.json");
            if (entry != null) {
                String content;
                try (InputStream is = jar.getInputStream(entry)) {
                    content = new String(ZipSecurity.safeReadAllBytes(is),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
                Pattern p = Pattern.compile("\"minecraft\"\\s*:\\s*\"([^\"]+)\"");
                Matcher m = p.matcher(content);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /** True when the mod already targets the host version, so no transform is needed. */
    private boolean isNativeVersion(String version) {
        if (version == null) return false;
        String clean = version.replace(">=", "").replace("~", "").replace("^", "").trim();
        return clean.equals(targetMcVersion) || version.contains(targetMcVersion);
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
                    for (Path path : stream.filter(p -> !Files.isDirectory(p)).toList()) {
                        String entryName = sourceDir.relativize(path).toString().replace("\\", "/");
                        jos.putNextEntry(new JarEntry(ZipSecurity.safeEntryName(entryName)));
                        Files.copy(path, jos);
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

    private void deleteDirectory(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> -a.compareTo(b))
                  .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        } catch (Exception e) {
            // ignore
        }
    }
}
