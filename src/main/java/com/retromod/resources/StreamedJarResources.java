/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.util.ArchiveLimits;
import com.retromod.util.ZipSecurity;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A mod's assets and data left in its source jar, read only when a migration step asks for one. */
public final class StreamedJarResources implements Closeable {

    private final ZipFile archive;
    // Source order, so the files still here are written in the order the jar listed them.
    private final Map<String, ZipEntry> entries = new LinkedHashMap<>();
    private final TreeSet<String> sortedNames = new TreeSet<>();
    // Files too large for a step to read are only copied, and no step sees them.
    private final Set<String> opaque = new HashSet<>();
    // Files a step deleted or wrote a new version of.
    private final Set<String> replaced = new HashSet<>();

    private StreamedJarResources(ZipFile archive) {
        this.archive = archive;
    }

    /** Opens {@code jar} for reading; the caller adds the entries its own loop leaves in place. */
    public static StreamedJarResources open(Path jar) throws IOException {
        return new StreamedJarResources(new ZipFile(jar.toFile()));
    }

    /**
     * Whether a transform loop leaves this entry in the source jar instead of unpacking it: an
     * asset or data file of known size, when the jar streams its resources or the file is too large
     * to hold in memory. Everything else a loop handles as before.
     */
    public static boolean streams(String name, long declaredSize) {
        if (name == null || declaredSize < 0) return false;
        if (!name.startsWith("assets/") && !name.startsWith("data/")) return false;
        if (!ModResourceMigrations.isMigratedResource(name)) return false;
        return ArchiveLimits.streamsResources() || declaredSize > ZipSecurity.DEFAULT_MAX_ENTRY_SIZE;
    }

    /**
     * Whether a transform loop copies this entry from the source jar without unpacking it: a file
     * over 128 MiB that no step reads. A file a step reads stays in the loop, under its 128 MiB limit.
     */
    public static boolean copiedUnchanged(String name, long declaredSize) {
        if (name == null || declaredSize <= ZipSecurity.DEFAULT_MAX_ENTRY_SIZE) return false;
        return !readByTransforms(name);
    }

    /** Whether an unpacking loop leaves this entry in the source jar: {@link #streams} or {@link #copiedUnchanged}. */
    public static boolean leftInSourceJar(String name, long declaredSize) {
        return streams(name, declaredSize) || copiedUnchanged(name, declaredSize);
    }

    /** Whether a transform step reads this entry by name: classes, metadata, mixin files, wideners, and nested jars. */
    public static boolean readByTransforms(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        boolean nestedJar = (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))
                && lower.endsWith(".jar");
        return nestedJar || lower.endsWith(".class") || lower.endsWith(".json") || lower.endsWith(".toml")
                || lower.endsWith(".accesswidener") || lower.endsWith(".classtweaker")
                || name.equals("META-INF/MANIFEST.MF") || name.equals("META-INF/accesstransformer.cfg")
                || name.equals("mcmod.info");
    }

    /** Leaves {@code name} in the source jar. Its declared size bounds every later read. */
    public void add(String name) throws IOException {
        ResourceTree.checkName(name);
        ZipEntry entry = archive.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() < 0) {
            throw new IOException("Cannot stream " + name + " from " + archive.getName());
        }
        if (entries.putIfAbsent(name, entry) != null) {
            throw new IOException("mod jar contains entries that resolve to the same output path: " + name);
        }
        sortedNames.add(name);
        if (entry.getSize() > ResourceTree.DEFAULT_MAX_READ_BYTES) opaque.add(name);
    }

    /** True when nothing was left in the source jar. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The migration view: files a step wrote come from {@code upper}, the rest from the source jar. */
    public ResourceTree over(ResourceTree upper) {
        return new Layered(upper);
    }

    /** Names still to copy from the source jar, in source order, skipping any {@code shadowed} one. */
    public List<String> remaining(Predicate<String> shadowed) {
        List<String> names = new ArrayList<>();
        for (String name : entries.keySet()) {
            if (!replaced.contains(name) && !shadowed.test(name)) names.add(name);
        }
        return names;
    }

    /**
     * Names still to copy after a step wrote files into {@code directory}. Names are compared
     * exactly, as in the jar, because a disk that ignores case would report {@code Foo.png} as
     * present once a step wrote {@code foo.png}.
     */
    public List<String> remaining(Path directory) throws IOException {
        Set<String> written = new HashSet<>();
        try (var walk = java.nio.file.Files.walk(directory)) {
            for (Path file : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
                written.add(directory.relativize(file).toString().replace(java.io.File.separatorChar, '/'));
            }
        }
        return remaining(written::contains);
    }

    /** Streams one file into {@code out} and returns its size. A file larger than it declared throws. */
    public long copy(String name, OutputStream out) throws IOException {
        ZipEntry entry = entries.get(name);
        if (entry == null) throw new NoSuchFileException(name);
        try (InputStream input = archive.getInputStream(entry)) {
            return ZipSecurity.copyBounded(input, out, entry.getSize(), name);
        }
    }

    private boolean visible(String name) {
        return entries.containsKey(name) && !opaque.contains(name) && !replaced.contains(name);
    }

    private byte[] read(String name, long maxBytes) throws IOException {
        ZipEntry entry = entries.get(name);
        long limit = maxBytes > 0 ? maxBytes : ResourceTree.DEFAULT_MAX_READ_BYTES;
        if (entry.getSize() > limit) {
            throw new IOException("Resource exceeds " + limit + " bytes: " + name);
        }
        // Bounded by the declared size, so a lying entry cannot make each read unpack more.
        try (InputStream input = archive.getInputStream(entry)) {
            return ZipSecurity.safeReadAllBytes(input, Math.max(1, entry.getSize()));
        }
    }

    @Override
    public void close() throws IOException {
        archive.close();
    }

    /** Written files over the files left in the jar. */
    private final class Layered implements ResourceTree {
        private final ResourceTree upper;

        Layered(ResourceTree upper) {
            this.upper = upper;
        }

        @Override
        public SortedSet<String> names() throws IOException {
            TreeSet<String> names = new TreeSet<>(upper.names());
            for (String name : sortedNames) {
                if (visible(name)) names.add(name);
            }
            return Collections.unmodifiableSortedSet(names);
        }

        @Override
        public boolean exists(String name) {
            return upper.exists(name) || visible(name);
        }

        @Override
        public byte[] read(String name, long maxBytes) throws IOException {
            if (upper.exists(name)) return upper.read(name, maxBytes);
            if (!visible(name)) throw new NoSuchFileException(name);
            return StreamedJarResources.this.read(name, maxBytes);
        }

        @Override
        public void write(String name, byte[] bytes) throws IOException {
            ResourceTree.checkName(name);
            refuseFileDirectoryClash(name);
            upper.write(name, bytes);
            if (entries.containsKey(name)) replaced.add(name);
        }

        @Override
        public boolean delete(String name) throws IOException {
            ResourceTree.checkName(name);
            boolean deleted = upper.delete(name);
            if (visible(name)) {
                replaced.add(name);
                deleted = true;
            }
            return deleted;
        }

        /** Refuses a write that would give a file and a folder the same path, as the other trees do. */
        private void refuseFileDirectoryClash(String name) throws IOException {
            for (int slash = name.indexOf('/'); slash > 0; slash = name.indexOf('/', slash + 1)) {
                String parent = name.substring(0, slash);
                if (entries.containsKey(parent) && !replaced.contains(parent)) {
                    throw new IOException("Resource path runs through a file: " + name);
                }
            }
            for (String child : sortedNames.tailSet(name + "/")) {
                if (!child.startsWith(name + "/")) break;
                if (!replaced.contains(child)) throw new IOException("Resource path is a directory: " + name);
            }
        }
    }
}
