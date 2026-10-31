// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity;

public enum EquipmentSlot {
    MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD, BODY, SADDLE;
    public enum Type { HAND, HUMANOID_ARMOR, ANIMAL_ARMOR, SADDLE }
    public Type getType() { throw new UnsupportedOperationException(); }
}
