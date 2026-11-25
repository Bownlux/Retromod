// Compile-only stub of a 1.21.2 to 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.block.entity;

public class FuelValues {
    private FuelValues() {}

    // Forge 26.2 patches this in: the item's own burn time, else the table's, then Forge's burn time event.
    public int burnDuration(net.minecraft.world.item.ItemStack stack, net.minecraft.world.item.crafting.RecipeType<?> type) { throw new UnsupportedOperationException(); }
}
