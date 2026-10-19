/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

/** Stands in for 26.3's {@code TrimMaterial}, which names a palette instead of an asset suffix. */
public record TrimMaterial(Identifier paletteId) {
}
