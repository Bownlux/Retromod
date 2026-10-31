// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item.crafting;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

public class BrewingRecipe implements Recipe<BrewingInput> {
    public PotionIngredient getInput() { throw new UnsupportedOperationException(); }
    public PotionIngredient getReagent() { throw new UnsupportedOperationException(); }
    public ItemStackTemplate getOutput() { throw new UnsupportedOperationException(); }
    public boolean matches(BrewingInput input) { throw new UnsupportedOperationException(); }
    public ItemStack assemble(BrewingInput input) { throw new UnsupportedOperationException(); }
}
