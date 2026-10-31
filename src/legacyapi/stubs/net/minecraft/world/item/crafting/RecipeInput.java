// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item.crafting;

import net.minecraft.world.item.ItemStack;

public interface RecipeInput {
    ItemStack getItem(int slot);
    int size();
}
