// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item;

import net.minecraft.world.level.ItemLike;

public class Item implements ItemLike {
    public Item asItem() { throw new UnsupportedOperationException(); }
    public net.minecraft.core.Holder.Reference<Item> builtInRegistryHolder() { throw new UnsupportedOperationException(); }
    public ItemStack getDefaultInstance() { throw new UnsupportedOperationException(); }
    public boolean canFitInsideContainerItems() { throw new UnsupportedOperationException(); }
    public net.minecraft.core.component.DataComponentMap components() { throw new UnsupportedOperationException(); }

    public static class Properties {
        public Properties signText() { throw new UnsupportedOperationException(); }
        public Properties useBlockDescriptionPrefix() { throw new UnsupportedOperationException(); }
    }
}
