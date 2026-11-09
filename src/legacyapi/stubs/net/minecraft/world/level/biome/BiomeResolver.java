// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.biome;

import net.minecraft.core.Holder;

public interface BiomeResolver {
    Holder<?> getNoiseBiome(int x, int y, int z);
}
