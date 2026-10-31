// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class Slot {
    public int x;
    public int y;
    public Slot(Container container, int slot, int x, int y) { throw new UnsupportedOperationException(); }
    public int getSlotIndex() { throw new UnsupportedOperationException(); }
    public void onQuickCraft(ItemStack picked, ItemStack original) { throw new UnsupportedOperationException(); }
    public boolean mayPlace(ItemStack stack) { throw new UnsupportedOperationException(); }
    public ItemStack getItem() { throw new UnsupportedOperationException(); }
    public void set(ItemStack stack) { throw new UnsupportedOperationException(); }
    public void setChanged() { throw new UnsupportedOperationException(); }
    public int getMaxStackSize() { throw new UnsupportedOperationException(); }
    public int getMaxStackSize(ItemStack stack) { throw new UnsupportedOperationException(); }
    public ItemStack remove(int amount) { throw new UnsupportedOperationException(); }
    public boolean mayPickup(Player player) { throw new UnsupportedOperationException(); }
    public boolean isSameInventory(Slot other) { throw new UnsupportedOperationException(); }
    public int getContainerSlot() { throw new UnsupportedOperationException(); }
}
