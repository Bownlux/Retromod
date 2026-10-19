/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

/** Stands in for DataFixerUpper's {@code Decoder}, with the erased shape of its {@code parse}. */
public interface Decoder {
    DataResult parse(DynamicOps ops, Object input);
}
