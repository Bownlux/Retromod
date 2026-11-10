/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.compoundhost;

/** Stands in for Holder: the item it holds. */
public interface Holder<T> {
    T value();
}
