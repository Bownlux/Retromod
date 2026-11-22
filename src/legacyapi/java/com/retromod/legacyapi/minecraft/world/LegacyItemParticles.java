/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/** {@code new ItemParticleOption(type, stack)}, which 26.1 takes as an item stack template. */
public final class LegacyItemParticles {

    private LegacyItemParticles() {}

    public static ItemParticleOption itemParticle(ParticleType<ItemParticleOption> type, ItemStack stack) {
        // A template cannot hold an empty stack; the plain item stands in for one.
        if (stack.isEmpty()) return new ItemParticleOption(type, stack.getItem());
        return new ItemParticleOption(type, ItemStackTemplate.fromNonEmptyStack(stack));
    }
}
