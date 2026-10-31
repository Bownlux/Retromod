// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item.crafting;

import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class Ingredient implements Predicate<ItemStack> {
    public Stream<Holder<Item>> items() { throw new UnsupportedOperationException(); }
    @Override public boolean test(ItemStack stack) { throw new UnsupportedOperationException(); }
}
