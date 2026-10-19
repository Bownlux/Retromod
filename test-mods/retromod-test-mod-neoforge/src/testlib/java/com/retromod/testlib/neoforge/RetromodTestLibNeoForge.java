/*
 * Retromod Test Library (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testlib.neoforge;

import net.minecraft.world.item.Tier;
import net.neoforged.fml.common.Mod;

/**
 * A library mod for the test mod's #362 case. Its API takes a type that Retromod rebuilds inside
 * each translated jar on newer hosts, so the test mod's call only links when Retromod has it reuse
 * this jar's copy. For #358 its metadata declares no minecraft or neoforge dependency, so Retromod
 * can find its Minecraft version only in the jar manifest.
 */
@Mod("retromod_test_lib_neoforge")
public class RetromodTestLibNeoForge {

    public RetromodTestLibNeoForge() {}

    /** The tool tier interface Minecraft 1.21.2 removed, rebuilt per mod on newer hosts. */
    public static float speed(Tier tier) {
        return tier.getSpeed();
    }
}
