/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.lootstub;

/** Renamed to {@code net/minecraft/class_55}; the field is {@code LootPool.CODEC}. */
public record LootPool(String name) implements LootValue {
    public static final Object field_45795 = new StubCodec(LootPool::new);
}
