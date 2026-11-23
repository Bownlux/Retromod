// Compile-only stub of a NeoForge 21.2 to 26.2 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.common.extensions;

import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.FuelValues;

public interface IItemStackExtension {
    default int getBurnTime(RecipeType<?> type, FuelValues fuelValues) { throw new UnsupportedOperationException(); }
}
