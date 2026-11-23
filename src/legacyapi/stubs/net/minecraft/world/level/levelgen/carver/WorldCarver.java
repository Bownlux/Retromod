// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.carver;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarverOutput;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

public interface WorldCarver {
    boolean carve(WorldGenerationContext context, RandomSource random, ChunkPos chunk, ChunkPos source, CarverOutput output);
    boolean isStartChunk(RandomSource random);
    MapCodec<? extends WorldCarver> codec();
}
