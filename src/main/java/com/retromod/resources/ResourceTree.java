/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.util.ZipSecurity;

import java.io.IOException;
import java.util.SortedSet;

/** One mod's files by jar path, the same whether extracted (runtime) or in memory (CLI, AOT). */
public interface ResourceTree {

    /** The largest file a converter reads unless it asks for a smaller bound. */
    long DEFAULT_MAX_READ_BYTES = ZipSecurity.DEFAULT_MAX_ENTRY_SIZE;

    /** Every file name, sorted, without directories. A snapshot, so a converter may write while iterating. */
    SortedSet<String> names() throws IOException;

    /** Whether {@code name} is a file in this tree. */
    boolean exists(String name);

    /** Same as {@link #read(String, long)}, bounded by {@link #DEFAULT_MAX_READ_BYTES}. */
    default byte[] read(String name) throws IOException {
        return read(name, DEFAULT_MAX_READ_BYTES);
    }

    /**
     * The bytes of one file, refusing one over {@code maxBytes}. A missing file throws
     * {@code NoSuchFileException}.
     */
    byte[] read(String name, long maxBytes) throws IOException;

    /** Creates or replaces one file. Refuses a name that would leave the tree. */
    void write(String name, byte[] bytes) throws IOException;

    /** Removes one file. Returns false when it did not exist. */
    boolean delete(String name) throws IOException;

    /** Checks that {@code name} is a canonical relative file name. */
    static String checkName(String name) throws IOException {
        // The extracted tree resolves names against a real directory, so a name that could climb out
        // of the root, or that two archive readers could resolve to different files, is refused for
        // both implementations alike.
        if (name == null || name.isEmpty()) {
            throw new IOException("Resource name is empty");
        }
        if (name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Resource name has a backslash or NUL: " + name);
        }
        // A canonical name has no leading slash, no empty segment, and no . or .. segment.
        String canonical = ZipSecurity.canonicalEntryName(name);
        if (!canonical.equals(name)) {
            throw new IOException("Resource name is not canonical: " + name);
        }
        return name;
    }
}
