/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/** Saddleable, which 1.21.5 replaced with the saddle equipment slot, for a mod's own entities that still implement it. */
public interface LegacySaddleable {

    boolean isSaddleable();

    void equipSaddle(ItemStack stack, SoundSource source);

    default SoundEvent getSaddleSoundEvent() {
        return SoundEvents.HORSE_SADDLE.value();
    }

    boolean isSaddled();
}
