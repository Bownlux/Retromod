/*
 * Retromod Test Library (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testlib.forge;

import net.minecraft.world.item.Tier;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.common.Mod;

/**
 * A library mod for the test mod's #362 case. Its API takes types that Retromod rebuilds inside
 * each translated jar on newer hosts, so the test mod's calls only link when Retromod has it reuse
 * this jar's copies. For #358 its metadata declares no minecraft dependency, so Retromod can find
 * its Minecraft version only in the jar manifest.
 */
@Mod("retromod_test_lib_forge")
public class RetromodTestLibForge {

    public RetromodTestLibForge() {}

    /** Forge's capability holder, rebuilt per mod on NeoForge. */
    public static boolean isPresent(LazyOptional<?> value) {
        return value.isPresent();
    }

    /** The tool tier interface Minecraft 1.21.2 removed, rebuilt per mod on newer hosts. */
    public static float speed(Tier tier) {
        return tier.getSpeed();
    }
}
