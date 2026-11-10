// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.common.crafting.ingredients;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.item.crafting.Ingredient;

public interface IIngredientSerializer<T extends Ingredient> {
    MapCodec<? extends T> codec();
}
