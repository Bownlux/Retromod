/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import java.lang.reflect.Method;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;

/**
 * 1.21.2 passed the server level to an effect's tick. A 1.21.1 effect's override, such as Farmer's Delight's comfort,
 * was never called again, so the effect did nothing.
 */
final class LegacyEffectTickCase {

    private LegacyEffectTickCase() {}

    /** The first step that went wrong, or success. */
    static String run(ServerLevel level) throws ReflectiveOperationException {
        CountingEffect effect = new CountingEffect();
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) return "fail: no zombie to tick";
        // The host's form, called as the host calls it; this mod was compiled before it existed.
        Method hostTick = null;
        for (Method method : CountingEffect.class.getMethods()) {
            if (method.getName().equals("applyEffectTick") && method.getParameterCount() == 3) hostTick = method;
        }
        if (hostTick == null) return "skipped: this host still ticks with the 1.21.1 form";
        Object kept = hostTick.invoke(effect, level, zombie, 3);
        if (effect.ticks != 1 || effect.amplifier != 3) return "fail: the old tick ran " + effect.ticks + " times";
        return Boolean.FALSE.equals(kept) ? "success" : "fail: the old tick's answer was lost";
    }

    /** An effect that counts its ticks and asks to be removed. */
    static final class CountingEffect extends MobEffect {
        int ticks;
        int amplifier;

        CountingEffect() {
            super(MobEffectCategory.BENEFICIAL, 0xFFFFFF);
        }

        @Override
        public boolean applyEffectTick(LivingEntity entity, int amplifier) {
            ticks++;
            this.amplifier = amplifier;
            return false;
        }
    }
}
