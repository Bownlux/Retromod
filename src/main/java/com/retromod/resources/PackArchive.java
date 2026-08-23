/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.util.JsonSecurity;
import com.retromod.util.ZipSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Shared bounded archive and directory operations for resource and data packs. */
final class PackArchive {

    static final long MAX_METADATA_BYTES = 1024L * 1024;
    static final int MAX_ARCHIVE_ENTRIES = 100_000;

    private PackArchive() {}

    static String transformedOutputName(String name) {
        String base = name.toLowerCase(Locale.ROOT).endsWith(".zip")
            ? name.substring(0, name.length() - 4)
            : name;
        return base + "-retromod.zip";
    }

    static String readMetadata(Path packPath) throws IOException {
        validatePath(packPath, "pack");
        if (Files.isDirectory(packPath, LinkOption.NOFOLLOW_LINKS)) {
            Path metadata = packPath.resolve("pack.mcmeta");
            return Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)
                ? readRegularFile(metadata, MAX_METADATA_BYTES, "pack.mcmeta")
                : null;
        }

        Set<String> names = new HashSet<>();
        String metadata = null;
        int entryCount = 0;
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                validateEntryCount(++entryCount);
                ZipEntry entry = entries.nextElement();
                String name = normalizedEntryName(entry.getName());
                if (!names.add(portableCollisionKey(name))) {
                    throw new IOException("Pack archive contains entries that resolve to the "
                        + "same portable path: " + name);
                }
                if (name.equals("pack.mcmeta") && !entry.isDirectory()) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        metadata = JsonSecurity.readUtf8(input, MAX_METADATA_BYTES,
                            JsonSecurity.DEFAULT_MAX_DEPTH, "pack.mcmeta");
                    }
                }
            }
        }
        return metadata;
    }

    static String readRegularFile(Path path, long maxBytes, String name) throws IOException {
        validatePath(path, name);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(name + " is not a regular file: " + path);
        }
        try (InputStream input = Files.newInputStream(path)) {
            return JsonSecurity.readUtf8(input, maxBytes,
                JsonSecurity.DEFAULT_MAX_DEPTH, name);
        }
    }

    static boolean containsArchiveEntryPrefix(Path archive, String prefix) throws IOException {
        validatePath(archive, "pack archive");
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pack archive is not a regular file: " + archive);
        }
        String normalizedPrefix = normalizedEntryName(prefix);
        boolean found = false;
        int entryCount = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                validateEntryCount(++entryCount);
                ZipEntry entry = entries.nextElement();
                String name = normalizedEntryName(entry.getName());
                if (!names.add(portableCollisionKey(name))) {
                    throw new IOException("Pack archive contains entries that resolve to the "
                        + "same portable path: " + name);
                }
                if ((entry.isDirectory() && name.equals(normalizedPrefix))
                        || name.startsWith(normalizedPrefix + "/")) {
                    found = true;
                }
            }
        }
        return found;
    }

    static void extractZip(Path zipPath, Path outputDirectory, String packType) throws IOException {
        validatePath(zipPath, packType + " archive");
        long totalSize = 0;
        int entryCount = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                validateEntryCount(++entryCount);
                ZipEntry entry = entries.nextElement();
                String name = normalizedEntryName(entry.getName());
                if (!names.add(portableCollisionKey(name))) {
                    throw new IOException("Pack archive contains entries that resolve to the "
                        + "same portable path: " + name);
                }
                Path outputPath = ZipSecurity.safeResolve(outputDirectory, name);
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    continue;
                }
                Files.createDirectories(outputPath.getParent());
                long written;
                try (InputStream input = zip.getInputStream(entry)) {
                    written = ZipSecurity.copyBounded(input, outputPath,
                        ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, name);
                }
                totalSize += written;
                if (totalSize > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                    throw new IOException(packType + " extracted size exceeds "
                        + ZipSecurity.DEFAULT_MAX_TOTAL_SIZE + " bytes");
                }
            }
        }
    }

    static void copyDirectoryContents(Path source, Path destination) throws IOException {
        validatePath(source, "pack directory");
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pack source is not a directory: " + source);
        }
        Files.createDirectories(destination);

        long totalSize = 0;
        List<Path> paths = collectBoundedPaths(source, MAX_ARCHIVE_ENTRIES);
        Set<String> names = new HashSet<>();
        for (Path path : paths) {
                validatePath(path, "pack entry");
                String entryName = normalizedEntryName(
                    source.relativize(path).toString().replace('\\', '/'));
                if (!names.add(portableCollisionKey(entryName))) {
                    throw new IOException("Pack directory contains entries that resolve to the "
                        + "same portable path: " + entryName);
                }
                Path target = destination.resolve(source.relativize(path).toString()).normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new IOException("Pack entry escapes its destination: " + path);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Pack entry is not a regular file: " + path);
                }
                Files.createDirectories(target.getParent());
                long written;
                try (InputStream input = Files.newInputStream(path);
                     OutputStream output = Files.newOutputStream(target)) {
                    written = copyBounded(input, output, ZipSecurity.DEFAULT_MAX_ENTRY_SIZE,
                        path.toString());
                }
                totalSize += written;
                if (totalSize > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                    throw new IOException("Pack directory size exceeds "
                        + ZipSecurity.DEFAULT_MAX_TOTAL_SIZE + " bytes");
                }
        }
    }

    static void copyPathAtomically(Path source, Path destination) throws IOException {
        validatePath(source, "pack");
        Path parent = requiredParent(destination);
        Files.createDirectories(parent);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Path staged = Files.createTempDirectory(parent, ".retromod-pack-");
            try {
                copyDirectoryContents(source, staged);
                replaceDirectory(staged, destination);
            } finally {
                deleteRecursivelyQuietly(staged);
            }
            return;
        }

        Path staged = Files.createTempFile(parent, ".retromod-pack-", ".tmp");
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Pack source is not a regular file: " + source);
            }
            try (InputStream input = Files.newInputStream(source);
                 OutputStream output = Files.newOutputStream(staged)) {
                copyBounded(input, output, ZipSecurity.DEFAULT_MAX_TOTAL_SIZE,
                    source.toString());
            }
            moveAtomically(staged, destination);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    static void packZip(Path sourceDirectory, Path zipPath) throws IOException {
        validatePath(sourceDirectory, "pack directory");
        Path parent = requiredParent(zipPath);
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".retromod-pack-", ".zip");
        try {
            writeZip(sourceDirectory, staged);
            moveAtomically(staged, zipPath);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** Moves one pack workflow path without following a symbolic-link leaf. */
    static void movePathAtomically(Path source, Path destination) throws IOException {
        validatePath(source, "pack transaction source");
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pack transaction source is missing: " + source);
        }
        Path parent = requiredParent(destination);
        Files.createDirectories(parent);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            validatePath(destination, "pack transaction destination");
        }
        moveAtomically(source, destination);
    }

    static void validateEntryCount(int entryCount) throws IOException {
        if (entryCount > MAX_ARCHIVE_ENTRIES) {
            throw new IOException("Pack contains more than " + MAX_ARCHIVE_ENTRIES + " entries");
        }
    }

    /** Rejects a managed path that escapes its root or crosses an existing symbolic link. */
    static void validateContainedPath(Path root, Path candidate, String description)
            throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IOException(description + " escapes the game directory: " + candidate);
        }

        Path current = normalizedRoot;
        rejectSymbolicLink(current, description);
        for (Path segment : normalizedRoot.relativize(normalizedCandidate)) {
            current = current.resolve(segment);
            rejectSymbolicLink(current, description);
        }
    }

    static void deleteRecursivelyQuietly(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                        throws IOException {
                    if (failure != null) throw failure;
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Temporary cleanup must not replace the original transform failure.
        }
    }

    private static void writeZip(Path sourceDirectory, Path zipPath) throws IOException {
        long totalSize = 0;
        List<Path> paths = collectBoundedPaths(sourceDirectory, MAX_ARCHIVE_ENTRIES);
        Set<String> names = new HashSet<>();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Path path : paths) {
                validatePath(path, "pack entry");
                String entryName = normalizedEntryName(
                    sourceDirectory.relativize(path).toString().replace('\\', '/'));
                if (!names.add(portableCollisionKey(entryName))) {
                    throw new IOException("Pack directory contains entries that resolve to the "
                        + "same portable path: " + entryName);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    output.putNextEntry(new ZipEntry(entryName + "/"));
                    output.closeEntry();
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Pack entry is not a regular file: " + path);
                }
                output.putNextEntry(new ZipEntry(entryName));
                long written;
                try (InputStream input = Files.newInputStream(path)) {
                    written = copyBounded(input, output, ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, entryName);
                }
                output.closeEntry();
                totalSize += written;
                if (totalSize > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                    throw new IOException("Pack archive size exceeds "
                        + ZipSecurity.DEFAULT_MAX_TOTAL_SIZE + " bytes");
                }
            }
        }
    }

    static List<Path> collectBoundedPaths(Path root, int maxEntries) throws IOException {
        if (maxEntries < 0) {
            throw new IllegalArgumentException("Pack entry limit must be nonnegative");
        }
        List<Path> paths = new ArrayList<>(Math.min(maxEntries, 1024));
        try (var stream = Files.walk(root)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.equals(root)) continue;
                if (paths.size() >= maxEntries) {
                    throw new IOException("Pack contains more than " + maxEntries + " entries");
                }
                paths.add(path);
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
        paths.sort(Path::compareTo);
        return paths;
    }

    private static long copyBounded(InputStream input,
                                    OutputStream output,
                                    long maximum,
                                    String name) throws IOException {
        byte[] buffer = new byte[8192];
        long written = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            written += count;
            if (written > maximum) {
                throw new IOException("Pack entry exceeds " + maximum + " bytes: " + name);
            }
            output.write(buffer, 0, count);
        }
        return written;
    }

    private static String normalizedEntryName(String entryName) throws IOException {
        ZipSecurity.safeEntryName(entryName);
        String slashNormalized = entryName.replace('\\', '/');
        StringBuilder normalized = new StringBuilder();
        for (String part : slashNormalized.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(part);
        }
        if (normalized.length() == 0) {
            throw new IOException("Pack archive contains an empty entry name");
        }
        return normalized.toString();
    }

    /** Matches paths that case-insensitive, Unicode-normalizing filesystems treat as one entry. */
    private static String portableCollisionKey(String entryName) throws IOException {
        return Normalizer.normalize(normalizedEntryName(entryName), Normalizer.Form.NFC)
            .toLowerCase(Locale.ROOT);
    }

    private static void validatePath(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(description + " is a symbolic link: " + path);
        }
    }

    private static void rejectSymbolicLink(Path path, String description) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException(description + " crosses a symbolic link: " + path);
        }
    }

    private static Path requiredParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Pack destination has no parent: " + path);
        }
        return parent;
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void replaceDirectory(Path staged, Path destination) throws IOException {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            moveAtomically(staged, destination);
            return;
        }
        validatePath(destination, "pack destination");

        Path parent = requiredParent(destination);
        Path backup = Files.createTempDirectory(parent, ".retromod-pack-backup-");
        Files.delete(backup);
        boolean oldMoved = false;
        try {
            moveAtomically(destination, backup);
            oldMoved = true;
            moveAtomically(staged, destination);
            deleteRecursivelyQuietly(backup);
        } catch (IOException failure) {
            if (oldMoved && !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveAtomically(backup, destination);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }
}
