// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.Block;

public class BlockState {
    // Declared on BlockBehaviour.BlockStateBase on the host.
    // A default method of TypedInstance on the host.
    public boolean is(net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag) { throw new UnsupportedOperationException(); }
    // TypedInstance.is(type) on the host, erased to Object.
    public boolean is(Object type) { throw new UnsupportedOperationException(); }
    public boolean isAir() { throw new UnsupportedOperationException(); }
    public Block getBlock() { throw new UnsupportedOperationException(); }
    // Declared on BlockBehaviour.BlockStateBase on the host.
    public boolean isSolid() { throw new UnsupportedOperationException(); }
    // Declared on BlockBehaviour.BlockStateBase on the host.
    public net.minecraft.world.phys.shapes.VoxelShape getFaceOcclusionShape(net.minecraft.core.Direction face) { throw new UnsupportedOperationException(); }
    // Declared on BlockBehaviour.BlockStateBase on the host.
    public boolean isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType type) { throw new UnsupportedOperationException(); }
    // Declared on StateHolder on the host.
    public boolean hasProperty(net.minecraft.world.level.block.state.properties.Property<?> property) { throw new UnsupportedOperationException(); }
    // Returns the generic S of StateHolder, erased to Object on the host.
    public <T extends Comparable<T>, V extends T> Object setValue(net.minecraft.world.level.block.state.properties.Property<T> property, V value) { throw new UnsupportedOperationException(); }
    public <T extends Comparable<T>> T getValue(net.minecraft.world.level.block.state.properties.Property<T> property) { throw new UnsupportedOperationException(); }
    // Declared on BlockBehaviour.BlockStateBase on the host; a virtual call resolves either way.
    public net.minecraft.world.level.material.FluidState getFluidState() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, net.minecraft.core.BlockPos pos, boolean includeData) { throw new UnsupportedOperationException(); }
    public boolean liquid() { throw new UnsupportedOperationException(); }
    public boolean ignitedByLava() { throw new UnsupportedOperationException(); }
    public boolean canBeReplaced() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.level.material.PushReaction getPistonPushReaction() { throw new UnsupportedOperationException(); }
}
