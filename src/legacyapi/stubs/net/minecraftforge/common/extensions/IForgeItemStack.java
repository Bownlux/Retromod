// Compile-only stub of a Forge 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.common.extensions;

import net.minecraft.world.item.crafting.RecipeType;

public interface IForgeItemStack {
    default int getBurnTime(RecipeType<?> type) { throw new UnsupportedOperationException(); }
}
