/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.placement;

import com.mojang.serialization.MapCodec;
import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyTypeCodec;
import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/** Rebuilds the placement modifier types 26.3 removed as the codecs it registers in their place. */
public final class LegacyPlacementModifierTypes {

    private LegacyPlacementModifierTypes() {
    }

    /** The type a mod's lambda {@code () -> CODEC} made: a codec that reads and writes with the mod's codec. */
    public static LegacyPlacementModifierType<PlacementModifier> wrap(Supplier<?> source) {
        return new LegacyPlacementModifierType<>(source);
    }

    /** {@code type.codec()}: the type is its own codec now. */
    public static MapCodec<?> codec(Object type) {
        return LegacyTypeCodec.codecOf(type);
    }
}
