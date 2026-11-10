// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity;

import net.minecraft.world.item.ItemStack;

public class LivingEntity extends Entity {
    public ItemStack getItemBySlot(EquipmentSlot slot) { throw new UnsupportedOperationException(); }
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) { throw new UnsupportedOperationException(); }
    public float getHealth() { throw new UnsupportedOperationException(); }
    public final float getMaxHealth() { throw new UnsupportedOperationException(); }
    public float getAbsorptionAmount() { throw new UnsupportedOperationException(); }
    public long getLootTableSeed() { throw new UnsupportedOperationException(); }
}
