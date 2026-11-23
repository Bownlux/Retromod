// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.feature.treedecorators;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.BiConsumer;

public abstract class TreeDecorator {
    public abstract void place(Context context);

    public static final class Context {
        public Context(WorldGenLevel level, BiConsumer<BlockPos, BlockState> decorationSetter, RandomSource random,
                Set<BlockPos> trunkPositions, Set<BlockPos> foliagePositions, Set<BlockPos> rootPositions) {
            throw new UnsupportedOperationException();
        }
    }
}
