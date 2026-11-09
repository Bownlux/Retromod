// Compile-only stub of a NeoForge 1.21 to 26.2 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.items;

import net.minecraft.world.item.ItemStack;

public interface IItemHandlerModifiable extends IItemHandler {
    void setStackInSlot(int slot, ItemStack stack);
}
