// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.biome;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;

public class BiomeGenerationSettings {
    public Iterable<Holder<?>> getCarvers() { throw new UnsupportedOperationException(); }
    public static class PlainBuilder {
        public PlainBuilder addCarver(Holder<?> carver) { throw new UnsupportedOperationException(); }
    }
    public static class Builder extends PlainBuilder {
        public Builder addCarver(ResourceKey<?> carver) { throw new UnsupportedOperationException(); }
    }
}
