/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.item;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Minecraft 1.20.1's item property function whose value is kept between 0 and 1. */
@FunctionalInterface
public interface ClampedItemPropertyFunction extends ItemPropertyFunction {

    /** The value {@link #unclampedCall} gives, kept between 0 and 1. */
    @Override
    default float call(ItemStack stack, ClientLevel level, LivingEntity entity, int seed) {
        float value = unclampedCall(stack, level, entity, seed);
        return value < 0 ? 0 : Math.min(value, 1);
    }

    /** The property's value before it is kept between 0 and 1. */
    float unclampedCall(ItemStack stack, ClientLevel level, LivingEntity entity, int seed);
}
