// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item;

import net.minecraft.world.level.ItemLike;

public class Item implements ItemLike {
    public static final com.mojang.serialization.Codec<net.minecraft.core.Holder<Item>> CODEC = null;
    public Item asItem() { throw new UnsupportedOperationException(); }
    public net.minecraft.core.Holder.Reference<Item> builtInRegistryHolder() { throw new UnsupportedOperationException(); }
    public ItemStack getDefaultInstance() { throw new UnsupportedOperationException(); }
    public boolean canFitInsideContainerItems() { throw new UnsupportedOperationException(); }
    public final ItemStackTemplate getCraftingRemainder() { throw new UnsupportedOperationException(); }

    public static class Properties {
        public Properties setId(net.minecraft.resources.ResourceKey<Item> id) { throw new UnsupportedOperationException(); }
        public net.minecraft.resources.Identifier effectiveModel() { throw new UnsupportedOperationException(); }
        public <T> Properties component(net.minecraft.core.component.DataComponentType<T> type, T value) { throw new UnsupportedOperationException(); }
        public Properties signText() { throw new UnsupportedOperationException(); }
        public Properties useBlockDescriptionPrefix() { throw new UnsupportedOperationException(); }
    }
    public net.minecraft.core.component.DataComponentMap components() { throw new UnsupportedOperationException(); }

    public final String getDescriptionId() { throw new UnsupportedOperationException(); }
}
