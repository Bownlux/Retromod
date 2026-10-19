/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.render.shared;

/** The old vanilla {@code ItemColor}, erased so every mod and library shares one type. */
@FunctionalInterface
public interface LegacyItemColor {
    // Named for this type alone, so one class can implement it beside other shared types.
    int legacyItemColor(Object stack, int tintIndex);
}
