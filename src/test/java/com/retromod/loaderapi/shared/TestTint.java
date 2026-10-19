/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.shared;

/** A second shared boundary type with the same erased shape as {@link TestColor}. */
public interface TestTint {
    int tintOf(Object state, Object level, Object pos, int tintIndex);
}
