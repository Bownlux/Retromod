// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.block;

import net.minecraft.world.level.ItemLike;

public class Block extends net.minecraft.world.level.block.state.BlockBehaviour implements ItemLike {
    public final net.minecraft.world.level.block.state.BlockState defaultBlockState() { throw new UnsupportedOperationException(); }
    public final net.minecraft.world.level.block.state.BlockState withPropertiesOf(net.minecraft.world.level.block.state.BlockState state) { throw new UnsupportedOperationException(); }
    public static void popResourceFromFace(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction face, net.minecraft.world.item.ItemStack stack) { throw new UnsupportedOperationException(); }
    public boolean isPossibleToRespawnInThis(net.minecraft.world.level.block.state.BlockState state) { throw new UnsupportedOperationException(); }
    public net.minecraft.world.item.Item asItem() { throw new UnsupportedOperationException(); }
    // 1.21 to 1.21.3 shape.
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) { throw new UnsupportedOperationException(); }
    public net.minecraft.core.Holder.Reference<Block> builtInRegistryHolder() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.level.block.state.StateDefinition<Block, net.minecraft.world.level.block.state.BlockState> getStateDefinition() { throw new UnsupportedOperationException(); }
}
