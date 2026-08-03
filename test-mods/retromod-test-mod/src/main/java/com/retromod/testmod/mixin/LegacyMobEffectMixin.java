/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Load-time regression for the MobEffect to Holder mixin shadow bridge. */
@Mixin(LivingEntity.class)
public abstract class LegacyMobEffectMixin {

    @Shadow
    public abstract boolean hasStatusEffect(StatusEffect effect);
}
