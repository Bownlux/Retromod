/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.caphost;

/** Stands in for a CapabilityManager without the package-private get, as a later Forge might ship it. */
public final class BareCapabilityManager {
    public BareCapabilityManager() {}
}
