// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.placement;

import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

public interface PlacementModifier {
    void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output);
    MapCodec<? extends PlacementModifier> codec();
}
