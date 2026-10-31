// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public interface Container {
    int getContainerSize();
    boolean isEmpty();
    ItemStack getItem(int slot);
    ItemStack removeItem(int slot, int amount);
    ItemStack removeItemNoUpdate(int slot);
    void setItem(int slot, ItemStack stack);
    default int getMaxStackSize() { throw new UnsupportedOperationException(); }
    default int getMaxStackSize(ItemStack stack) { throw new UnsupportedOperationException(); }
    void setChanged();
    boolean stillValid(Player player);
    default boolean canPlaceItem(int slot, ItemStack stack) { throw new UnsupportedOperationException(); }
}
