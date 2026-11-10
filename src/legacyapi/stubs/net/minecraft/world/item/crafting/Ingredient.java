// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item.crafting;

import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// Vanilla and NeoForge make it final; Forge patches it open with these two constructors for its AbstractIngredient.
public class Ingredient implements Predicate<ItemStack> {
    protected Ingredient(HolderSet<Item> values) { throw new UnsupportedOperationException(); }
    protected Ingredient(HolderSet<Item> values, boolean isVanilla) { throw new UnsupportedOperationException(); }
    public Stream<Holder<Item>> items() { throw new UnsupportedOperationException(); }
    @Override public boolean test(ItemStack stack) { throw new UnsupportedOperationException(); }
}
