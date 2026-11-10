// Compile-only stub of a NeoForge 1.21 to 26.2 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.items;

import net.minecraft.world.item.ItemStack;

public interface IItemHandler {
    int getSlots();
    ItemStack getStackInSlot(int slot);
    ItemStack insertItem(int slot, ItemStack stack, boolean simulate);
    ItemStack extractItem(int slot, int amount, boolean simulate);
    int getSlotLimit(int slot);
    boolean isItemValid(int slot, ItemStack stack);
}
