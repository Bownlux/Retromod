// Compile-only stub of the feature base LegacyFeatureBridge generates on 26.3 hosts; never packaged.
package com.retromod.generated;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

public abstract class LegacyFeature {
    public LegacyFeature(Codec<?> configCodec) { throw new UnsupportedOperationException(); }
    public abstract boolean place(FeaturePlaceContext context);
}
