// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.block.state;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

// Abstract on the host; Block extends it.
public abstract class BlockBehaviour {
    protected BlockBehaviour() {}

    public final Properties properties() { throw new UnsupportedOperationException(); }
    public final java.util.Optional<net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable>> getLootTable() { throw new UnsupportedOperationException(); }
    public final String getDescriptionId() { throw new UnsupportedOperationException(); }

    public static class Properties {
        public Properties setId(net.minecraft.resources.ResourceKey<net.minecraft.world.level.block.Block> id) { throw new UnsupportedOperationException(); }
        public Properties isViewBlocking(StateArgumentPredicate<AABB> predicate) { throw new UnsupportedOperationException(); }
        public Properties emissiveRendering(java.util.function.Predicate<BlockState> predicate) { throw new UnsupportedOperationException(); }
    }

    public interface StatePredicate {
        boolean test(BlockState state, BlockGetter level, BlockPos pos);
    }

    public interface StateArgumentPredicate<A> {
        boolean test(BlockState state, BlockGetter level, BlockPos pos, A argument);
    }
}
