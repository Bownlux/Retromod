/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.lootstub;

/** Renamed to {@code net/minecraft/class_131}; the field is {@code LootItemFunctions.ROOT_CODEC}. */
public final class LootFunctions {
    public static final Object field_50023 = new StubCodec(LootFunction::new);

    private LootFunctions() {}
}
