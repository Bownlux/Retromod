// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceKey;

public class SurfaceRules {
    @SuppressWarnings("rawtypes")
    public static ConditionSource isBiome(HolderGetter getter, ResourceKey... keys) { throw new UnsupportedOperationException(); }

    // The host declares Function<Context, Condition>; Condition is package-private, so the stub stays raw.
    @SuppressWarnings("rawtypes")
    public interface ConditionSource extends java.util.function.Function {
        MapCodec codec();
    }
}
