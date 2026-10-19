/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.shared;

/** A shared boundary type for the two-jar test, shaped like an erased {@code BlockColor}. */
public interface TestColor {
    int colorOf(Object state, Object level, Object pos, int tintIndex);
}
