// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.capabilities;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class RegisterCapabilitiesEvent {
    public <T, C> void registerBlock(BlockCapability<T, C> capability, IBlockCapabilityProvider<T, C> provider, Block... blocks) { throw new UnsupportedOperationException(); }
    public <T, C, BE extends BlockEntity> void registerBlockEntity(BlockCapability<T, C> capability, BlockEntityType<BE> type, ICapabilityProvider<? super BE, C, T> provider) { throw new UnsupportedOperationException(); }
    public <T, C, E extends Entity> void registerEntity(EntityCapability<T, C> capability, EntityType<E> type, ICapabilityProvider<? super E, C, T> provider) { throw new UnsupportedOperationException(); }
    public <T, C> void registerItem(ItemCapability<T, C> capability, ICapabilityProvider<ItemStack, C, T> provider, ItemLike... items) { throw new UnsupportedOperationException(); }
}
