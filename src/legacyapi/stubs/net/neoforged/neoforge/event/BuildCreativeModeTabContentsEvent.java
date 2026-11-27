// Compile-only stub of a NeoForge 1.21 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.event;

import it.unimi.dsi.fastutil.objects.ObjectSortedSet;
import net.minecraft.world.item.CreativeModeTab.TabVisibility;
import net.minecraft.world.item.ItemStack;

public final class BuildCreativeModeTabContentsEvent {
    public ObjectSortedSet<ItemStack> getParentEntries() { throw new UnsupportedOperationException(); }
    public ObjectSortedSet<ItemStack> getSearchEntries() { throw new UnsupportedOperationException(); }
    public void accept(ItemStack stack, TabVisibility visibility) { throw new UnsupportedOperationException(); }
    public void insertAfter(ItemStack existing, ItemStack stack, TabVisibility visibility) { throw new UnsupportedOperationException(); }
    public void insertBefore(ItemStack existing, ItemStack stack, TabVisibility visibility) { throw new UnsupportedOperationException(); }
    public void insertFirst(ItemStack stack, TabVisibility visibility) { throw new UnsupportedOperationException(); }
    public void remove(ItemStack stack, TabVisibility visibility) { throw new UnsupportedOperationException(); }
}
