// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.structure.placement;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

public interface StructurePlacement {
    boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z);

    MapCodec<? extends StructurePlacement> codec();
}
