/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/**
 * Follows the 1.21.5 renames of ten {@code MobEffects} constants for mods built for 1.20.5 to
 * 1.21.4, where those constants were already {@code Holder<MobEffect>}.
 *
 * <p>1.21.5 gave the old internal names their player-facing ones: {@code DIG_SPEED} became
 * {@code HASTE}, {@code CONFUSION} became {@code NAUSEA}, and so on. A mod reading
 * {@code MobEffects.DIG_SPEED} dies with {@code NoSuchFieldError}. Bountiful Fares does this in
 * its block and food registration on 26.1.2. The rename is scoped to the {@code Holder} type
 * because older mods read the same names as {@code MobEffect}, and those reads keep their
 * registry lookup.
 */
public final class LegacyMobEffectNames {

    private LegacyMobEffectNames() {}

    static final String MOB_EFFECTS = "net/minecraft/world/effect/MobEffects";
    static final String HOLDER = "Lnet/minecraft/core/Holder;";

    /** (name up to 1.21.4, name from 1.21.5), read from the official mappings of both. */
    static final String[][] RENAMES = {
        {"MOVEMENT_SPEED", "SPEED"},
        {"MOVEMENT_SLOWDOWN", "SLOWNESS"},
        {"DIG_SPEED", "HASTE"},
        {"DIG_SLOWDOWN", "MINING_FATIGUE"},
        {"DAMAGE_BOOST", "STRENGTH"},
        {"HEAL", "INSTANT_HEALTH"},
        {"HARM", "INSTANT_DAMAGE"},
        {"JUMP", "JUMP_BOOST"},
        {"CONFUSION", "NAUSEA"},
        {"DAMAGE_RESISTANCE", "RESISTANCE"},
    };

    /** Registers the renames. Only for hosts that run Mojang names, 1.21.5 and newer. */
    public static void register(RetromodTransformer transformer) {
        for (String[] rename : RENAMES) {
            transformer.registerFieldRenameForType(MOB_EFFECTS, rename[0], HOLDER, rename[1]);
        }
    }
}
