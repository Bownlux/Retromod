/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.lootstub;

/** Renamed to {@code net/minecraft/class_5341}; the field is {@code LootItemCondition.DIRECT_CODEC}. */
public record LootCondition(String name) implements LootValue {
    public static final Object field_51809 = new StubCodec(LootCondition::new);
}
