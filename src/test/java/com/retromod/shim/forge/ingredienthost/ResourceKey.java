/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

/** Stands in for a registry's ResourceKey, which Forge compares by identity. */
public final class ResourceKey {
    private final String name;

    public ResourceKey(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
