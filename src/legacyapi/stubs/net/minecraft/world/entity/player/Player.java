// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity.player;

import net.minecraft.util.Prediction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

public class Player extends LivingEntity {
    public Inventory getInventory() { throw new UnsupportedOperationException(); }
    public ItemEntity drop(ItemStack stack, boolean randomly, Prediction prediction) { throw new UnsupportedOperationException(); }
}
