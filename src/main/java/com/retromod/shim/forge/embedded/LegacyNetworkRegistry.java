/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stand-in for the 1.12.2 {@code NetworkRegistry}. A 1.12 mod creates its channel and GUI handler
 * in pre-init, before it registers content, so a missing class there stops everything after it.
 * Channels are inert: the 1.12 packet pipeline has no modern equivalent to forward to.
 * Parameters are {@code Object} because the transform erases them for calls on these stand-ins.
 */
public final class LegacyNetworkRegistry {

    public static final LegacyNetworkRegistry INSTANCE = new LegacyNetworkRegistry();

    public LegacySimpleNetworkWrapper newSimpleChannel(Object name) {
        return new LegacySimpleNetworkWrapper(name);
    }

    /** GUIs open client-side through a handler the modern menu system never calls. */
    public void registerGuiHandler(Object mod, Object handler) {
        LegacyForgeEvents.note("a GUI handler", mod);
    }
}
