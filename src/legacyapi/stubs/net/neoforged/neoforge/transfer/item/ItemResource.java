// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.transfer.item;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.resource.Resource;

public final class ItemResource implements Resource {
    public static final ItemResource EMPTY = null;
    public static ItemResource of(ItemStack stack) { throw new UnsupportedOperationException(); }
    @Override public boolean isEmpty() { throw new UnsupportedOperationException(); }
    public boolean matches(ItemStack stack) { throw new UnsupportedOperationException(); }
    public ItemStack toStack(int amount) { throw new UnsupportedOperationException(); }
    public int getMaxStackSize() { throw new UnsupportedOperationException(); }
}
