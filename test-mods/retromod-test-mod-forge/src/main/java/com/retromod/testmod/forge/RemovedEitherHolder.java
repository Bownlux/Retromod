/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.Item;

import java.util.Optional;

/**
 * #271: 26.1 removed {@code EitherHolder}, which Minepathy uses to name its syringe's damage type
 * before the registries load. This 1.20.1 build calls it in its 1.21.11 shape through a
 * compile-only stand-in. Kept out of the mod class, so a type a host lacks fails only its own check.
 */
final class RemovedEitherHolder {

    private RemovedEitherHolder() {}

    /**
     * An {@code EitherHolder} built from a key reports that key. It runs where the 26.1 stand-in
     * applies, which a host shows by having {@code delayedHolderComponent}. An older host either
     * has no such class or has its own.
     */
    static boolean keyReadsBack() {
        if (!hostDelaysHolderComponents()) return true;
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
                new ResourceLocation("retromod", "test_syringe"));
        return new EitherHolder<>(key).key().equals(Optional.of(key));
    }

    private static boolean hostDelaysHolderComponents() {
        for (java.lang.reflect.Method method : Item.Properties.class.getMethods()) {
            if (method.getName().equals("delayedHolderComponent")) return true;
        }
        return false;
    }
}
