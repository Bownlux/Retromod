/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

/** One loader API runtime, loaded on every loader, so it names no loader class. */
public interface LoaderApiProvider {

    /** A stable id, such as {@code fabric-26x}. It is the first half of the translation identity. */
    String id();

    /**
     * The version of this provider's rows. It changes whenever what the provider registers
     * changes, so jars translated with older rows are translated again.
     */
    String rowsVersion();

    /** Whether this provider serves mods in {@code context}. */
    boolean appliesTo(LoaderApiContext context);

    /**
     * Registers this provider's rows. Every key must be claimed through {@code registration}
     * before it is registered, so later shims cannot replace it.
     */
    void register(LoaderApiRegistration registration, LoaderApiContext context);

    /**
     * The Retromod release from which {@code auto} turns this provider on in every build, or
     * null while it is opt-in outside snapshots.
     */
    default String graduatedIn() {
        return null;
    }

    /** {@code <id>/<rows version>}, written to translated jars and AOT cache stamps. */
    default String identity() {
        return id() + "/" + rowsVersion();
    }
}
