// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity;

import net.minecraft.world.item.ItemStack;

public class LivingEntity extends Entity {
    public ItemStack getItemBySlot(EquipmentSlot slot) { throw new UnsupportedOperationException(); }
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) { throw new UnsupportedOperationException(); }
}
