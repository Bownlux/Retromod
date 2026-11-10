/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** Stands in for Minecraft's server, with the registries a test gives it. */
public class MinecraftServer {
    private final RegistryAccess.Frozen registries;

    public MinecraftServer(RegistryAccess.Frozen registries) {
        this.registries = registries;
    }

    public RegistryAccess.Frozen registryAccess() {
        return registries;
    }
}
