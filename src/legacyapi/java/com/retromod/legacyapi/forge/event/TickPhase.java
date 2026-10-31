/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

/** Forge 1.20.1's {@code TickEvent.Phase}, which Forge 26.2 replaced with a Pre and a Post event. */
public enum TickPhase {
    START,
    END
}
