/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

/**
 * The VariantHolder interface newer Minecraft removed when entity variants moved to data components. Old entities
 * that implement it keep their getter and setter; the game no longer calls them.
 */
public interface LegacyVariantHolder<T> {

    void setVariant(T variant);

    T getVariant();
}
