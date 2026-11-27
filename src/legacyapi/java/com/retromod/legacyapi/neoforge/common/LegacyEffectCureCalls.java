/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.EventHooks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** The effect cure calls NeoForge 1.21.1 mods make, answered with the host's own effect removal. */
public final class LegacyEffectCureCalls {

    private LegacyEffectCureCalls() {}

    /** {@code LivingEntity.removeEffectsCuredBy(cure)}: ends each effect the cure ends, on the server only, as before. */
    public static boolean removeEffectsCuredBy(LivingEntity entity, LegacyEffectCure cure) {
        if (!(entity.level() instanceof ServerLevel)) return false;
        boolean removed = false;
        // A copy, since each removal changes the entity's effects; the host posts the removal event for each.
        for (MobEffectInstance effect : new ArrayList<>(entity.getActiveEffects())) {
            if (getCures(effect).contains(cure)) removed |= entity.removeEffect(effect.getEffect());
        }
        return removed;
    }

    /** {@code MobEffectInstance.getCures()}: the default cures, and honey for poison, as a new set each call. */
    public static Set<LegacyEffectCure> getCures(MobEffectInstance effect) {
        Set<LegacyEffectCure> cures = new HashSet<>(LegacyEffectCures.DEFAULT_CURES);
        if (MobEffects.POISON.equals(effect.getEffect())) cures.add(LegacyEffectCures.HONEY);
        return cures;
    }

    /** {@code EventHooks.onEffectRemoved(entity, effect, cure)}: the host's removal event, which no longer names a cure. */
    public static boolean onEffectRemoved(LivingEntity entity, MobEffectInstance effect, LegacyEffectCure cure) {
        return EventHooks.onEffectRemoved(entity, effect);
    }
}
