/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodVersion;
import com.retromod.util.ZipSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs the pre-1.20 data migrations that work on a whole data tree, on every transform path.
 *
 * <p>{@link LegacyStructureDataMigrator} renames and rewrites structure files, and
 * {@link LegacySmithingRecipeMigrator} rewrites smithing recipes. The runtime Forge path works on
 * an extracted tree and calls {@link #migrateTree}. The CLI and AOT paths stream entries into a new
 * jar, so they finish the jar first and call {@link #migrateArchive}, which extracts only
 * {@code data/}, migrates it, and rewrites the jar when something changed. A nested jar is a mod
 * of its own, so its source version comes from its own metadata.
 */
public final class LegacyDataMigrations {

    private LegacyDataMigrations() {}

    /** Both migrations over an extracted mod; returns the number of files written. */
    public static int migrateTree(Path extractedJar, String sourceMcVersion, String targetMcVersion)
            throws IOException {
        return LegacyStructureDataMigrator.migrate(extractedJar, sourceMcVersion, targetMcVersion)
                + LegacySmithingRecipeMigrator.migrate(extractedJar, sourceMcVersion, targetMcVersion);
    }

    /**
     * Both migrations over a finished jar, rewritten in place when a file changed.
     *
     * @param sourceMcVersion the version the jar was built for, or null to read it from the jar
     * @return the number of data files written
     */
    public static int migrateArchive(Path jar, String sourceMcVersion, String targetMcVersion)
            throws IOException {
        if (targetMcVersion == null || RetromodVersion.compareMcVersions(targetMcVersion, "1.19") < 0) {
            return 0;
        }
        String source = sourceMcVersion != null ? sourceMcVersion : declaredVersion(jar);
        // A cheap superset of the two migrators' own gates, so newer mods are never extracted.
        // An unreadable version is not guessed to be old.
        if (source == null || !source.matches("\\d+\\.\\d+.*")
                || RetromodVersion.compareMcVersions(source, "1.20") >= 0) {
            return 0;
        }

        Path tree = Files.createTempDirectory("retromod-data-");
        try {
            if (!extractData(jar, tree)) return 0;
            int written = migrateTree(tree, source, targetMcVersion);
            if (written > 0) replaceData(jar, tree);
            return written;
        } finally {
            deleteTree(tree);
        }
    }

    /** {@link #migrateArchive} for a nested jar held in memory; returns the same bytes when unchanged. */
    public static byte[] migrateArchive(byte[] jar, String targetMcVersion) throws IOException {
        Path file = Files.createTempFile("retromod-nested-", ".jar");
        try {
            Files.write(file, jar);
            return migrateArchive(file, null, targetMcVersion) > 0 ? Files.readAllBytes(file) : jar;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** The Minecraft version a jar's own metadata declares, or null when it cannot be read. */
    public static String declaredVersion(Path jar) {
        try {
            var info = new ModVersionDetector().detectVersion(jar);
            return info == null ? null : info.targetMcVersion();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static boolean extractData(Path jar, Path tree) throws IOException {
        boolean any = false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("data/")) continue;
                Path target = ZipSecurity.safeResolve(tree, ZipSecurity.safeEntryName(entry.getName()));
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    ZipSecurity.copyBounded(in, target, entry.getName());
                }
                any = true;
            }
        }
        return any;
    }

    /** Copies every non-data entry, then the migrated {@code data/} tree, and swaps the jar. */
    private static void replaceData(Path jar, Path tree) throws IOException {
        Path parent = jar.toAbsolutePath().getParent();
        Path staged = Files.createTempFile(parent, "." + jar.getFileName() + ".", ".tmp");
        try {
            try (ZipFile zip = new ZipFile(jar.toFile());
                 JarOutputStream out = new JarOutputStream(Files.newOutputStream(staged))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("data/")) continue;
                    out.putNextEntry(new JarEntry(entry.getName()));
                    if (!entry.isDirectory()) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            out.write(ZipSecurity.safeReadAllBytes(in));
                        }
                    }
                    out.closeEntry();
                }
                List<Path> files;
                try (Stream<Path> walk = Files.walk(tree)) {
                    files = walk.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path file : files) {
                    out.putNextEntry(new JarEntry(tree.relativize(file).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(file));
                    out.closeEntry();
                }
            }
            Files.move(staged, jar, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void deleteTree(Path tree) throws IOException {
        if (!Files.exists(tree)) return;
        try (Stream<Path> walk = Files.walk(tree)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
