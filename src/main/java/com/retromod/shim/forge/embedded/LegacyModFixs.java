/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/** Stands in for 1.12.2 Forge's {@code ModFixs}; see {@link LegacyCompoundDataFixer}. */
public final class LegacyModFixs {

    /** {@code registerFix(type, fix)}: accepted, never run. */
    public void registerFix(Object type, Object fix) {
        // A 1.12 save cannot reach this host, so there is nothing for the fix to upgrade.
    }
}
