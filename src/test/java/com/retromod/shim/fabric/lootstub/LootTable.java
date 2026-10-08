/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.lootstub;

/** Renamed to {@code net/minecraft/class_52}; the field is {@code LootTable.DIRECT_CODEC}. */
public record LootTable(String name) implements LootValue {
    public static final Object field_50021 = new StubCodec(LootTable::new);
}
