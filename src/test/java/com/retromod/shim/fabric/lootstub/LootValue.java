/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.lootstub;

/** Every stub loot type reports the name it was decoded from. */
public interface LootValue {
    String name();
}
