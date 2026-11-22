// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;

public class Level implements CollisionGetter {
    public static final net.minecraft.resources.ResourceKey<Level> OVERWORLD = null;
    // Abstract on the host.
    public net.minecraft.world.level.storage.LevelData.RespawnData getRespawnData() { throw new UnsupportedOperationException(); }
    // 1.21.2 to 26.2 only.
    public net.minecraft.world.level.block.entity.FuelValues fuelValues() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) { throw new UnsupportedOperationException(); }
    public BlockState getBlockState(BlockPos pos) { throw new UnsupportedOperationException(); }
    public boolean setBlock(BlockPos pos, BlockState state, int flags) { throw new UnsupportedOperationException(); }
    public <T, C> T getCapability(BlockCapability<T, C> capability, BlockPos pos, C context) { throw new UnsupportedOperationException(); }
    public <T, C> T getCapability(BlockCapability<T, C> capability, BlockPos pos, BlockState state, BlockEntity blockEntity, C context) { throw new UnsupportedOperationException(); }
    public boolean isRaining() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.level.storage.LevelData getLevelData() { throw new UnsupportedOperationException(); }
    public MinecraftServer getServer() { throw new UnsupportedOperationException(); }
    public RegistryAccess registryAccess() { throw new UnsupportedOperationException(); }
    public java.util.List<Entity> getEntities(Entity except, AABB area, java.util.function.Predicate<? super Entity> filter) { throw new UnsupportedOperationException(); }
    public long getOverworldClockTime() { throw new UnsupportedOperationException(); }
    // Abstract on the host.
    public Entity getEntity(int id) { throw new UnsupportedOperationException(); }
    public net.minecraft.util.RandomSource getRandom() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.level.dimension.DimensionType dimensionType() { throw new UnsupportedOperationException(); }
    // The host also has a covariant abstract form returning EnvironmentAttributeSystem; this is the reader bridge.
    public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() { throw new UnsupportedOperationException(); }
}
