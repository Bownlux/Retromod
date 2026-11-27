/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item.enchantment;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Optional;

/** The looting level an old drops event reported: the killer's looting, from the items it holds and wears. */
public final class LegacyLootingLevel {

    private LegacyLootingLevel() {}

    /** The looting level of whoever caused {@code source}, or 0 when no living entity did. */
    @SuppressWarnings("unchecked")
    public static int of(DamageSource source) {
        if (source == null || !(source.getEntity() instanceof LivingEntity killer)) return 0;
        // Enchantments are data from 1.21, so looting is looked up in the killer's world. 1.21.1 and 26.x
        // both have lookupOrThrow and get with these descriptors; 1.21.2 changed what lookup returns.
        HolderLookup.Provider registries = killer.level().registryAccess();
        ResourceKey<Registry<Enchantment>> key = (ResourceKey<Registry<Enchantment>>) (ResourceKey<?>) Registries.ENCHANTMENT;
        HolderGetter<Enchantment> enchantments = registries.lookupOrThrow(key);
        Optional<? extends Holder<Enchantment>> looting = enchantments.get(Enchantments.LOOTING);
        return looting.map(holder -> EnchantmentHelper.getEnchantmentLevel(holder, killer)).orElse(0);
    }
}
