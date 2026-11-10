/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.structure.placement;

import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyTypeCodec;
import java.util.function.Supplier;

/** The structure placement type 26.3 removed, rebuilt as the codec 26.3 registers for the placement. */
public final class LegacyStructurePlacementType<P> extends LegacyTypeCodec<P> {

    public LegacyStructurePlacementType(Supplier<?> source) {
        super(source);
    }

    /** The type a mod's lambda {@code () -> CODEC} made: a codec that reads and writes with the mod's codec. */
    public static LegacyStructurePlacementType<?> wrap(Supplier<?> source) {
        return new LegacyStructurePlacementType<>(source);
    }
}
