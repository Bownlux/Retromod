/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.structure.placement;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement;

/** The structure placement class mods extended before 26.3, which made it an interface keyed by its codec. */
public abstract class LegacyStructurePlacement extends AbstractSpreadingStructurePlacement {

    protected LegacyStructurePlacement(Vec3i locateOffset, FrequencyReductionMethod method, float frequency, int salt,
            Optional<ExclusionZone> exclusionZone) {
        super(locateOffset, method, frequency, salt, exclusionZone);
    }

    /** The placement type the mod registered, which 26.3 keeps as the placement's codec. */
    public abstract LegacyStructurePlacementType<?> type();

    @Override
    @SuppressWarnings("unchecked")
    public MapCodec<? extends AbstractSpreadingStructurePlacement> codec() {
        return (MapCodec<? extends AbstractSpreadingStructurePlacement>) (MapCodec<?>) type();
    }
}
