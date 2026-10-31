// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

public interface WorldlyContainer extends Container {
    int[] getSlotsForFace(Direction side);
    boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side);
    boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side);
}
