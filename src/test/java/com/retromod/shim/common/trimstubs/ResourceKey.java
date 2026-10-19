/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

/** Stands in for {@code ResourceKey}: an equipment asset's key, of which the trim helper reads the id. */
public record ResourceKey(Identifier identifier) {
}
