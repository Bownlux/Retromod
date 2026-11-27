// Compile-only stub of a Forge 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.event;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

public final class ForgeEventFactory {
    private ForgeEventFactory() {}
    public static int getItemBurnTime(ItemStack stack, int burnTime, RecipeType<?> type) { throw new UnsupportedOperationException(); }
}
