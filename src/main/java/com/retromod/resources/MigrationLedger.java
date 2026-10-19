/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Records what one migration run did that a later converter must know about. */
final class MigrationLedger {

    /**
     * One copied file. The original at {@code from} stays and the game reads {@code to}. {@code kind}
     * is the singular data folder, such as {@code tags/block}, and {@code legacyAssetSide} marks an
     * original under {@code assets/}, where 1.12 kept data.
     */
    record Relocation(String from, String to, String kind, boolean legacyAssetSide) {}

    // The layout converter copies data the old game read from a path the new game ignores. Those
    // copies are new to the loader, so DataLoadGate checks each one before it can stop a world from
    // loading. This list is how the gate learns which files were copied.
    private final List<Relocation> relocations = new ArrayList<>();

    void relocated(Relocation relocation) {
        relocations.add(relocation);
    }

    List<Relocation> relocations() {
        return Collections.unmodifiableList(relocations);
    }
}
