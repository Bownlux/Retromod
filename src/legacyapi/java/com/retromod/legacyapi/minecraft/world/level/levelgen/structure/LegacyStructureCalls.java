/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.StructureAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** The two structure lookups 26.3 retyped, in the shapes older mods call. */
public final class LegacyStructureCalls {

    private LegacyStructureCalls() {}

    /** {@code manager.getStartForStructure(section, structure, access)}: 26.3 reads the start from the access alone. */
    public static StructureStart getStartForStructure(StructureManager manager, SectionPos section, Structure structure,
            StructureAccess access) {
        return manager.getStartForStructure(structure, access);
    }

    /** {@code manager.getStructureWithPieceAt(pos, structure)}: 26.3 takes the block coordinates one by one. */
    public static StructureStart getStructureWithPieceAt(StructureManager manager, BlockPos pos, Structure structure) {
        return manager.getStructureWithPieceAt(pos.getX(), pos.getY(), pos.getZ(), structure);
    }
}
