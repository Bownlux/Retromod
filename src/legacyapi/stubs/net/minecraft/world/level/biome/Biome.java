// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.biome;

public final class Biome {
    private Biome() {}
    public float getBaseTemperature() { throw new UnsupportedOperationException(); }
    public Precipitation getPrecipitationAt(net.minecraft.core.BlockPos pos, int seaLevel) { throw new UnsupportedOperationException(); }
    public boolean coldEnoughToSnow(net.minecraft.core.BlockPos pos, int seaLevel) { throw new UnsupportedOperationException(); }
    public boolean warmEnoughToRain(net.minecraft.core.BlockPos pos, int seaLevel) { throw new UnsupportedOperationException(); }
    public enum Precipitation { NONE, RAIN, SNOW }
}
