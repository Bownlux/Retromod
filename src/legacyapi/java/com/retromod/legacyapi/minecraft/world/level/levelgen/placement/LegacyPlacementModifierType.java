/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.placement;

import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyTypeCodec;
import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/** The placement modifier type 26.3 removed, rebuilt as the codec 26.3 registers for the modifier. */
public final class LegacyPlacementModifierType<P extends PlacementModifier> extends LegacyTypeCodec<P> {

    public LegacyPlacementModifierType(Supplier<?> source) {
        super(source);
    }
}
