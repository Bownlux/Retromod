// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.world.inventory;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public abstract class StackCopySlot extends Slot {
    public StackCopySlot(int slot, int x, int y) { super(null, slot, x, y); }
    protected abstract ItemStack getStackCopy();
    protected abstract void setStackCopy(ItemStack stack);
    @Override public final ItemStack getItem() { throw new UnsupportedOperationException(); }
    @Override public final void set(ItemStack stack) { throw new UnsupportedOperationException(); }
    @Override public final void setChanged() { throw new UnsupportedOperationException(); }
    @Override public ItemStack remove(int amount) { throw new UnsupportedOperationException(); }
}
