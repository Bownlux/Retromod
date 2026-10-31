// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world;

import com.mojang.serialization.Codec;
import net.minecraft.world.item.ItemStack;

public final class ItemStackWithSlot {
    public static final Codec<ItemStackWithSlot> CODEC = null;
    public ItemStackWithSlot(int slot, ItemStack stack) { throw new UnsupportedOperationException(); }
    public int slot() { throw new UnsupportedOperationException(); }
    public ItemStack stack() { throw new UnsupportedOperationException(); }
    public boolean isValidInContainer(int size) { throw new UnsupportedOperationException(); }
}
