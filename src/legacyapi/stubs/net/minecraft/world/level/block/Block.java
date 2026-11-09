// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.block;

import net.minecraft.world.level.ItemLike;

public class Block implements ItemLike {
    public net.minecraft.world.item.Item asItem() { throw new UnsupportedOperationException(); }
    public net.minecraft.core.Holder.Reference<Block> builtInRegistryHolder() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.level.block.state.StateDefinition<Block, net.minecraft.world.level.block.state.BlockState> getStateDefinition() { throw new UnsupportedOperationException(); }
}
