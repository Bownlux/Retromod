/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/** A {@link ResourceTree} in memory, for the CLI and AOT paths that stream a jar instead of extracting it. */
public final class MapResourceTree implements ResourceTree {

    // Entries keep the order they were added in, so a caller that writes them back produces a jar in
    // the original order with new files at the end.
    private final Map<String, byte[]> entries = new LinkedHashMap<>();
    private final TreeSet<String> sortedNames = new TreeSet<>();

    /** An empty tree. */
    public MapResourceTree() {
    }

    /** A tree holding a copy of {@code initial}. */
    public MapResourceTree(Map<String, byte[]> initial) throws IOException {
        for (Map.Entry<String, byte[]> entry : initial.entrySet()) {
            write(entry.getKey(), entry.getValue());
        }
    }

    /** The current files in insertion order, for writing them back out. */
    public Map<String, byte[]> entries() {
        return Collections.unmodifiableMap(entries);
    }

    @Override
    public SortedSet<String> names() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(sortedNames));
    }

    @Override
    public boolean exists(String name) {
        return entries.containsKey(name);
    }

    @Override
    public byte[] read(String name, long maxBytes) throws IOException {
        byte[] bytes = entries.get(name);
        if (bytes == null) throw new NoSuchFileException(name);
        if (maxBytes > 0 && bytes.length > maxBytes) {
            throw new IOException("Resource exceeds " + maxBytes + " bytes: " + name);
        }
        return bytes;
    }

    @Override
    public void write(String name, byte[] bytes) throws IOException {
        // Names are checked exactly as the extracted tree checks them, so a converter never sees a
        // name here that the runtime path would refuse.
        ResourceTree.checkName(name);
        if (bytes == null) throw new IOException("Resource has no content: " + name);
        if (!entries.containsKey(name)) {
            if (entries.size() >= PackArchive.MAX_ARCHIVE_ENTRIES) {
                throw new IOException("Resource tree would exceed "
                        + PackArchive.MAX_ARCHIVE_ENTRIES + " entries: " + name);
            }
            refuseFileDirectoryClash(name);
        }
        entries.put(name, bytes);
        sortedNames.add(name);
    }

    /** Refuses a name that would give a file and a folder the same path. */
    private void refuseFileDirectoryClash(String name) throws IOException {
        // A directory cannot hold both, so the extracted tree refuses such a write. Refusing it here
        // too keeps the two trees from producing different jars.
        for (int slash = name.indexOf('/'); slash > 0; slash = name.indexOf('/', slash + 1)) {
            if (entries.containsKey(name.substring(0, slash))) {
                throw new IOException("Resource path runs through a file: " + name);
            }
        }
        String child = sortedNames.ceiling(name + "/");
        if (child != null && child.startsWith(name + "/")) {
            throw new IOException("Resource path is a directory: " + name);
        }
    }

    @Override
    public boolean delete(String name) throws IOException {
        ResourceTree.checkName(name);
        sortedNames.remove(name);
        return entries.remove(name) != null;
    }
}
