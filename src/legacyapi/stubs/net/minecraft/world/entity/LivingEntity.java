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
    public void travel(net.minecraft.world.phys.Vec3 input) { throw new UnsupportedOperationException(); }
    protected void travelFlying(net.minecraft.world.phys.Vec3 input, float speed) { throw new UnsupportedOperationException(); }
    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.BlockPos pos) { throw new UnsupportedOperationException(); }
    public boolean onClimbable() { throw new UnsupportedOperationException(); }
    public boolean canUseSlot(EquipmentSlot slot) { throw new UnsupportedOperationException(); }
    public int hurtTime;
    public int deathTime;
    public boolean isBaby() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.item.ItemStack getMainHandItem() { throw new UnsupportedOperationException(); }
}
