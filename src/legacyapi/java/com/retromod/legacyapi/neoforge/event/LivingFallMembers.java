/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.event;

import net.neoforged.neoforge.event.entity.living.LivingFallEvent;

/** NeoForge 1.21.1 fall event members, a float until the fall distance became a double. */
public final class LivingFallMembers {

    private LivingFallMembers() {}

    /** {@code LivingFallEvent.getDistance()}. */
    public static float fallDistance(LivingFallEvent event) {
        return (float) event.getDistance();
    }

    /** {@code LivingFallEvent.setDistance(float)}. */
    public static void setFallDistance(LivingFallEvent event, float distance) {
        event.setDistance(distance);
    }
}
