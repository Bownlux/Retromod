/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import java.io.IOException;

/** One translation of a mod's resources or data, run in order by {@link ModResourceMigrations}. */
public interface ModResourceConverter {

    /** A short name for logs, such as {@code data layout}. */
    String name();

    /** Whether this mod and host need the step. An unknown source version never counts as old. */
    boolean appliesTo(MigrationContext context);

    /**
     * Translates the tree in place and returns the number of files written, moved, or removed. Never
     * replace a file the mod already ships at the path being written. Stay idempotent, since a tree
     * can be migrated more than once. Throw when the tree itself is unsafe, but skip a single malformed
     * file rather than fail the whole mod over it.
     */
    int apply(ResourceTree tree, MigrationContext context) throws IOException;
}
