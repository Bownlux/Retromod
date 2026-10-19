/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.Item;

/**
 * #271: 26.1 removed {@code EitherHolder}, which Minepathy uses to name its syringe's damage type
 * before the registries load, and 26.3 removed the {@code BLOCK_TYPE} registry its block codecs
 * were registered in. Kept out of the mod class, so a type a host lacks fails only its own check.
 */
final class RemovedRegistryTypes {

    private RemovedRegistryTypes() {}

    /**
     * An {@code EitherHolder} built from a key reports that key. It runs where the 26.1 stand-in
     * applies, which a host shows by having {@code delayedHolderComponent}. Before 26.1 the host's
     * own class answers, and by 1.21.11 its {@code key()} returned an Optional, which this 1.21.1
     * build does not expect.
     */
    static boolean eitherHolderKeyReadsBack() {
        if (!hostDelaysHolderComponents()) return true;
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
                ResourceLocation.fromNamespaceAndPath("retromod", "test_syringe"));
        return new EitherHolder<>(key).key() == key;
    }

    static boolean blockTypeRegistryReads() {
        return BuiltInRegistries.BLOCK_TYPE != null;
    }

    private static boolean hostDelaysHolderComponents() {
        for (java.lang.reflect.Method method : Item.Properties.class.getMethods()) {
            if (method.getName().equals("delayedHolderComponent")) return true;
        }
        return false;
    }
}
