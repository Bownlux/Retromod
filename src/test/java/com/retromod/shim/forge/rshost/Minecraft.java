/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.rshost;

/** Stand-in for the host Minecraft: the client level and the modifier keys held. */
public class Minecraft {
    public static Minecraft instance;
    public ClientLevel level;
    public boolean shift;
    public boolean control;
    public boolean alt;

    public static Minecraft getInstance() {
        return instance;
    }

    public boolean hasShiftDown() {
        return shift;
    }

    public boolean hasControlDown() {
        return control;
    }

    public boolean hasAltDown() {
        return alt;
    }
}
