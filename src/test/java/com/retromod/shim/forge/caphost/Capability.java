/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.caphost;

/** Stands in for Forge's Capability: a name and whether it has been registered. */
public final class Capability<T> {
    private final String name;
    private boolean registered;

    Capability(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isRegistered() {
        return registered;
    }

    void onRegister() {
        registered = true;
    }
}
