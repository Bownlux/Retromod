/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.sounds;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.cow.CowSoundVariant;
import net.minecraft.world.entity.animal.cow.CowSoundVariants;

/** The old cow sound constants, read from the classic set of the cow sound variants that replaced them in 26.1. */
public final class LegacyCowSounds {

    private LegacyCowSounds() {}

    /** {@code SoundEvents.COW_AMBIENT}. */
    public static SoundEvent ambient() {
        return classic().ambientSound().value();
    }

    /** {@code SoundEvents.COW_HURT}. */
    public static SoundEvent hurt() {
        return classic().hurtSound().value();
    }

    /** {@code SoundEvents.COW_DEATH}. */
    public static SoundEvent death() {
        return classic().deathSound().value();
    }

    /** {@code SoundEvents.COW_STEP}. */
    public static SoundEvent step() {
        return classic().stepSound().value();
    }

    // The classic set holds the sounds every cow made before the moody variant was added.
    private static CowSoundVariant classic() {
        return SoundEvents.COW_SOUNDS.get(CowSoundVariants.SoundSet.CLASSIC);
    }
}
