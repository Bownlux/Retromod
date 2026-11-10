/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.item;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Minecraft 1.20.1's item property function, which 1.21.4 replaced with property types in item definitions. */
@FunctionalInterface
public interface ItemPropertyFunction {

    /** The property's value for a stack; an override whose threshold the value reaches picks its model. */
    float call(ItemStack stack, ClientLevel level, LivingEntity entity, int seed);
}
