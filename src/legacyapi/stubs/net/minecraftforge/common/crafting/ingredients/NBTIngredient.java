// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.common.crafting.ingredients;

import net.minecraft.core.HolderSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

public class NBTIngredient extends AbstractIngredient {
    public static final IIngredientSerializer<NBTIngredient> SERIALIZER = null;
    private NBTIngredient(HolderSet<Item> values, CompoundTag nbt, boolean strict) {
        super(values);
        throw new UnsupportedOperationException();
    }
    @Override public boolean isSimple() { throw new UnsupportedOperationException(); }
    @Override public IIngredientSerializer<? extends Ingredient> serializer() { throw new UnsupportedOperationException(); }
}
