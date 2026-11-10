/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** Forge 26.2's ICapabilitySerializable, which only joins its two superinterfaces. */
public interface ICapabilitySerializable<T extends Tag> extends ICapabilityProvider, INBTSerializable<T> {
}
