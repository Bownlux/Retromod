/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.util.ZipSecurity;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/** A {@link ResourceTree} over a mod the runtime loaders extracted to a directory. */
public final class DirectoryResourceTree implements ResourceTree {

    private final Path root;
    // The directory is walked once. This list is kept in step with every write and delete after that.
    private final SortedSet<String> names;

    private DirectoryResourceTree(Path root, SortedSet<String> names) {
        this.root = root;
        this.names = names;
    }

    /** Opens an extracted mod, refusing links, special files, and more entries than a pack may have. */
    public static DirectoryResourceTree open(Path root) throws IOException {
        if (root == null || Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Resource tree root is not a regular directory: " + root);
        }
        // The same bounded walk the data migration has always used.
        Path normalizedRoot = root.toAbsolutePath().normalize();
        SortedSet<String> names = new TreeSet<>();
        for (Path path : PackArchive.collectBoundedPaths(
                normalizedRoot, PackArchive.MAX_ARCHIVE_ENTRIES)) {
            // A link anywhere fails the whole migration, so a crafted jar cannot redirect a write
            // outside the extraction directory.
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Resource tree contains a symbolic link: " + path);
            }
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                names.add(normalizedRoot.relativize(path).toString()
                        .replace(File.separatorChar, '/'));
            } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Resource tree entry is not a regular file: " + path);
            }
        }
        return new DirectoryResourceTree(normalizedRoot, names);
    }

    /** The directory this tree reads and writes. */
    public Path root() {
        return root;
    }

    @Override
    public SortedSet<String> names() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(names));
    }

    @Override
    public boolean exists(String name) {
        return names.contains(name);
    }

    @Override
    public byte[] read(String name, long maxBytes) throws IOException {
        Path file = resolve(name);
        if (!names.contains(name)) throw new NoSuchFileException(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Resource is not a regular file: " + file);
        }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return ZipSecurity.safeReadAllBytes(input, maxBytes);
        }
    }

    @Override
    public void write(String name, byte[] bytes) throws IOException {
        Path file = resolve(name);
        if (!names.contains(name) && names.size() >= PackArchive.MAX_ARCHIVE_ENTRIES) {
            throw new IOException("Resource tree would exceed " + PackArchive.MAX_ARCHIVE_ENTRIES
                    + " entries: " + name);
        }
        // Checked before creating the parents, so a link planted after the walk cannot steer the new
        // directories outside the root, and again after, on the exact path being written.
        PackArchive.validateContainedPath(root, file, "Resource " + name);
        Files.createDirectories(file.getParent());
        PackArchive.validateContainedPath(root, file, "Resource " + name);
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Resource path is a directory: " + name);
        }
        Files.write(file, bytes);
        names.add(name);
    }

    @Override
    public boolean delete(String name) throws IOException {
        Path file = resolve(name);
        if (!names.remove(name)) return false;
        PackArchive.validateContainedPath(root, file, "Resource " + name);
        Files.deleteIfExists(file);
        return true;
    }

    /** The file for an entry name, refusing a name that escapes the root. */
    private Path resolve(String name) throws IOException {
        ResourceTree.checkName(name);
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new IOException("Resource path escapes the tree root: " + name);
        }
        return file;
    }
}
