// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.transfer.access;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

public interface ItemAccess {
    static ItemAccess forStack(ItemStack stack) { throw new UnsupportedOperationException(); }
    ItemResource getResource();
    int getAmount();
}
