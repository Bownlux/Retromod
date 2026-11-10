// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.structure.placement;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

public abstract class AbstractSpreadingStructurePlacement implements StructurePlacement {
    protected AbstractSpreadingStructurePlacement(Vec3i locateOffset, FrequencyReductionMethod method, float frequency, int salt,
            Optional<ExclusionZone> exclusionZone) {}

    public abstract MapCodec<? extends AbstractSpreadingStructurePlacement> codec();

    public boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z) { throw new UnsupportedOperationException(); }

    protected abstract boolean isPlacementChunk(ChunkGeneratorStructureState state, int x, int z);

    public enum FrequencyReductionMethod {}

    public static final class ExclusionZone {}
}
