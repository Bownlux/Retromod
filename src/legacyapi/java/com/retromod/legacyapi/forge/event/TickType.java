/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

/** Forge 1.20.1's {@code TickEvent.Type}, which Forge 26.2 dropped when each tick event became its own interface. */
public enum TickType {
    // Forge 1.20.1's order, so ordinal() and old switch tables read the same values.
    LEVEL,
    PLAYER,
    CLIENT,
    SERVER,
    RENDER
}
