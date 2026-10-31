// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.capabilities;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class ItemCapability<T, C> extends BaseCapability<T, C> {
    private ItemCapability() { super(null, null, null); }
    public static <T> ItemCapability<T, Void> createVoid(Identifier name, Class<T> type) { throw new UnsupportedOperationException(); }
    public T getCapability(ItemStack stack, C context) { throw new UnsupportedOperationException(); }
}
