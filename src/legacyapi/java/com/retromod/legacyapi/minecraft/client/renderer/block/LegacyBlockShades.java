/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.block;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;

/** {@code getter.getShade(face, shaded)}, read from the per-face lighting 26.1 gave block getters. */
public final class LegacyBlockShades {

    private LegacyBlockShades() {}

    /** The face's brightness when shaded; unshaded faces took the brightness of a face lit from above. */
    public static float getShade(BlockAndTintGetter getter, Direction face, boolean shaded) {
        CardinalLighting lighting = getter.cardinalLighting();
        return shaded ? lighting.byFace(face) : lighting.up();
    }

    // Set once a dedicated server shows it lacks the client block getter, so later calls skip the lookup.
    private static volatile boolean noClientClasses;

    /** {@code level.getShade(face, shaded)} on any level type: only a client level lights faces, so others read full light. */
    public static float getLevelShade(Object level, Direction face, boolean shaded) {
        if (noClientClasses) return 1.0F;
        try {
            return level instanceof BlockAndTintGetter getter ? getShade(getter, face, shaded) : 1.0F;
        } catch (NoClassDefFoundError dedicatedServer) {
            noClientClasses = true;
            return 1.0F;
        }
    }
}
