// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponentHolder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.common.MutableDataComponentHolder;

public final class ItemStack implements DataComponentHolder, MutableDataComponentHolder {
    public static final ItemStack EMPTY = null;
    public static final MapCodec<ItemStack> MAP_CODEC = null;
    public static final Codec<ItemStack> CODEC = null;
    public static final Codec<ItemStack> OPTIONAL_CODEC = null;
    public ItemStack(ItemLike item, int count) { throw new UnsupportedOperationException(); }
    public boolean isEmpty() { throw new UnsupportedOperationException(); }
    public int getCount() { throw new UnsupportedOperationException(); }
    public void setCount(int count) { throw new UnsupportedOperationException(); }
    public void grow(int amount) { throw new UnsupportedOperationException(); }
    public void shrink(int amount) { throw new UnsupportedOperationException(); }
    public ItemStack copy() { throw new UnsupportedOperationException(); }
    public ItemStack copyWithCount(int count) { throw new UnsupportedOperationException(); }
    public ItemStack split(int amount) { throw new UnsupportedOperationException(); }
    public int getMaxStackSize() { throw new UnsupportedOperationException(); }
    public boolean isStackable() { throw new UnsupportedOperationException(); }
    public Item getItem() { throw new UnsupportedOperationException(); }
    public static boolean isSameItem(ItemStack first, ItemStack second) { throw new UnsupportedOperationException(); }
    public static boolean isSameItemSameComponents(ItemStack first, ItemStack second) { throw new UnsupportedOperationException(); }
    public static boolean matches(ItemStack first, ItemStack second) { throw new UnsupportedOperationException(); }
    @Override public <T> T get(DataComponentType<? extends T> type) { throw new UnsupportedOperationException(); }
    @Override public <T> T set(DataComponentType<T> type, T value) { throw new UnsupportedOperationException(); }
    @Override public <T> T remove(DataComponentType<? extends T> type) { throw new UnsupportedOperationException(); }
    public <T, C> T getCapability(ItemCapability<T, C> capability, C context) { throw new UnsupportedOperationException(); }
}
