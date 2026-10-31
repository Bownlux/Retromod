// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class SimpleContainer implements Container {
    public SimpleContainer(int size) { throw new UnsupportedOperationException(); }
    @Override public int getContainerSize() { throw new UnsupportedOperationException(); }
    @Override public boolean isEmpty() { throw new UnsupportedOperationException(); }
    @Override public ItemStack getItem(int slot) { throw new UnsupportedOperationException(); }
    @Override public ItemStack removeItem(int slot, int amount) { throw new UnsupportedOperationException(); }
    @Override public ItemStack removeItemNoUpdate(int slot) { throw new UnsupportedOperationException(); }
    @Override public void setItem(int slot, ItemStack stack) { throw new UnsupportedOperationException(); }
    @Override public void setChanged() { throw new UnsupportedOperationException(); }
    @Override public boolean stillValid(Player player) { throw new UnsupportedOperationException(); }
}
