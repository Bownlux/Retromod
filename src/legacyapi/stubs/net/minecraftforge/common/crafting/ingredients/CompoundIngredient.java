// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.common.crafting.ingredients;

import java.util.List;
import net.minecraft.world.item.crafting.Ingredient;

public class CompoundIngredient extends AbstractIngredient {
    public static final IIngredientSerializer<CompoundIngredient> SERIALIZER = null;
    private CompoundIngredient(List<Ingredient> children) {
        super();
        throw new UnsupportedOperationException();
    }
    public static Ingredient of(Ingredient... children) { throw new UnsupportedOperationException(); }
    @Override public boolean isSimple() { throw new UnsupportedOperationException(); }
    @Override public IIngredientSerializer<? extends Ingredient> serializer() { throw new UnsupportedOperationException(); }
}
