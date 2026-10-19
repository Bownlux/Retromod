/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

/** Stands in for 26.3's {@code ArmorTrim}, which no longer has {@code layerAssetId}. */
public record ArmorTrim(Holder<TrimMaterial> material, Holder<TrimPattern> pattern) {
}
