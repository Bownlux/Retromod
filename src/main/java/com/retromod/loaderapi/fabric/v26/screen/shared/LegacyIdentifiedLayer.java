/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen.shared;

/** The old Fabric API {@code IdentifiedLayer} of 1.21.4 and 1.21.5: a HUD layer with its id. */
public interface LegacyIdentifiedLayer extends LegacyHudLayer {
    /** The layer's {@code Identifier}. */
    Object legacyHudLayerId();
}
