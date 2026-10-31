// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item.component;

import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

public final class ItemContainerContents {
    public static final ItemContainerContents EMPTY = null;
    public static ItemContainerContents fromItems(List<ItemStack> items) { throw new UnsupportedOperationException(); }
    public int getSlots() { throw new UnsupportedOperationException(); }
    public ItemStack getStackInSlot(int slot) { throw new UnsupportedOperationException(); }
    public void copyInto(NonNullList<ItemStack> target) { throw new UnsupportedOperationException(); }
}
