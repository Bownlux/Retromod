// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item;

import net.minecraft.core.Holder;

public final class ItemStackTemplate {
    public static final com.mojang.serialization.MapCodec<ItemStackTemplate> MAP_CODEC = null;
    public Holder<Item> item() { throw new UnsupportedOperationException(); }
    public int count() { throw new UnsupportedOperationException(); }
    public net.minecraft.core.component.DataComponentPatch components() { throw new UnsupportedOperationException(); }
    public ItemStack create() { throw new UnsupportedOperationException(); }
    public Holder<Item> typeHolder() { throw new UnsupportedOperationException(); }
    public static ItemStackTemplate fromNonEmptyStack(net.minecraft.world.item.ItemStack stack) { throw new UnsupportedOperationException(); }
}
