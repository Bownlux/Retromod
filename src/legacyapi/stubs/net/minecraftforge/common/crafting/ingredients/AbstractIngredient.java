// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.common.crafting.ingredients;

import net.minecraft.core.HolderSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

public abstract class AbstractIngredient extends Ingredient {
    protected AbstractIngredient() {
        super(null, false);
        throw new UnsupportedOperationException();
    }
    protected AbstractIngredient(HolderSet<Item> values) {
        super(values);
        throw new UnsupportedOperationException();
    }
    public abstract boolean isSimple();
    public abstract IIngredientSerializer<? extends Ingredient> serializer();
}
