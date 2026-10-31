// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item.crafting;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.component.predicates.PotionsPredicate;
import net.minecraft.world.item.ItemStack;

public final class PotionIngredient implements Predicate<ItemStack> {
    public Ingredient ingredient() { throw new UnsupportedOperationException(); }
    public Optional<PotionsPredicate> potions() { throw new UnsupportedOperationException(); }
    @Override public boolean test(ItemStack stack) { throw new UnsupportedOperationException(); }
}
